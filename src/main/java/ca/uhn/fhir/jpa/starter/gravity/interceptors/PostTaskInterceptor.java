package ca.uhn.fhir.jpa.starter.gravity.interceptors;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.ServerLogger;
import ca.uhn.fhir.jpa.starter.authorization.AuthorizationController;
import ca.uhn.fhir.jpa.starter.gravity.models.ActiveTask;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Interceptor
public class PostTaskInterceptor {
	@Autowired
	AppProperties appProperties;

	private static final Logger logger = ServerLogger.getLogger();

	private final FhirContext ctx = FhirContext.forR4();

	private static Map<String, String> activeTasksMap = Collections.synchronizedMap(new HashMap<String, String>());

	private static String thisServerBaseUrl = "";

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void handleTaskCreation(
			IBaseResource theResource, RequestDetails theRequestDetails, ResponseDetails theResponseDetails) {
		if (!(theResource instanceof Task)) {
			return;
		}
		thisServerBaseUrl = theRequestDetails.getFhirServerBase();
		Task createdTask = (Task) theResource;
		String ownerServerBaseUrl = getTaskOwnerServerBaseUrl(createdTask);

		if (ownerServerBaseUrl != null) {
			updateTaskReferences(createdTask, thisServerBaseUrl);
			String recipientTaskId = sendTaskToReceiver(createdTask, ownerServerBaseUrl);
			ActiveTask activeTask = new ActiveTask(createdTask.getIdPart(), recipientTaskId);
			AuthorizationController.getDB().write(activeTask);
			activeTasksMap = AuthorizationController.getDB().getActiveTasks();
			if (recipientTaskId == null) {
				logger.warning("Failed to send task " + activeTask.getTaskId() + " to recipient server:  "
						+ ownerServerBaseUrl);
			} else {
				logger.info("Active TASKS: " + activeTasksMap.size() + ". Active sever task "
						+ activeTask.getTaskId()
						+ " is linked to recipient task " + activeTask.getExternalTaskId());
			}
		} else {
			logger.warning("Cannot send task to Owner: the owner's sever base URL is unknown ");
		}
	}

