package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.provider.JpaCapabilityStatementProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.context.support.IValidationSupport;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementSoftwareComponent;

import javax.servlet.http.HttpServletRequest;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import javax.annotation.Nonnull;

public class SdohCapabilityStatementProvider extends JpaCapabilityStatementProvider {
  SdohCapabilityStatementProvider(@Nonnull RestfulServer theRestfulServer, @Nonnull IFhirSystemDao<?, ?> theSystemDao,
      @Nonnull DaoConfig theDaoConfig, @Nonnull ISearchParamRegistry theSearchParamRegistry,
      IValidationSupport theValidationSupport) {
    super(theRestfulServer, theSystemDao, theDaoConfig, theSearchParamRegistry, theValidationSupport);
  }

  @Override
  public CapabilityStatement getServerConformance(HttpServletRequest theRequest, RequestDetails theRequestDetails) {
    CapabilityStatement metadata = (CapabilityStatement) super.getServerConformance(theRequest, theRequestDetails);
    metadata.addInstantiates("http://hl7.org/fhir/us/sdoh-clinicalcare/CapabilityStatement/SDOHCC-ReferralSource");
    metadata.setName("Gravity SDOH EHR FHIR Server RI");
    metadata.setDescription(
        "A Reference Implementation for Gravity SDOH Clinical Care Referral Source HAPI FHIR R4 Server");
    metadata.setStatus(PublicationStatus.ACTIVE);
    metadata.setPublisher("HL7 International Patient Care WG");
    metadata.addImplementationGuide(
        "http://hl7.org/fhir/us/sdoh-clinicalcare/ImplementationGuide/hl7.fhir.us.sdoh-clinicalcare");

    CapabilityStatementSoftwareComponent software = new CapabilityStatementSoftwareComponent();
    software.setName("Gravity SDOHCC EHR HAPI FHIR based Server");
    software.setVersion("2.0.0");
    metadata.setSoftware(software);

    return metadata;
  }
}
