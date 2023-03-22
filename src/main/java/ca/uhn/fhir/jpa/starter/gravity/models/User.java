package ca.uhn.fhir.jpa.starter.gravity.models;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.text.StringEscapeUtils;

import ca.uhn.fhir.jpa.starter.gravity.controllers.AuthorizationController;

public class User {

  private String username;
  private String password;
  private String providerId;
  private String createdDate;
  private String refreshToken;

  public User(String username, String password) {
    this(username, password, null);
  }

  public User(String username, String password, String providerId) {
    this(username, password, providerId, null, null);
  }

  public User(String username, String password, String providerId, String createdDate, String refreshToken) {
    // Escape all the inputs (since it could be from the browser)
    username = StringEscapeUtils.escapeJava(username);
    password = StringEscapeUtils.escapeJava(password);
    providerId = StringEscapeUtils.escapeJava(providerId);
    createdDate = StringEscapeUtils.escapeJava(createdDate);
    refreshToken = StringEscapeUtils.escapeJava(refreshToken);

    this.username = username;
    this.password = password;
    this.providerId = providerId;
    this.createdDate = createdDate;
    this.refreshToken = refreshToken;
  }

  public String getProviderId() {
    return this.providerId;
  }

  public String getUsername() {
    return this.username;
  }

  public String getPassword() {
    return this.password;
  }

  public String getCreatedDate() {
    return this.createdDate;
  }

  public String getRefreshToken() {
    return this.refreshToken;
  }

  public static User getUser(String username) {
    return AuthorizationController.getDB().readUser(username);
  }

  public Map<String, Object> toMap() {
    HashMap<String, Object> map = new HashMap<>();
    map.put("username", this.username);
    map.put("password", this.password);
    map.put("provider_id", this.providerId);
    return map;
  }

  @Override
  public String toString() {
    return "User " + this.username + "(" + this.providerId + "): password(" + this.password + ")";
  }
}
