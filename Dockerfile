FROM eclipse-temurin:22-jdk-jammy@sha256:d8e6ba486df17bf758888d2b1b608133d1eedca8daf69d3fc6bf78d8be81e07e AS builder

WORKDIR /workspace

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./

RUN chmod +x gradlew \
    && ./gradlew dependencies --configuration runtimeClasspath --no-daemon

COPY src/main src/main

RUN ./gradlew clean bootJar --no-daemon

FROM eclipse-temurin:22-jre-jammy@sha256:dbcae8b5dd4d63f81739a538ec2c09797735f04a21d814f9071b62f018326043

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
