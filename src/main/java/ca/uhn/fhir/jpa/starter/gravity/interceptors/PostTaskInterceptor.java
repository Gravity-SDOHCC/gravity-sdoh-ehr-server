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
import ca.uhn.fhir.jpa.starter.gravity.services.TaskService;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import ca.uhn.fhir.rest.gclient.DateClientParam;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.gravity.ServerLogger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import ca.uhn.fhir.rest.gclient.TokenClientParam;

@Service
public class PostTaskInterceptor extends InterceptorAdapter {

	@Autowired
	private TaskService taskService;
	@Autowired
	private ServletRequest servletRequest;
	private static final AppProperties appProperties = new AppProperties();
	private static final Logger logger = ServerLogger.getLogger();
	private IGenericClient client;
	private FhirContext ctx;

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void handleTaskCreation(IBaseResource theResource, RequestDetails theRequestDetails,
			ResponseDetails theResponseDetails) {
		if (!(theResource instanceof Task)) {
			return;
		}
		Task createdTask = (Task) theResource;

		Reference ownerReference = createdTask.getOwner();
		String ownerReferenceUrl = ownerReference.getReference();
		String ownerServerBaseUrl = ownerReferenceUrl.substring(0, ownerReferenceUrl.indexOf("/Organization"));

		String baseUrl = theRequestDetails.getFhirServerBase();
		updateTaskReferences(createdTask, baseUrl);

		sendTaskToReceiver(createdTask, ownerServerBaseUrl, theRequestDetails);
	}

	@Scheduled(fixedRate = 60000)
	public void pollTaskUpdates() {
		// TODO: dynamically get the base URL. env variable maybe?
		String serverBaseUrl = "http://localhost:8080/fhir";
		setupClient(serverBaseUrl);
		try {
			List<Task> receivedTasks = fetchReceivedTasksFromSelf(serverBaseUrl);

			for (Task task : receivedTasks) {
				String receiverRefUrl = task.getOwner().getReference();
				String receiverUrl = receiverRefUrl.substring(0, receiverRefUrl.indexOf("/Organization"));
				IIdType requesterReference = task.getRequester().getReferenceElement();
				String requesterId = requesterReference.getIdPart();

				Date lastUpdated = task.getMeta().getLastUpdated();
				List<Task> updatedTasks = fetchUpdatedTasksFromReceiverServer(receiverUrl, requesterId, lastUpdated);

				for (Task updatedTask : updatedTasks) {
					Task existingTask = client.read().resource(Task.class).withId(updatedTask.getId()).execute();
					if (existingTask != null && !existingTask.getStatus().equals(updatedTask.getStatus())) {
						existingTask.setStatus(updatedTask.getStatus());
						Task newUpdatedTask = (Task) client.update().resource(existingTask).execute().getResource();
						if (newUpdatedTask != null) {
							logger.info(
									"Updated Task: " + newUpdatedTask.getId() + " with status: " + newUpdatedTask.getStatus());
						} else {
							logger.severe("Failed to update Task: " + existingTask.getId() + " status.");
						}
					}
				}
			}
		} catch (Exception e) {

			logger.severe(
					"Something wrong happened when polling task for update or updating the status on: " + serverBaseUrl
							+ " " + e.getMessage());
		}
	}

	private void updateTaskReferences(Task task, String baseUrl) {
		task.getPartOf().forEach(reference -> {
			updateReference(reference, baseUrl);
		});

		updateReference(task.getRequester(), baseUrl);
		updateReference(task.getFor(), baseUrl);
		updateReference(task.getFocus(), baseUrl);
	}

	private void updateReference(Reference reference, String baseUrl) {
		if (reference != null && !reference.getReference().startsWith("http")) {
			reference.setReference(baseUrl + "/" + reference.getReference());
		}
	}

	private void sendTaskToReceiver(Task task, String receiverUrl, RequestDetails theRequestDetails) {
		setupClient(receiverUrl);

		try {
			MethodOutcome outcome = client.create().resource(task).execute();

			if (outcome.getCreated()) {
				task.setStatus(Task.TaskStatus.RECEIVED);
				Task updatedTask = taskService.updateTask(task, theRequestDetails);
				if (updatedTask != null) {
					logger.info("Updated Task: " + updatedTask.getId() + " with status: " + updatedTask.getStatus());
				} else {
					logger.severe("Failed to update Task: " + task.getId() + " status to received.");
				}
			}
		} catch (Exception e) {
			logger.severe("Failed to send Task to receiver: " + e.getMessage());
		}
	}

	private List<Task> fetchUpdatedTasksFromReceiverServer(String receiverUrl, String requesterId, Date lastUpdated) {
		setupClient(receiverUrl);

		DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
		String lastUpdatedParam = formatter.format(lastUpdated.toInstant());

		Bundle response = client.search()
				.forResource(Task.class)
				.where(new ReferenceClientParam(Task.SP_REQUESTER).hasId(requesterId))
				.and(new DateClientParam("_lastUpdated").afterOrEquals().millis(lastUpdatedParam))
				.returnBundle(Bundle.class)
				.execute();

		return response.getEntry().stream()
				.map(Bundle.BundleEntryComponent::getResource)
				.filter(resource -> resource instanceof Task)
				.map(resource -> (Task) resource)
				.collect(Collectors.toList());
	}

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

	private RequestDetails getRequestDetails() {
		ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder
				.getRequestAttributes();
		return (RequestDetails) requestAttributes.getAttribute("ca.uhn.fhir.rest.server.RestfulServer.REQUEST_DETAILS",
				RequestAttributes.SCOPE_REQUEST);
	}

}
