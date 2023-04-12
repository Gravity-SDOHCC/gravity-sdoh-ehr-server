package ca.uhn.fhir.jpa.starter.gravity.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Task;
import ca.uhn.fhir.rest.api.server.RequestDetails;

import ca.uhn.fhir.jpa.starter.gravity.ServerLogger;
import java.util.logging.Logger;

@Service
public class TaskService {

  @Autowired
  private IFhirResourceDao<Task> taskDao;

  private static final Logger logger = ServerLogger.getLogger();

  public Task createTask(Task task, RequestDetails theRequestDetails) {
    try {
      DaoMethodOutcome outcome = taskDao.create(task, theRequestDetails);
      return (Task) outcome.getResource();
    } catch (Exception e) {
      // Log the error
      logger.severe("Failed to create Task: " + e.getMessage());
      return null;
    }
  }

  public Task readTask(IIdType taskId, RequestDetails theRequestDetails) {
    try {
      if (theRequestDetails == null) {
        return taskDao.read(taskId);
      } else {
        return taskDao.read(taskId, theRequestDetails);
      }
    } catch (Exception e) {
      // Log the error
      logger.severe("Failed to read Task: " + e.getMessage());
      return null;
    }
  }

  public Task updateTask(Task task, RequestDetails theRequestDetails) {
    DaoMethodOutcome outcome = null;
    try {
      if (theRequestDetails == null) {
        outcome = taskDao.update(task);
      } else {
        outcome = taskDao.update(task, theRequestDetails);
      }
      return (Task) outcome.getResource();
    } catch (Exception e) {
      // Log the error
      logger.severe("Failed to update Task: " + e.getMessage());
      return null;
    }
  }
}
