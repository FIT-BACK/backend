package com.fitback.backend.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fitbackOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FIT-BACK Backend API")
                        .description("FIT-BACK Backend API 문서")
                        .version("v1"))
                .servers(List.of(new Server().url("/").description("Default Server")));
    }

    @Bean
    public GroupedOpenApi v1Api() {
        return GroupedOpenApi.builder()
                .group("v1")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
