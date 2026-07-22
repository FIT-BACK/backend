package com.fitback.backend.global.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfig {

    @Bean
    public S3Presigner s3Presigner(ImageStorageProperties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.awsRegion()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
