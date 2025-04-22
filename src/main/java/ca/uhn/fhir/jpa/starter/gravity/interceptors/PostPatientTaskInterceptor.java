package ca.uhn.fhir.jpa.starter.gravity.interceptors;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.ServerLogger;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.JsonParser;
import org.hl7.fhir.r5.formats.IParser;
import org.hl7.fhir.utilities.FhirPublication;
import org.hl7.fhir.validation.ValidationEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Logger;

@Interceptor
public class PostPatientTaskInterceptor {

    private static final Logger logger = ServerLogger.getLogger();

    private static final FhirContext ctx = FhirContext.forR4();

    private static final String PATIENT_TASK_PROFILE = "http://hl7.org/fhir/us/sdoh-clinicalcare/StructureDefinition/SDOHCC-TaskForPatient";

    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
    public void handlePatientTaskUpdates(
            IBaseResource theOldResource, IBaseResource theResource, RequestDetails theRequestDetails, ResponseDetails theResponseDetails) throws IOException, URISyntaxException {
        {
            logger.info("Inside Patient Task Updates Interceptor");
            if (!(theResource instanceof Task)) {
                return;
            }
            Task createdTask = (Task) theResource;
            if (!isPatientTask(createdTask)) {
                return;
            }
            logger.info("Retrieving associated resources for task "+createdTask.getIdPart());
            String thisServerBaseUrl = theRequestDetails.getFhirServerBase();
            if (createdTask.hasStatus() && createdTask.getStatus() == Task.TaskStatus.COMPLETED) {
                if (createdTask.hasOutput() && createdTask.getOutputFirstRep().getType().getCodingFirstRep().getCode().equalsIgnoreCase("questionnaire-response")) {
                    String questionnaireurl = "";
                    String structureMapurl = "";
                    StructureMap structureMap = null;
                    String questionnaireResponseRef = null;
                    IGenericClient ehrClient = setupClient(thisServerBaseUrl);
                    // Getting questionnaire response reference from task output
                    if (createdTask.getOutputFirstRep().getValue() instanceof Reference) {
                        questionnaireResponseRef = ((Reference) createdTask.getOutputFirstRep().getValue()).getReference();
                    }
                    if (questionnaireResponseRef == null || questionnaireResponseRef.isEmpty()) {
                        logger.info("Couldn't find questionnaire-response with task. Nothing to process");
                        return;
                    }
                    // Getting questionnaire url from task input
                    if (createdTask.hasInput() && createdTask.getInputFirstRep().hasValue()) {
                        if (createdTask.getInputFirstRep().getValue() instanceof CanonicalType) {
                            CanonicalType canonicalType = (CanonicalType) createdTask.getInputFirstRep().getValue();
                            questionnaireurl = canonicalType.getValue();
                        }
                    }
                    if (questionnaireurl == null || questionnaireurl.isEmpty()) {
                        logger.info("Questionnaire is empty. Nothing to process");
                        return;
                    }
                    logger.info("Questionnaire url " + questionnaireurl);
                    Questionnaire questionnaire = ehrClient
                            .read()
                            .resource(Questionnaire.class)
                            .withUrl(questionnaireurl)
                            .execute();
                    if (questionnaire != null) {
                        Extension extension = questionnaire.getExtensionByUrl("http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-targetStructureMap");
                        if (extension != null && extension.getValue() instanceof CanonicalType) {
                            CanonicalType canonicalType = (CanonicalType) extension.getValue();
                            structureMapurl = canonicalType.getValue();
                        }
                        structureMap = ehrClient
                                .read()
                                .resource(StructureMap.class)
                                .withUrl(structureMapurl)
                                .execute();
                    }
                    if (structureMap == null || structureMap.isEmpty()) {
                        logger.info("StructureMap couldn't be found for sdc-questionnaire-targetStructureMap url " + structureMapurl);
                        return;
                    }
                    logger.info("StructureMap url " + structureMapurl);

                    // Start transform
                    QuestionnaireResponse questionnaireResponse = ehrClient
                            .read()
                            .resource(QuestionnaireResponse.class)
                            .withId(questionnaireResponseRef)
                            .execute();
                    logger.info("Starting transform with validationEngine");
                    ValidationEngine validationEngine = new ValidationEngine.ValidationEngineBuilder()
                            .withVersion("4.0.1")
                            .withNoTerminologyServer()
                            .fromSource("hl7.fhir.r4.core#4.0.1");
                    validationEngine.loadPackage("hl7.fhir.us.sdoh-clinicalcare", "2.0.0");
                    validationEngine.setVersion(FhirPublication.R4.toCode());
                    validationEngine.setAllowExampleUrls(true);
                    String source = thisServerBaseUrl+"/QuestionnaireResponse/"+questionnaireResponse.getIdPart();
                    logger.info(source);
                    Element result = validationEngine.transform(source, structureMapurl);
                    logger.info("Post transform. Processing results "+result.toString());
                    if( result.fhirType().equals("Bundle") ){
                        List<Element> elements = result.getChildrenByName("entry");
                        for(Element entry : elements){
                            Element element = entry.getNamedChild("resource");
                            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                            JsonParser jsonParser = new JsonParser(validationEngine.getContext());
                            jsonParser.compose(element, outputStream, IParser.OutputStyle.NORMAL, null);
                            String jsonString = outputStream.toString("UTF-8");
                            MethodOutcome outcome = ehrClient.create().resource(jsonString).execute();
                            logger.info("Transformed resource Id : "+outcome.getId().toUnqualifiedVersionless());
                        }
                    }
                    logger.info("Completed transform with validationEngine");
                } else {
                    logger.info("No questionnaire-response output found for task " + createdTask.getIdElement().getValue());
                }
            } else {
                logger.info("Task status is "+createdTask.getStatus()+", not completed. Nothing to process");
            }
        }
    }

    private IGenericClient setupClient(String serverBaseUrl) {
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setConnectTimeout(20 * 1000);
        return ctx.newRestfulGenericClient(serverBaseUrl);
    }

    private boolean isPatientTask(Task createdTask){
        if ( createdTask.hasMeta() && createdTask.getMeta().hasProfile() && createdTask.getMeta().getProfile().get(0).equals(PATIENT_TASK_PROFILE)) {
            return true;
        }
        return false;
    }
}
