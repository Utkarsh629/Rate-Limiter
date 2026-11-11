# syntax=docker/dockerfile:1
# Multi-stage build: Maven wrapper build on a JDK, layered runtime on a JRE (Java 17).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/distributed-rate-limiter-0.1.0-SNAPSHOT.jar \
        extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:17-jre
# curl: used only by the container health check (readiness probe).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home app
WORKDIR /app
# Layers ordered from least to most frequently changing, so code changes rebuild only the last one.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER 10001
EXPOSE 8080
# Container-aware heap; overridable at run time. No secrets here: all come from the environment.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
