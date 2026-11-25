# syntax=docker/dockerfile:1
# Multi-stage JVM image (no native build).
#   docker build -t trading-desk-gateway .
# Tests are not run here (they need Docker for Dev Services); CI runs them before building.

# --- Build --------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# IBKR TWS API is not on Maven Central: install lib/TwsApi.jar (with lib/pom.xml, so its
# protobuf-java dependency comes along) as com.interactivebrokers:tws-api.
COPY lib/TwsApi.jar lib/pom.xml lib/
RUN cd lib && mvn -B -q org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
        -Dfile=TwsApi.jar -DpomFile=pom.xml

# Dependencies in their own layer, so a source-only change does not download them again.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B -DskipTests package

# --- Runtime ------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21

ENV LANGUAGE='en_US:en' \
    TZ=UTC \
    JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager" \
    JAVA_APP_JAR="/deployments/quarkus-run.jar"

COPY --from=build --chown=185 /src/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /src/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /src/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /src/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 185
ENTRYPOINT ["/opt/jboss/container/java/run/run-java.sh"]
