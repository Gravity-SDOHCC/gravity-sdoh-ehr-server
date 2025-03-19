package ca.uhn.fhir.jpa.starter.gravity.models;

import java.util.HashMap;
import java.util.Map;

public class ActiveTask {
	private String taskId;
	private String externalTaskId;

	public ActiveTask(String taskId, String externalTaskId) {
		this.taskId = taskId;
		this.externalTaskId = externalTaskId;
	}

	public String getTaskId() {
		return this.taskId;
	}

	public String getExternalTaskId() {
		return this.externalTaskId;
	}

	public Map<String, Object> toMap() {
		HashMap<String, Object> map = new HashMap<>();
		map.put("taskId", this.taskId);
		map.put("externalTaskId", this.externalTaskId);
		return map;
	}

	@Override
	public String toString() {
		return "ActiveTask " + this.taskId + " (" + this.externalTaskId + ")";
	}
}
