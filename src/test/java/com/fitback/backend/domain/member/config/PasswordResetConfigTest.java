package com.fitback.backend.domain.member.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PasswordResetConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PasswordResetConfig.class);

    @Test
    void bindsPasswordResetProperties() {
        contextRunner
                .withPropertyValues(
                        "app.password-reset.frontend-url=http://localhost:3000/reset-password",
                        "app.password-reset.sender-email=test@fitback.com",
                        "app.password-reset.token-ttl=5m",
                        "app.password-reset.request-cooldown=1m"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    PasswordResetProperties properties =
                            context.getBean(PasswordResetProperties.class);
                    assertThat(properties.frontendUrl())
                            .isEqualTo("http://localhost:3000/reset-password");
                    assertThat(properties.senderEmail()).isEqualTo("test@fitback.com");
                    assertThat(properties.tokenTtl()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.requestCooldown()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void rejectsBlankFrontendUrl() {
        contextRunner
                .withPropertyValues(
                        "app.password-reset.frontend-url= ",
                        "app.password-reset.sender-email=test@fitback.com",
                        "app.password-reset.token-ttl=5m",
                        "app.password-reset.request-cooldown=1m"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "app.password-reset.frontend-url must not be blank"
                            );
                });
    }

    @Test
    void rejectsBlankSenderEmail() {
        contextRunner
                .withPropertyValues(
                        "app.password-reset.frontend-url=http://localhost:3000/reset-password",
                        "app.password-reset.sender-email= ",
                        "app.password-reset.token-ttl=5m",
                        "app.password-reset.request-cooldown=1m"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "app.password-reset.sender-email must not be blank"
                            );
                });
    }

    @Test
    void rejectsNonPositiveTokenTtl() {
        contextRunner
                .withPropertyValues(
                        "app.password-reset.frontend-url=http://localhost:3000/reset-password",
                        "app.password-reset.sender-email=test@fitback.com",
                        "app.password-reset.token-ttl=0s",
                        "app.password-reset.request-cooldown=1m"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "app.password-reset.token-ttl must be positive"
                            );
                });
    }

    @Test
    void rejectsNonPositiveRequestCooldown() {
        contextRunner
                .withPropertyValues(
                        "app.password-reset.frontend-url=http://localhost:3000/reset-password",
                        "app.password-reset.sender-email=test@fitback.com",
                        "app.password-reset.token-ttl=5m",
                        "app.password-reset.request-cooldown=0s"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "app.password-reset.request-cooldown must be positive"
                            );
                });
    }

    @Test
    void rejectsRequestCooldownNotShorterThanTokenTtl() {
        contextRunner
                .withPropertyValues(
                        "app.password-reset.frontend-url=http://localhost:3000/reset-password",
                        "app.password-reset.sender-email=test@fitback.com",
                        "app.password-reset.token-ttl=5m",
                        "app.password-reset.request-cooldown=5m"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "app.password-reset.request-cooldown "
                                            + "must be shorter than token-ttl"
                            );
                });
    }
}
