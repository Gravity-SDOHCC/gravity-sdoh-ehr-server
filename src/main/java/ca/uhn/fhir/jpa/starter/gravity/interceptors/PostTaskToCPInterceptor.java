package ca.uhn.fhir.jpa.starter.gravity.interceptors;

import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import javax.servlet.ServletRequest;
import ca.uhn.fhir.rest.api.server.ResponseDetails;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Task;
import ca.uhn.fhir.rest.api.MethodOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import ca.uhn.fhir.rest.gclient.DateClientParam;
import ca.uhn.fhir.jpa.starter.gravity.ServerLogger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import ca.uhn.fhir.rest.gclient.TokenClientParam;

/**
 * This class is an interceptor that is called whenever a Task resource is
 * created.
 */
@Service
public class PostTaskToCPInterceptor extends InterceptorAdapter {

  @Autowired
  private IFhirResourceDao<Task> taskDao;
  @Autowired
  private ServletRequest servletRequest;
  private static final Logger logger = ServerLogger.getLogger();
  private IGenericClient client;
  private FhirContext ctx;

  /**
   * This method is called whenever a Task resource is created. It extracts the
   * owner reference from
   * the Task and sends the Task to the receiver FHIR server. It also updates the
   * Task status to
   * RECEIVED if the Task was successfully sent to the receiver FHIR server.
   *
   * @param theResource       The Task resource that was created
   * @param theRequestDetails The request details
   *
   */
  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
  public void handleTaskCreation(IBaseResource theResource, RequestDetails theRequestDetails,
      ResponseDetails theResponseDetails) {
    if (!(theResource instanceof Task)) {
      return;
    }

    Task createdTask = (Task) theResource;

    // Extract the absolute reference URL of the referenced owner
    Reference ownerReference = createdTask.getOwner();
    String ownerReferenceUrl = ownerReference.getReference();
    String ownerServerBaseUrl = ownerReferenceUrl.substring(0, ownerReferenceUrl.indexOf("/Organization"));

    // Make sure all the references within the Task have absolute URLs
    String baseUrl = theRequestDetails.getFhirServerBase();
    updateTaskReferences(createdTask, baseUrl);

    // Send the post request containing the created Task to the receiver FHIR server
    sendTaskToReceiver(createdTask, ownerServerBaseUrl, theRequestDetails);
  }

  /**
   * This method is called every 60 seconds to poll the receiver FHIR server for
   * any updates to the
   * Task. It extracts the receiver URL from the owner reference and uses it to
   * poll the receiver
   * FHIR server for any updates to the Task. It then updates the Task status
   * accordingly.
   *
   *
   */
  @Scheduled(fixedRate = 60000) // Poll every 60 seconds (60000 ms)
  public void pollTaskUpdates() {
    // TODO: unable to pass an argument to the method. find a way to dynamically get
    // the server base url
    // String serverBaseUrl = getServerBaseUrl();
    String serverBaseUrl = "http://localhost:8080/fhir";
    try {
      List<Task> receivedTasks = fetchReceivedTasksFromSelf(serverBaseUrl);

      for (Task task : receivedTasks) {
        // Extract the receiver URL from the owner reference
        String receiverRefUrl = task.getOwner().getReference();
        String receiverUrl = receiverRefUrl.substring(0, receiverRefUrl.indexOf("/Organization"));
        IIdType requesterReference = task.getRequester().getReferenceElement();
        String requesterId = requesterReference.getIdPart();

        Date lastUpdated = task.getMeta().getLastUpdated();
        List<Task> updatedTasks = fetchUpdatedTasksFromReceiverServer(receiverUrl, requesterId, lastUpdated);

        for (Task updatedTask : updatedTasks) {
          Task existingTask = taskDao.read(new IdType(updatedTask.getId()));
          if (existingTask != null && !existingTask.getStatus().equals(updatedTask.getStatus())) {
            existingTask.setStatus(updatedTask.getStatus());
            taskDao.update(existingTask);
          }
        }
      }
    } catch (Exception e) {
      // TODO: handle exception
      logger.severe("Failed to send Task to receiver: " + e.getMessage());
    }

  }

