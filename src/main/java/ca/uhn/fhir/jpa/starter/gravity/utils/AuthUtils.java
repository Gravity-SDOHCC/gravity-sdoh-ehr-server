package ca.uhn.fhir.jpa.starter.gravity.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.AlgorithmMismatchException;
import com.auth0.jwt.exceptions.InvalidClaimException;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.bcrypt.BCrypt;

import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.gravity.controllers.AuthorizationController;
import ca.uhn.fhir.jpa.starter.gravity.ServerLogger;
import ca.uhn.fhir.jpa.starter.gravity.models.Client;
import ca.uhn.fhir.jpa.starter.gravity.models.User;

public class AuthUtils {

  private AuthUtils() {
    throw new IllegalStateException("Utility class");
  }

  private static final String CLIENT_ID_KEY = "client_id";
  private static final String REDIRECT_URI_KEY = "redirect_uri";

  private static final Logger logger = ServerLogger.getLogger();

  private static final AppProperties appProperties = new AppProperties();

  /**
   * Populate DB with default clients and test users
   */
  public static void initializeDB() {
    List<Client> clients = new ArrayList<>();
    List<User> users = new ArrayList<>();

    Client cplocalhost = new Client("ebfacee0-8e06-4fd9-b8d3-7e0c81eb1ef4",
        "ANOh5rd7b4VUL3qJb7GN-dlNITx9BauQ9m4HnA93Gu22FMoByoXvBpQSb2gk9Yw-Nq66b6on-9JiQHsoW178HK8",
        "http://localhost:8082/login");
    Client ehrlocalhost = new Client("1c4d149f-9995-4c5c-ac42-018150437355",
        "YtKsfSIYPzOURSY9weWaW3kGGLnu30pcZLC43K4ezQ--ycNr_omKhkZqw3DtJX1LOo87ddNLF1PuOTFH9GzaIg",
        "http://localhost:8080/login");
    clients.add(cplocalhost);
    clients.add(ehrlocalhost);

    User ehrProvider = new User("provider", BCrypt.hashpw("password", BCrypt.gensalt()), "Smart-Practitioner-71482713");
    users.add(ehrProvider);

    loadClients(clients);
    loadUsers(users);
  }

  /**
   * Load DB with a list of clients if client does not exist
   */
  public static void loadClients(List<Client> clients) {
    for (Client client : clients) {
      if (Client.getClient(client.getId()) == null) {
        AuthorizationController.getDB().write(client);
      }
    }
  }

  /**
   * Load DB with a list of users if user does not exist
   */
  public static void loadUsers(List<User> users) {
    for (User user : users) {
      if (User.getUser(user.getUsername()) == null) {
        AuthorizationController.getDB().write(user);
      }
    }
  }

  /**
   * Get the FHIR base url from AppProperties
   *
   * @return the fhir base url
   */
  public static String getFhirBaseUrl() {
    String baseUrl = appProperties.getServer_address();
    if (baseUrl.endsWith("/"))
      return StringUtils.chop(baseUrl);
    else
      return baseUrl;
  }

  /**
   * Get supported auth response types
   *
   * @return String[] of supported response types
   */
  public static List<String> authResponseType() {
    String[] responseTypes = { "code", "refresh_token" };
    return Arrays.asList(responseTypes);
  }

  /**
   * http://hl7.org/fhir/smart-app-launch/conformance/index.html#core-capabilities
   * Get the array of all core capabilities
   *
   * @return String[] of core capabilities
   */
  public static List<String> coreCapabilities() {
    String[] capabilities = { "launch-standalone", "client-confidential-symmetric",
        "context-standalone-patient", "permission-patient", "permission-user" };
    return Arrays.asList(capabilities);
  }

  /**
   * Get the array of all supported scopes
   *
   * @return String[] of supported scopes
   */
  public static List<String> supportedScopes() {
    String[] scopes = { "patient/.*", "patient/*.read", "user/*.read", "user/.read", "offline_access", "launch",
        "launch/patient", "openid", "fhirUser" };
    return Arrays.asList(scopes);
  }

