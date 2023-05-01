package ca.uhn.fhir.jpa.starter.gravity.interceptors;

import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;

import ca.uhn.fhir.rest.api.MethodOutcome;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.jpa.starter.gravity.ServerLogger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class PostTaskInterceptor extends InterceptorAdapter {
	private static final Logger logger = ServerLogger.getLogger();

	private final FhirContext ctx = FhirContext.forR4();;
	private static List<String> taskIdsToUpdate = Collections.synchronizedList(new ArrayList<>());

	private static Map<String, String> activeTasksMap = Collections.synchronizedMap(new HashMap<String, String>());
	private static String thisServerBaseUrl = "";

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void handleTaskCreation(IBaseResource theResource, RequestDetails theRequestDetails,
			ResponseDetails theResponseDetails) {
		if (!(theResource instanceof Task)) {
			return;
		}
		Task createdTask = (Task) theResource;
		thisServerBaseUrl = theRequestDetails.getFhirServerBase();
		String ownerServerBaseUrl = getTaskOwnerServerBaseUrl(createdTask);

		if (ownerServerBaseUrl != null) {
			updateTaskReferences(createdTask, thisServerBaseUrl);
			String recipientTaskId = sendTaskToReceiver(createdTask, ownerServerBaseUrl);

			if (recipientTaskId != null) {
				activeTasksMap.put(createdTask.getIdPart(), recipientTaskId);
				taskIdsToUpdate.add(createdTask.getIdPart());
				logger.info("Active TASKS: " + activeTasksMap.size() + ". Active sever task " + createdTask.getIdPart()
						+ " is linked to recipient task " + activeTasksMap.get(createdTask.getIdPart()));
			}
		} else {
			logger.warning("Cannot send task to Owner: the owner's sever base URL is unknown ");
		}

	}

	@Scheduled(fixedRate = 10000)
	public void pollTaskUpdates() {
		IGenericClient ehrClient = setupClient(thisServerBaseUrl);
		try {
			List<Task> activeTasks = fetchActiveTasksFromSelf(ehrClient);
			for (Task task : activeTasks) {
				if (task != null && task.getStatus() == Task.TaskStatus.REQUESTED) {
					updateTaskStatus(ehrClient, task, "received");
				} else {

					String receiverUrl = getTaskOwnerServerBaseUrl(task);
					if (receiverUrl != null) {
						String updatedTaskId = activeTasksMap.get(task.getIdPart());
						if (updatedTaskId == null) {
							taskIdsToUpdate.remove(task.getIdPart());
							continue;
						}
						Task updatedTask = fetchUpdatedTaskFromReceiverServer(receiverUrl, updatedTaskId);

						if (updatedTask != null && updatedTask.getStatus() != Task.TaskStatus.REQUESTED
								&& !updatedTask.getStatus().equals(task.getStatus())) {
							String status = updatedTask.getStatus().toCode();
							updateTaskStatus(ehrClient, task, status);
							if (status == "cancelled" || status == "rejected" || status == "completed") {
								taskIdsToUpdate.remove(task.getIdPart());
								activeTasksMap.remove(task.getIdPart());
							}
						}
					} else {
						logger.warning(
								"Cannot poll updates on task " + task.getIdPart() + ": The task owner base URL is unknown.");
						continue;
					}
				}
			}
		} catch (Exception e) {

			logger.severe(
					"Something wrong happened when polling task for update or updating the status on server: "
							+ e.getMessage());
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

	private boolean updateTaskStatus(IGenericClient client, Task task, String newStatus) {
		try {
			logger.info("Updating task " + task.getIdPart() + " status to  " + newStatus);
			// Update the status and persist the Task
			task.setStatus(Task.TaskStatus.fromCode(newStatus));
			Task newUpdatedTask = (Task) client.update().resource(task).execute().getResource();
			if (newUpdatedTask != null) {
				logger.info(
						"Updated Task: " + newUpdatedTask.getIdPart() + " with status: " + newUpdatedTask.getStatus());
				return true;
			} else {
				logger.severe("Failed to update Task: " + task.getIdPart() + " status.");
			}
		} catch (Exception e) {
			logger.severe(
					"Failed to update task with id " + task.getIdPart() + " to " + newStatus + ": " + e.getMessage());
		}
		return false;
	}

	private String sendTaskToReceiver(Task task, String receiverBaseUrl) {
		// Implement your POST request logic to send the Task to the receiver server
		// Return taskId if successful, null otherwise
		IGenericClient client = setupClient(receiverBaseUrl);
		try {
			MethodOutcome outcome = client.create().resource(task).execute();
			Task t = (Task) outcome.getResource();
			return t.getIdElement().getIdPart();
		} catch (Exception e) {
			logger.severe("Failed to send Task to receiver: " + e.getMessage());
		}
		return null;
	}

	private Task fetchUpdatedTaskFromReceiverServer(String receiverUrl, String taskId) {
		IGenericClient client = setupClient(receiverUrl);

		Task task = client.read().resource(Task.class).withId(taskId).execute();
		return task;
	}

	private Organization fetchOrganization(String serverUrl, String organizationId) {
		IGenericClient client = setupClient(serverUrl);

		Organization org = client.read().resource(Organization.class).withId(organizationId).execute();
		return org;
	}

	private String getTaskOwnerServerBaseUrl(Task task) {
		String ownerReference = task.getOwner().getReference();
		String ownerId = ownerReference.substring(ownerReference.indexOf("/"));
		// Assumption here is that the owner is an Organization instance
		Organization owner = fetchOrganization(thisServerBaseUrl, ownerId);
		List<ContactPoint> telecoms = owner.getContactFirstRep().getTelecom();
		String ownerServerBaseUrl = null;
		for (ContactPoint telecom : telecoms) {
			if (telecom.hasSystem() && telecom.getSystem().equals(ContactPointSystem.URL)) {
				ownerServerBaseUrl = telecom.getValue();
				break;
			}
		}

		return ownerServerBaseUrl;

	}

	private List<Task> fetchActiveTasksFromSelf(IGenericClient client) {
		logger.info("CHECKING ACTIVE TASKS: " + taskIdsToUpdate.size());
		if (taskIdsToUpdate.size() == 0) {
			return new ArrayList<Task>();
		}
		// Build a comma-separated string of IDs
		String idList = String.join(",", taskIdsToUpdate);

		// Search for tasks with the specified IDs
		Bundle response = client.search().forResource(Task.class)
				.where(new TokenClientParam("_id").exactly().code(idList))
				.returnBundle(Bundle.class)
				.execute();

		return response.getEntry().stream()
				.map(bundleEntryComponent -> (Task) bundleEntryComponent.getResource())
				.collect(Collectors.toList());
	}

	private IGenericClient setupClient(String serverBaseUrl) {
		ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ctx.getRestfulClientFactory().setConnectTimeout(20 * 1000);
		return ctx.newRestfulGenericClient(serverBaseUrl);
	}

}
