# Gravity SDOH EHR Reference Implementation Server

Reference Implementation EHR HAPI FHIR based server for the [Gravity SDOHCC
Implementation
Guide](http://hl7.org/fhir/us/sdoh-clinicalcare/CapabilityStatement-SDOHCC-CoordinationPlatform.html)

## Prerequisites

- Java JDK 17 +
- Maven 3.8 +

## Installation

Clone this repository, then cd into the project directory:

```bash
git clone https://github.com/Gravity-SDOHCC/gravity-sdoh-ehr-server.git

cd gravity-sdoh-ehr-server
```

## Running locally

Before running the server locally, ensure that the server address is set to localhost in the configuration (`application.yaml`).

```bash
server_address: http://localhost:8080/fhir
```

Adjust the port number depending on the port you run this server on.
>> Forproduction, the server address should be set the the hosted URL <https://gravity-ehr-server.herokuapp.com/fhir>.

The easiest way to run this server entirely depends on your environment requirements. At least, the following 4 ways are supported:

### Using jetty

```bash
mvn jetty:run
```

The server will then be accessible at <http://localhost:8080/fhir>.

If you need to run this server on a different port (using Maven), you can change the port in the run command as follows:

```bash
mvn -Djetty.port=8888 jetty:run
```

Server will then be accessible at <http://localhost:8888/> and eg. <http://localhost:8888/fhir/metadata>. Remember to adjust you overlay configuration in the application.yaml to eg.

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8888/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

### Using Spring Boot with :run

```bash
mvn clean spring-boot:run -Pboot
```

Server will then be accessible at <http://localhost:8080/> and eg. <http://localhost:8080/fhir/metadata>. Remember to adjust you overlay configuration in the application.yaml to eg.

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

### Using Spring Boot

```bash
mvn clean package spring-boot:repackage -Pboot && java -jar target/ROOT.war
```

Server will then be accessible at <http://localhost:8080/> and eg. <http://localhost:8080/fhir/metadata>. Remember to adjust you overlay configuration in the application.yaml to eg.

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

### Using the Dockerfile and multistage build

```bash
./build-docker-image.sh && docker run -p 8080:8080 hapi-fhir/gravity-ehr:latest
```

Server will then be accessible at http://localhost:8080/ and eg. http://localhost:8080/fhir/metadata. Remember to adjust you overlay configuration in the application.yaml to eg.

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

## Deployment

This server is automatically deployed to heroku with any push to the `master` branch.

## Using this Server

This server currently supports unauthenticated access. You can obtain the
supported endpoints and search parameters for each endpoint by querying the
metadata `[base_url]/metadata` (e.g. <http://localhost:8080/fhir/metadata>).

The server automatically loads all resources located in the
`src/main/resources/fhir_resources` folder when it starts. There is also a ruby
script `upload.rb` which can be run to load the server all of the resources from
that folder.

See [the usage
documentation](https://github.com/Gravity-SDOHCC/sdoh_referral_source_client/blob/master/docs/usage.md)
for instructions on using the whole reference implementations.

## Configurations

The list of supported endpoints is managed in `src/main/resources/application.yaml`.

To ensure that all the required search parametes are supported, make sure to load the SearchParameter instances from the IG to this server.

### Task Referral Management: [Direct Workflow](https://build.fhir.org/ig/HL7/fhir-sdoh-clinicalcare/referral_workflow.html#direct-referral)

There is an interceptor `PostTaskInterceptor` (`src/main/java/ca/uhn/fhir/jpa/starter/gravity/interceptors/PostTaskInterceptor.java`) that is used to manage Task referral requests between this server (referral source server) and a task referral recipient server (Coordination Platform server or CBO server). This interceptor functions are as follows:

- on task creation, the interceptor retrieves the receiver server URL from the `Task.owner` Organization resource, and post the task to the receiver server.

>> For Direct referral (`EHR - CP` or `EHR - CBO`) where both systems have an enabled FHIR server, the task owner organization is expected to have a `contact` field with telecom url representing the organization base URL. Without this, the interceptor will not work as expected for this use case (The created task will not be sent to the recipient server).

Example Organization instance:
```json
{
  "resourceType": "Organization",
  "id": "0bb0123a-7862-4eea-bfa1-3b3d7ddcb679",
  "meta": {
    "versionId": "1",
    "lastUpdated": "2023-05-08T22:45:10.294-04:00",
    "source": "#nWZeqrEJwhlCSFlG"
  },
  "active": true,
  "name": "ABC Local CP",
  "address": [ {
    "line": [ "123 King Street" ],
    "city": "New York",
    "state": "NY",
    "postalCode": "30223"
  } ],
  "contact": [ {
    "telecom": [ {
      "system": "phone",
      "value": "555-555-5555"
    }, {
      "system": "email",
      "value": "example@cp.org"
    }, {
      "system": "url",
      "value": "http://localhost:8082/fhir"
    } ]
  } ]
}
```

- When the task is successfully sent to the recipient server, the interceptor update the task status on this server to `received`. The task id from the recipient server is also saved and it is used to poll the recipient server for task status updates.

>> If the `POST` request fails, the server will reattempt to send the task until the request succeeds.

- The interceptor has a scheduler that periodically (every minute) polls the recipient server for task status updates on the active tasks. The pooling on a task stopped when the task is cancelled, rejected, or completed.

- When the task is completed, the interceptor also query the associated output (procedure) from the recipient server.

### Task Referral Management: [Direct Light Workflow](https://build.fhir.org/ig/HL7/fhir-sdoh-clinicalcare/referral_workflow.html#direct-referral-light)

In this use case, the task recipient has a FHIR-enable application but not a FHIR API. As a result, the referring provider (this server in this case) can’t push information to the recipient, but rather the task recipient app needs to connect to this server and pull information. At the conclusion of the referral, the recipient POSTS needed information (e.g., Procedures) to this FHIR server and updates the status of the task.

## Contributions

Pull requests are welcome. Any questions, suggestions, or issues should be submitted via the [GitHub issue tracker](https://github.com/Gravity-SDOHCC/gravity-sdoh-ehr-server/issues)

## License
Copyright 2023 The MITRE Corporation

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy of the
License at
```
http://www.apache.org/licenses/LICENSE-2.0
```
Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

## Trademark Notice

HL7, FHIR and the FHIR [FLAME DESIGN] are the registered trademarks of Health
Level Seven International and their use does not constitute endorsement by HL7.


## Questions and Contributions
Questions about the project can be asked in the [Gravity SDOH stream on the FHIR Zulip Chat](https://chat.fhir.org/#narrow/stream/233957-Gravity-sdoh-cc).

This project welcomes Pull Requests. Any issues identified with the RI should be submitted via the [GitHub issue tracker](https://github.com/Gravity-SDOHCC/gravity-sdoh-ehr-server/issues).

As of October 1, 2023, The Lantana Consulting Group is responsible for the management and maintenance of this Reference Implementation.
In addition to posting on FHIR Zulip Chat channel mentioned above you can contact [Corey Spears](mailto:corey.spears@lantanagroup.com) for questions or requests.
