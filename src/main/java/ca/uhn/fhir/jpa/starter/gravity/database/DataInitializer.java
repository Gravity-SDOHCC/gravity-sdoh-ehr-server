package ca.uhn.fhir.jpa.starter.gravity.database;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.nio.charset.StandardCharsets;

@Component
public class DataInitializer {

	private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

	@Autowired
	private FhirContext fhirContext;

	@Autowired
	private DaoRegistry daoRegistry;

	@Autowired
	private AppProperties appProperties;

	@Autowired
	private ResourceLoader resourceLoader;

	@Autowired
	private JpaStorageSettings storageSettings;

	@EventListener(ApplicationReadyEvent.class)
	public void initializeData() {

		if (appProperties.getInitialData() == null
				|| appProperties.getInitialData().isEmpty()) {
			return;
		}

		logger.info("Initializing data");

		// Disable referential integrity checks so that resources can be loaded in any order
		storageSettings.setEnforceReferentialIntegrityOnWrite(false);

		for (String directoryPath : appProperties.getInitialData()) {
			logger.info("Loading resources from directory: " + directoryPath);

			Resource[] resources = null;

			try {
				resources = ResourcePatternUtils.getResourcePatternResolver(resourceLoader)
						.getResources("classpath:" + directoryPath + "/**/*.json");
			} catch (Exception e) {
				logger.error("Error loading resources from directory: " + directoryPath, e);
				continue;
			}

			for (Resource resource : resources) {
				try {
					String resourceText = new String(
							FileCopyUtils.copyToByteArray(resource.getInputStream()), StandardCharsets.UTF_8);

					IBaseResource fhirResource = fhirContext.newJsonParser().parseResource(resourceText);

					// IMPORTANT: the HAPI parser appends version numbers to this ID when parsing from file,
					// but not when parsing from the body of an HTTP request, even when the content of both
					// is exactly the same.
					// That version number causes pain here, so remove it.
					fhirResource.setId(fhirResource.getIdElement().getResourceType() + "/"
							+ fhirResource.getIdElement().getIdPart());

					IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(fhirResource);
					dao.update(fhirResource, new SystemRequestDetails());
					logger.info("Loaded resource: " + resource.getFilename());
				} catch (Exception e) {
					logger.error("Error loading resource: " + resource.getFilename(), e);
				}
			}
		}

		// Re-enable referential integrity checks if they were previously enabled
		storageSettings.setEnforceReferentialIntegrityOnWrite(
				appProperties.getEnforce_referential_integrity_on_write());
	}
}
