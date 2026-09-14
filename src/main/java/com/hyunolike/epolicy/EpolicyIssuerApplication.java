package com.hyunolike.epolicy;

import com.hyunolike.epolicy.infrastructure.config.EpolicyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EpolicyProperties.class)
public class EpolicyIssuerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EpolicyIssuerApplication.class, args);
    }
}
