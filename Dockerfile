FROM eclipse-temurin:21-jdk-jammy@sha256:9d8dcf999b0bce2453e913823595a5ff2a4e8e9e5d5241b45280d0ff069818ec AS builder

WORKDIR /workspace

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./

RUN chmod +x gradlew \
    && ./gradlew dependencies --configuration runtimeClasspath --no-daemon

COPY src/main src/main

RUN ./gradlew clean bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

ARG VCS_REF=unknown

LABEL org.opencontainers.image.source="https://github.com/FIT-BACK/backend" \
      org.opencontainers.image.revision="${VCS_REF}"

RUN command -v curl > /dev/null \
    && groupadd --gid 10001 fitback \
    && useradd --uid 10001 --gid fitback --no-create-home --shell /usr/sbin/nologin fitback

WORKDIR /app

COPY --from=builder --chown=fitback:fitback /workspace/build/libs/*.jar app.jar

USER fitback

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