  /**
   * Check if the provided scope is supported
   *
   * @param String scope - the scope in question
   * @return true if the provided scope is supported, false otherwise
   */
  public static boolean isSupportedScope(String scope) {
    return supportedScopes().contains(scope);
  }

  /**
   * Generate Authorization code for client with a 2 min expiration time
   *
   * @param baseUrl        - the baseUrl for this server
   * @param clientId       - the client's client_id received in the request
   * @param redirecURI     - the client's redirect URI received in the request
   * @param providerId     - the selected test providerId
   * @param practitionerId - the selected test practitionerId
   * @return a signed JWT token for the authorization code
   */
  public static String generateAuthorizationCode(String baseUrl, String clientId, String redirectURI, String providerId,
      String practitionerId) {
    try {
      Algorithm algorithm = Algorithm.RSA256(AuthorizationController.getPublicKey(),
          AuthorizationController.getPrivateKey());
      Instant expTime = LocalDateTime.now().plusMinutes(2).atZone(ZoneId.systemDefault()).toInstant();
      return JWT.create().withIssuer(baseUrl).withExpiresAt(Date.from(expTime)).withIssuedAt(new Date())
          .withAudience(baseUrl).withClaim(CLIENT_ID_KEY, clientId).withClaim(REDIRECT_URI_KEY, redirectURI)
          .withClaim("providerId", providerId)
          .withClaim("practitionerId", practitionerId)
          .sign(algorithm);
    } catch (Exception e) {
      String msg = String.format("AuthorizationEndpoint::generateAuthorizationCode:Unable to generate code for %s",
          clientId);
      logger.log(Level.SEVERE, msg, e);
      return null;
    }
  }

  /**
   * Produce the client redirect uri with parameters
   *
   * @param redirectURI - the base client's redirect uri
   * @param attributes  - the parameters to add to the base redirect uri
   * @return formatted redirect uri
   */
  public static String getRedirect(String redirectURI, Map<String, String> attributes) {
    if (attributes.size() > 0) {
      redirectURI += "?";

      int i = 1;
      for (Map.Entry<String, String> entry : attributes.entrySet()) {
        redirectURI += entry.getKey() + "=" + entry.getValue();
        if (i != attributes.size())
          redirectURI += "&";

        i++;
      }
    }
    return redirectURI;
  }

  /**
   * Verify the Basic Authorization header to authenticate the requestor (client)
   *
   * @param request - the current request
   * @return the clientId from the authorization header if the clientID and secret
   *         provided match the registered ones
   */
  public static String clientIsAuthorized(HttpServletRequest request) {
    String authHeader = request.getHeader("Authorization");
    logger.log(Level.FINE, ("TokenEndpoint::AuthHeader: " + authHeader));
    if (authHeader != null) {
      Pattern pattern = Pattern.compile("Basic (.*)");
      Matcher matcher = pattern.matcher(authHeader);
      if (matcher.find() && matcher.groupCount() == 1) {
        String clientAuthorization = new String(Base64.getDecoder().decode(matcher.group(1)));
        Pattern clientAuthPattern = Pattern.compile("(.*):(.*)");
        Matcher clientAuthMatcher = clientAuthPattern.matcher(clientAuthorization);
        if (clientAuthMatcher.find() && clientAuthMatcher.groupCount() == 2) {
          String clientId = clientAuthMatcher.group(1);
          String clientSecret = clientAuthMatcher.group(2);
          logger.log(Level.FINE,
              ("TokenEndpoint::AuthorizationHeader:client_id " + clientId + " | client_secret " + clientSecret));
          Client client = Client.getClient(clientId);
          if (client != null && client.validateSecret(clientSecret)) {
            logger.log(Level.INFO, ("TokenEndpoint::clientIsAuthorized:client_id " + clientId));
            return clientId;
          }
        }
      }
    }
    logger.warning("TokenEndpoint::clientIsAuthorized: false");
    return null;
  }