  /**
   * This method is used to update all the references within a Task to have
   * absolute URLs.
   *
   * @param task    The Task resource
   * @param baseUrl The base URL of the FHIR server
   */
  private void updateTaskReferences(Task task, String baseUrl) {
    task.getPartOf().forEach(reference -> {
      updateReference(reference, baseUrl);
    });

    updateReference(task.getRequester(), baseUrl);
    updateReference(task.getFor(), baseUrl);
    updateReference(task.getFocus(), baseUrl);
  }

  /**
   * This method is used to update a reference to have an absolute URL.
   *
   * @param reference The reference to be updated
   * @param baseUrl   The base URL of the FHIR server
   */
  private void updateReference(Reference reference, String baseUrl) {
    if (reference != null && !reference.getReference().startsWith("http")) {
      reference.setReference(baseUrl + "/" + reference.getReference());
    }
  }

  /**
   * This method is used to send the created Task to the receiver FHIR server, and
   * update the Task status
   * to RECEIVED if the Task was successfully sent to the receiver FHIR server.
   *
   * @param task        The Task resource
   * @param receiverUrl The receiver URL
   *
   */
  private void sendTaskToReceiver(Task task, String receiverUrl, RequestDetails theRequestDetails) {
    setupClient(receiverUrl);

    try {
      MethodOutcome outcome = client.create().resource(task).execute();

      if (outcome.getCreated()) {
        task.setStatus(Task.TaskStatus.RECEIVED);
        taskDao.update(task, theRequestDetails);
      }
    } catch (Exception e) {
      logger.severe("Failed to send Task to receiver: " + e.getMessage());
      // Handle exception and take any necessary action
    }
  }

  /**
   * This method is used to check the receiver fhir server for updated task since
   * the last search.
   *
   * @param receiverUrl The receiver base URL
   * @param requesterId The requester ID
   * @param lastUpdated The last updated date
   * @return A list of Tasks that were updated since the last search
   */
  private List<Task> fetchUpdatedTasksFromReceiverServer(String receiverUrl, String requesterId, Date lastUpdated) {
    setupClient(receiverUrl);

    // Long lastUpdatedParam = lastUpdated.getTime();

    Bundle response = client.search()
        .forResource(Task.class)
        .where(new ReferenceClientParam(Task.SP_REQUESTER).hasId(requesterId))
        .and(new DateClientParam("_lastUpdated").afterOrEquals().millis(lastUpdated))
        .returnBundle(Bundle.class)
        .execute();

    return response.getEntry().stream()
        .map(Bundle.BundleEntryComponent::getResource)
        .filter(resource -> resource instanceof Task)
        .map(resource -> (Task) resource)
        .collect(Collectors.toList());
  }

  /**
   * This method is used to fetch all the Tasks with status RECEIVED from the FHIR
   * server.
   *
   * @param serverBaseUrl This FHIR server base URL
   * @return A list of Tasks with status RECEIVED
   */
  private List<Task> fetchReceivedTasksFromSelf(String serverBaseUrl) {
    setupClient(serverBaseUrl);

    Bundle response = client.search()
        .forResource(Task.class)
        .where(new TokenClientParam("status:not").exactly().code("rejected"))
        .and(new TokenClientParam("status:not").exactly().code("canceled"))
        .and(new TokenClientParam("status:not").exactly().code("completed"))
        .returnBundle(Bundle.class)
        .execute();

    return response.getEntry().stream()
        .map(Bundle.BundleEntryComponent::getResource)
        .filter(resource -> resource instanceof Task)
        .map(resource -> (Task) resource)
        .collect(Collectors.toList());
  }

  private void setupClient(String serverBaseUrl) {
    ctx = FhirContext.forR4();
    // Disable server validation (don't pull the server's metadata first)
    ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    ctx.getRestfulClientFactory().setConnectTimeout(20 * 1000);
    client = ctx.newRestfulGenericClient(serverBaseUrl);
  }

  private String getServerBaseUrl() {
    String scheme = servletRequest.getScheme();
    String serverName = servletRequest.getServerName();
    int serverPort = servletRequest.getServerPort();
    String contextPath = servletRequest.getServletContext().getContextPath();
    return scheme + "://" + serverName + ":" + serverPort + contextPath;
  }

}
