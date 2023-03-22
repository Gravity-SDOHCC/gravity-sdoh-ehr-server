package ca.uhn.fhir.jpa.starter.gravity.interceptors;

import ca.uhn.fhir.jpa.starter.gravity.ServerLogger;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import ca.uhn.fhir.jpa.starter.gravity.controllers.AuthorizationController;
import ca.uhn.fhir.jpa.starter.AppProperties;

@SuppressWarnings("ConstantConditions")
public class SmartAuthInterceptor extends AuthorizationInterceptor {
  private static final Logger logger = ServerLogger.getLogger();
  AppProperties appProperties;

  @Override
  public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
    String authHeader = theRequestDetails.getHeader("Authorization");
    // Check if authorization header is present, return unauthorized rule if not
    if (authHeader == null) {
      logger.info("No Authorization header found");
      return unauthorizedRule();
    }

    // Check authorization header pattern Bearer <token> and extract token. Throw
    // exception if pattern does not match
    Pattern pattern = Pattern.compile("Bearer (.+)");
    Matcher matcher = pattern.matcher(authHeader);
    if (!matcher.matches() || matcher.groupCount() != 1) {
      throw new AuthenticationException("Authorization header does not match pattern Bearer <token>");
    }

    String token = matcher.group(1);
    logger.fine("SmartAuthInterceptor::Token token retrieved is " + token);
    String adminToken = appProperties.getAdmin_token();
    // Check if token is admin token and return admin rule if true
    if (adminToken != null && token.equals(adminToken)) {
      logger.fine("SmartAuthInterceptor::Token token is admin token");
      return adminRule();
    }
    try {
      IIdType providerId = verify(token, theRequestDetails.getFhirServerBase());
      if (providerId != null)
        return clientAuthRule();
    } catch (SignatureVerificationException e) {
      throw new AuthenticationException("Token signature verification failed", e.getCause());
    } catch (TokenExpiredException e) {
      throw new AuthenticationException("Token has expired", e.getCause());
    } catch (JWTVerificationException e) {
      throw new AuthenticationException("Token verification failed", e.getCause());
    } catch (Exception e) {
      throw new AuthenticationException(e.getMessage(), e.getCause());
    }
    return clientAuthRule();
  }

  private List<IAuthRule> unauthorizedRule() {
    return new RuleBuilder().allow().metadata()
        .andThen().denyAll("unauthenticated client").build();
  }

  private List<IAuthRule> adminRule() {
    return new RuleBuilder().allowAll().build();
  }

  private List<IAuthRule> clientAuthRule() {
    return new RuleBuilder().deny("unauthorize write CareTeam").write().resourcesOfType("CareTeam").withAnyId()
        .andThen().deny("unauthorize delete CareTeam").delete().resourcesOfType("CareTeam").withAnyId()
        .andThen().deny("unauthorize write Consent").write().resourcesOfType("Consent").withAnyId()
        .andThen().deny("unauthorize delete Consent").delete().resourcesOfType("Consent").withAnyId()
        .andThen().deny("unauthorize write Device").write().resourcesOfType("Device").withAnyId()
        .andThen().deny("unauthorize delete Device").delete().resourcesOfType("Device").withAnyId()
        .andThen().deny("unauthorize write Group").write().resourcesOfType("Group").withAnyId()
        .andThen().deny("unauthorize delete Group").delete().resourcesOfType("Group").withAnyId()
        .andThen().deny("unauthorize write HealthcareService").write().resourcesOfType("HealthcareService").withAnyId()
        .andThen().deny("unauthorize delete HealthcareService").delete().resourcesOfType("HealthcareService")
        .withAnyId()
        .andThen().deny("unauthorize write Location").write().resourcesOfType("Location").withAnyId()
        .andThen().deny("unauthorize delete Location").delete().resourcesOfType("Location").withAnyId()
        .andThen().deny("unauthorize write Patient").write().resourcesOfType("Patient").withAnyId()
        .andThen().deny("unauthorize delete Patient").delete().resourcesOfType("Patient").withAnyId()
        .andThen().deny("unauthorize write Practitioner").write().resourcesOfType("Practitioner").withAnyId()
        .andThen().deny("unauthorize delete Practitioner").delete().resourcesOfType("Practitioner").withAnyId()
        .andThen().deny("unauthorize write PractitionerRole").write().resourcesOfType("PractitionerRole").withAnyId()
        .andThen().deny("unauthorize delete PractitionerRole").delete().resourcesOfType("PractitionerRole").withAnyId()
        .andThen().deny("unauthorize write RelatedPerson").write().resourcesOfType("RelatedPerson").withAnyId()
        .andThen().deny("unauthorize delete RelatedPerson").delete().resourcesOfType("RelatedPerson").withAnyId()
        .andThen().deny("unauthorize write Questionnaire").write().resourcesOfType("Questionnaire").withAnyId()
        .andThen().deny("unauthorize delete Questionnaire").delete().resourcesOfType("Questionnaire").withAnyId()
        .andThen().allowAll().build();

  }

  /**
   * Helper method to verify and decode the access token
   *
   * @param token       - the access token
   * @param fhirBaseUrl - the base url of this FHIR server
   * @return the base interface Patient ID datatype if the jwt token is verified
   *         and contains a patient ID in it claim, otherwise null.
   * @throws SignatureVerificationException
   * @throws TokenExpiredException
   * @throws JWTVerificationException
   */
  private IIdType verify(String token, String fhirBaseUrl)
      throws SignatureVerificationException, TokenExpiredException, JWTVerificationException {
    Algorithm algorithm = Algorithm.RSA256(AuthorizationController.getPublicKey(), null);
    logger.fine("Verifying JWT token iss and aud is " + fhirBaseUrl);
    JWTVerifier verifier = JWT.require(algorithm).withIssuer(fhirBaseUrl).withAudience(fhirBaseUrl).build();
    DecodedJWT jwt = verifier.verify(token);
    String providerId = jwt.getClaim("provider_id").asString();
    if (providerId != null)
      return new IdType("Practitioner", providerId);

    return null;
  }

}
