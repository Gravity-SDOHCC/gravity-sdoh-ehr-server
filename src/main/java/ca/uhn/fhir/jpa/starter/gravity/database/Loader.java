package ca.uhn.fhir.jpa.starter.gravity.database;

import java.io.File;
import java.nio.charset.Charset;
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

@Component
public class Loader {

  @Autowired
  private ApplicationContext context;

  @EventListener(ApplicationReadyEvent.class)
  public void loadResources() {
    // TODO: will this work when packaged up? 
    // may need to move these under src/main/resources
    File dir = new File("./fhir_resources");

    Map<String, IFhirResourceDao> resourceDAOs = context.getBeansOfType(IFhirResourceDao.class);

    IParser parser = FhirContext.forR4().newJsonParser();

    File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
    if (files != null) {
      for (File file : files) {
        try {
          System.out.println("Loading " + file.getName());
          String fileContent = FileUtils.readFileToString(file, Charset.defaultCharset());
          Resource resource = (Resource) parser.parseResource(fileContent);
          
          String resourceType = resource.getResourceType().toString();
          
          // IMPORTANT: the HAPI parser appends version numbers to the ID, but
          // if you just read the JSON directly into the body of an HTTP PUT you probably don't.
          // The version number causes pain here, so remove it.
          resource.setId(resourceType + "/" + resource.getIdPart());
         
          // TODO: This feels brittle. May not be the right way to get a DAO.
          // All FHIR resource DAOs appear to be named "my{resourceType}DaoR4"
          IFhirResourceDao<Resource> resourceDAO = resourceDAOs.get("my" + resourceType + "DaoR4");          
          resourceDAO.update(resource, new SystemRequestDetails());
        } catch (Exception e) {
          // TODO: logger
          System.err.println("Unable to load " + file.getName());
          e.printStackTrace();
        }
      }
    }

    System.out.println("Done loading files");
  }
}
