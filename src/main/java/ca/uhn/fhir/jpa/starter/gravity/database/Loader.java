package ca.uhn.fhir.jpa.starter.gravity.database;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * The Loader class loads FHIR resource JSONs from file into the server upon startup.
 * This is intended to be equivalent to HTTP PUT requests where the ID from the files is preserved.
 */
@Component
public class Loader {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Loader.class);

  @Autowired
  private ApplicationContext context;

  @EventListener(ApplicationReadyEvent.class)
  public void loadResources() throws Exception {
    Map<String, IFhirResourceDao> resourceDAOs = context.getBeansOfType(IFhirResourceDao.class);
    IParser parser = FhirContext.forR4().newJsonParser();

    URI resourcesURI = Loader.class.getClassLoader().getResource("fhir_resources").toURI();
    Path fhirResources = Paths.get(resourcesURI);

    Files.walk(fhirResources, Integer.MAX_VALUE)
        .filter(Files::isReadable)
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".json"))
        .forEach(p -> {
          try {
            File file = p.toFile();
            LOG.info("Loading " + file.getName());
            String fileContent = FileUtils.readFileToString(file, Charset.defaultCharset());
            Resource resource = (Resource) parser.parseResource(fileContent);

            String resourceType = resource.getResourceType().toString();

            // IMPORTANT: the HAPI parser appends version numbers to this ID when parsing from file,
            // but not when parsing from the body of an HTTP request, even when the content of both
            // is exactly the same.
            // That version number causes pain here, so remove it.
            resource.setId(resourceType + "/" + resource.getIdPart());

            // TODO: This feels brittle. May not be the right way to get a DAO.
            // All FHIR resource DAOs appear to be named "my{resourceType}DaoR4"
            IFhirResourceDao<Resource> resourceDAO = resourceDAOs.get("my" + resourceType + "DaoR4");          
            resourceDAO.update(resource, new SystemRequestDetails());
          } catch (Exception e) {
            LOG.error("Unable to load " + p.toString(), e);
          }
        });

    LOG.info("Done loading files");
  }
}
