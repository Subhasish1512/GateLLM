package com.gatellm;

import com.gatellm.config.GateLlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GateLlmProperties.class)
public class GateLlmApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateLlmApplication.class, args);
    }
}
