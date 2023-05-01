FROM maven:3.8-openjdk-17-slim as build
COPY pom.xml /usr/src/app/pom.xml
COPY server.xml /usr/src/app/server.xml

COPY src /usr/src/app/src
RUN mvn -f /usr/src/app/pom.xml clean install -DskipTests -Djdk.lang.Process.launchMechanism=vfork

FROM jetty:9.4-jre17-alpine as jetty
COPY --from=build /usr/src/app/target/ROOT.war /var/lib/jetty/webapps/ROOT.war
COPY --from=build /usr/src/app/target /var/lib/jetty/target

COPY src /var/lib/jetty/src
USER root
RUN chown -R jetty:jetty /var/lib/jetty/target
USER jetty:jetty
EXPOSE 8080
