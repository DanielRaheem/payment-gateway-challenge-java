FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 gateway \
    && useradd --uid 10001 --gid gateway --no-create-home gateway
WORKDIR /app
COPY --from=build --chown=gateway:gateway /workspace/build/libs/*.jar /app/gateway.jar
USER gateway:gateway
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
    CMD curl --fail --silent http://localhost:8090/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
