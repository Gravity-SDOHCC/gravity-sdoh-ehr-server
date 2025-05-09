package ca.uhn.fhir.jpa.starter.authorization;

import ca.uhn.fhir.jpa.starter.ServerLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Logger;

public class RegistrationEndpoint {
	private RegistrationEndpoint() {
		throw new IllegalStateException("Register Client Utility class");
	}

	private static final Logger logger = ServerLogger.getLogger();

	public static ResponseEntity<String> handleRegisterClient(String redirectUri) {
		// Escape all the query parameters
		redirectUri = StringEscapeUtils.escapeJava(redirectUri);

		logger.info("RegisterEndpoint::Register: /register/client");
		logger.fine("RegisterClient:RedirectURI: " + redirectUri);

		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		String clientId = UUID.randomUUID().toString();
		String clientSecret = RandomStringUtils.randomAlphanumeric(256);
		Client newClient = new Client(clientId, clientSecret, redirectUri);

		if (AuthorizationController.getDB().write(newClient))
			return new ResponseEntity<>(gson.toJson(newClient.toMap()), HttpStatus.CREATED);
		else {
			HashMap<String, String> errorMap = new HashMap<>();
			errorMap.put(
					"error",
					"Unable to register Client. Please provide a valid and unique redirect URI.\n Check the '/debug/Clients' endpoint to retrieve your client credentials if already registered.");
			return new ResponseEntity<>(gson.toJson(errorMap), HttpStatus.BAD_REQUEST);
		}
	}
}
