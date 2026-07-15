package com.fitback.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class FitbackBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FitbackBackendApplication.class, args);
    }

}