	@Scheduled(fixedRate = 10000)
	public void pollTaskUpdates() {
		if (thisServerBaseUrl.isEmpty()) thisServerBaseUrl = appProperties.getServer_address();
		IGenericClient ehrClient = setupClient(thisServerBaseUrl);
		try {
			List<Task> activeTasks = fetchActiveTasksFromSelf(ehrClient);
			for (Task task : activeTasks) {
				String receiverUrl = getTaskOwnerServerBaseUrl(task);
				if (task != null && task.getStatus() == Task.TaskStatus.REQUESTED) {
					if (activeTasksMap.get(task.getIdPart()) != null
							&& !activeTasksMap.get(task.getIdPart()).equals("null")) {
						updateTaskStatus(ehrClient, task, task, "received");
					} else {
						if (receiverUrl != null) {
							updateTaskReferences(task, thisServerBaseUrl);
							String recipientTaskId = sendTaskToReceiver(task, receiverUrl);
							if (recipientTaskId != null) {
								ActiveTask activeTask = new ActiveTask(task.getIdPart(), recipientTaskId);
								AuthorizationController.getDB().updateActiveTask(activeTask);
								activeTasksMap = AuthorizationController.getDB().getActiveTasks();
								logger.info("Active TASKS: " + activeTasksMap.size() + ". Active sever task "
										+ task.getIdPart() + " is linked to recipient task "
										+ activeTasksMap.get(task.getIdPart()));
							}
						}
					}

				} else {

					if (receiverUrl != null) {
						String updatedTaskId = activeTasksMap.get(task.getIdPart());
						logger.info("THE UPDATED TASK ID IS " + updatedTaskId);
						if (updatedTaskId.equals(null) || updatedTaskId.equals("null")) {
							Boolean success = AuthorizationController.getDB().deleteActiveTask(task.getIdPart());
							logger.info("SUCESSFULLY UNTRACKED TASK " + updatedTaskId + "?? " + success);
							activeTasksMap = AuthorizationController.getDB().getActiveTasks();
							continue;
						}
						Task updatedTask = fetchUpdatedTaskFromReceiverServer(receiverUrl, updatedTaskId);
						String status = task.getStatus().toCode();
						// Checking if the ehr user has cancelled the initiated task, if so, cancel the
						// receiver task as well.
						if (updatedTask != null && status.equals("cancelled")) {
							IGenericClient receiverClient = setupClient(receiverUrl);
							updateTaskStatus(receiverClient, task, updatedTask, "cancelled");
							// Now checking if the receiver task status has changed. If so, update the
							// initiated task on this server.
						} else if (updatedTask != null
								&& updatedTask.getStatus() != Task.TaskStatus.REQUESTED
								&& !updatedTask.getStatus().equals(task.getStatus())) {
							status = updatedTask.getStatus().toCode();
							if (status.equals("completed")) {
								String procedureRef = ((Reference)
												updatedTask.getOutputFirstRep().getValue())
										.getReference();
								if (procedureRef != null) {
									String procedureId = procedureRef.substring(procedureRef.indexOf("/"));
									logger.info("Task is completed, retrieving the procedure " + procedureId
											+ " from external server " + receiverUrl);
									IGenericClient Client = setupClient(receiverUrl);
									Procedure procedure = Client.read()
											.resource(Procedure.class)
											.withId(procedureId)
											.execute();
									logger.info("Procedure retrieved successfully, now saving...");
									ehrClient.update().resource(procedure).execute();
								}
							}
							updateTaskStatus(ehrClient, updatedTask, task, status);
						}

						if (status == "cancelled" || status == "rejected" || status == "completed") {
							AuthorizationController.getDB().deleteActiveTask(task.getIdPart());
							activeTasksMap = AuthorizationController.getDB().getActiveTasks();
						}
					} else {
						logger.warning("Cannot poll updates on task " + task.getIdPart()
								+ ": The task owner base URL is unknown.");
						continue;
					}
				}
			}
		} catch (Exception e) {

			logger.severe("Something wrong happened when polling task for update or updating the status on server: "
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

	private boolean updateTaskStatus(IGenericClient client, Task updatedTask, Task taskToUpdate, String newStatus) {
		try {
			logger.info("Updating task " + taskToUpdate.getIdPart() + " status to  " + newStatus);
			// Update the status and persist the Task
			taskToUpdate.setStatus(Task.TaskStatus.fromCode(newStatus));
			taskToUpdate.setStatusReason(updatedTask.getStatusReason());
			taskToUpdate.setOutput(updatedTask.getOutput());
			Task newUpdatedTask =
					(Task) client.update().resource(taskToUpdate).execute().getResource();
			if (newUpdatedTask != null) {
				logger.info(
						"Updated Task: " + newUpdatedTask.getIdPart() + " with status: " + newUpdatedTask.getStatus());
				return true;
			} else {
				logger.severe("Failed to update Task: " + taskToUpdate.getIdPart() + " status.");
			}
		} catch (Exception e) {
			logger.severe("Failed to update task with id " + taskToUpdate.getIdPart() + " to " + newStatus + ": "
					+ e.getMessage());
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
			logger.severe("Failed to send Task to receiver " + receiverBaseUrl + ": " + e.getMessage());
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

		Organization org = client.read()
				.resource(Organization.class)
				.withId(organizationId)
				.execute();
		return org;
	}

	private String getTaskOwnerServerBaseUrl(Task task) {
		if (task.getOwner() == null || task.getOwner().getReference() == null) {
			return null;
		}

		String[] ownerReferenceParts = task.getOwner().getReference().split("/");
		String ownerType = ownerReferenceParts[0];
		String ownerId = ownerReferenceParts[1];
		String ownerServerBaseUrl = null;

		if (ownerType.equals("Organization")) {
			Organization owner = fetchOrganization(thisServerBaseUrl, ownerId);
			List<ContactPoint> telecoms = owner.getContactFirstRep().getTelecom();
			for (ContactPoint telecom : telecoms) {
				if (telecom.hasSystem() && telecom.getSystem().equals(ContactPointSystem.URL)) {
					ownerServerBaseUrl = telecom.getValue();
					break;
				}
			}
		}

		return ownerServerBaseUrl;
	}

	private List<Task> fetchActiveTasksFromSelf(IGenericClient client) {
		Integer size = activeTasksMap.size();
		logger.info("PostTaskInterceptor::fetchActiveTasksFromSelf: CHECKING ACTIVE TASKS: " + size);
		if (size == 0) {
			try {
				activeTasksMap = AuthorizationController.getDB().getActiveTasks();
				size = activeTasksMap.size();
			} catch (Exception e) {
				logger.severe(e.getMessage());
			}
			if (size == 0) return new ArrayList<Task>();
		}
		List<String> taskIdsToUpdate = new ArrayList<>(activeTasksMap.keySet());
		// Build a comma-separated string of IDs
		String idList = String.join(",", taskIdsToUpdate);

		// Search for tasks with the specified IDs
		Bundle response = client.search()
				.forResource(Task.class)
				.where(new TokenClientParam("_id").exactly().code(idList))
				.returnBundle(Bundle.class)
				.execute();

		List<Task> tasks = response.getEntry().stream()
				.map(bundleEntryComponent -> (Task) bundleEntryComponent.getResource())
				.collect(Collectors.toList());

		if (tasks.isEmpty()) {
			taskIdsToUpdate.forEach(id -> {
				AuthorizationController.getDB().deleteActiveTask(id);
				activeTasksMap = AuthorizationController.getDB().getActiveTasks();
			});
		}
		return tasks;
	}

	private IGenericClient setupClient(String serverBaseUrl) {
		ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ctx.getRestfulClientFactory().setConnectTimeout(20 * 1000);
		return ctx.newRestfulGenericClient(serverBaseUrl);
	}
}
