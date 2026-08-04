package com.fitback.backend.domain.member.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginAttemptProperties.class)
public class LoginAttemptConfig {
}
