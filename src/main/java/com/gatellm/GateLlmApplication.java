package com.gatellm;

import io.github.cdimascio.dotenv.Dotenv;
import com.gatellm.config.GateLlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GateLlmProperties.class)
public class GateLlmApplication {

    public static void main(String[] args) {
        // Load .env file before Spring context initialization
        // Use project root directory (where gradlew is)
        String projectRoot = System.getProperty("user.dir");
        Dotenv dotenv = Dotenv.configure()
                .directory(projectRoot)
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue())
        );

        SpringApplication.run(GateLlmApplication.class, args);
    }
}
