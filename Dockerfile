FROM eclipse-temurin:25-jdk-jammy@sha256:0348e7b24ad4479cf35927b750671bb4b78465c303003b08536f6f2fa6f180cd AS builder

WORKDIR /workspace

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./

RUN chmod +x gradlew \
    && ./gradlew dependencies --configuration runtimeClasspath --no-daemon

COPY src/main src/main

RUN ./gradlew clean bootJar --no-daemon

FROM eclipse-temurin:25-jre-jammy@sha256:b8ba5fca9d88b6ecc3a46c8e75b744f84aca9a9d08587901b5ab480baf641ab5

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
