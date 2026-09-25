# Budget Owl API.
#
# Multi-stage: the build tooling never reaches the published image. Someone running this on a home
# server should not be shipped a JDK, Maven and the whole dependency cache.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencies first, so a source change does not re-download the internet.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

# Never root. A container escape should not start from uid 0.
RUN addgroup -S owl && adduser -S -G owl owl
WORKDIR /app
COPY --from=build --chown=owl:owl /build/target/*.jar app.jar
USER owl

EXPOSE 8080

# Containers get a fraction of the host; let the JVM see the cgroup limit rather than the machine.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
