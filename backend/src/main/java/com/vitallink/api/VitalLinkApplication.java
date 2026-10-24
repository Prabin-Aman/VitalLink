package com.vitallink.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** VitalLink API entry point. Run locally: {@code ./mvnw spring-boot:run}. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VitalLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(VitalLinkApplication.class, args);
    }
}