  /**
   * Verify the authorization code provided in the POST request's claim to /token
   * path
   *
   * @param code        - the authorization code provided in the request
   * @param baseUrl     - this server base URL
   * @param redirectURI - the requestor/client redirect URI provided in the POST
   *                    request
   * @param clientId    - the client ID retrieved from the request's Authorization
   *                    Header
   * @return providerId if the authorization code is valid, otherwise null
   */
  public static String authCodeIsValid(String code, String baseUrl, String redirectURI, String clientId) {
    String providerId = null;
    try {
      Algorithm algorithm = Algorithm.RSA256(AuthorizationController.getPublicKey(), null);
      JWTVerifier verifier = JWT.require(algorithm).withIssuer(baseUrl).withAudience(baseUrl)
          .withClaim(REDIRECT_URI_KEY, redirectURI).withClaim(CLIENT_ID_KEY, clientId).build();
      DecodedJWT jwt = verifier.verify(code);
      String username = jwt.getClaim("username").asString();
      User user = User.getUser(username);
      providerId = user != null ? user.getProviderId() : null;
    } catch (SignatureVerificationException | InvalidClaimException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Signature invalid or claim value invalid",
          e);
    } catch (AlgorithmMismatchException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Algorithm mismatch", e);
    } catch (TokenExpiredException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Token expired", e);
    } catch (JWTVerificationException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Please obtain a new code", e);
    }
    return providerId;
  }

  /**
   * Verify the refresh token
   *
   * @param refreshToken - the refresh token
   * @param baseUrl      - this server base url
   * @param clientId     - the requestor/client client id provided in the post
   *                     request Authorization header
   * @return providerId if the refresh token is verified, otherwise null
   */
  public static String refreshTokenIsValid(String refreshToken, String baseUrl, String clientId) {
    String providerId = null;
    try {
      Algorithm algorithm = Algorithm.RSA256(AuthorizationController.getPublicKey(), null);
      JWTVerifier verifier = JWT.require(algorithm).withIssuer(baseUrl).withAudience(baseUrl)
          .withClaim(CLIENT_ID_KEY, clientId).build();
      DecodedJWT jwt = verifier.verify(refreshToken);
      String jwtId = jwt.getId();
      providerId = jwt.getClaim("provider_id").asString();
      if (!jwtId.equals(AuthorizationController.getDB().readRefreshToken(providerId))) {
        logger.warning("TokenEndpoint::Refresh token is invalid. Please reauthorize.");
        providerId = null;
      }
    } catch (JWTVerificationException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Refresh token is invalid. Please reauthorize.", e);
    }
    return providerId;
  }

  /**
   * Generate an access (valid for an hour) or refresh token for the user with
   * correct claims.
   *
   * @param baseUrl    - this server base url
   * @param clientId   - the client ID of the requestor/client
   * @param providerId - the user's patient ID
   * @param jwtId      - the unique ID for this token
   * @param exp        - the token expiration time
   *
   * @return access or refresh token for granted user, otherwise null
   */
  public static String generateToken(String baseUrl, String clientId, String providerId, String jwtId,
      Instant exp) {
    try {
      Algorithm algorithm = Algorithm.RSA256(AuthorizationController.getPublicKey(),
          AuthorizationController.getPrivateKey());
      return JWT.create().withKeyId(AuthorizationController.getKeyId()).withIssuer(baseUrl).withAudience(baseUrl)
          .withIssuedAt(new Date()).withExpiresAt(Date.from(exp)).withClaim(CLIENT_ID_KEY, clientId)
          .withClaim("provider_id", providerId)
          .withJWTId(jwtId).sign(algorithm);
    } catch (JWTCreationException e) {
      logger.log(Level.SEVERE,
          "TokenEndpoint::generateToken:Unable to generate token. Invalid signing/claims configuration", e);
    }
    return null;
  }

}
