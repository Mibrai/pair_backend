package org.program.pair.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.media.StorageService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class StorageConfig {

    private final StorageService storageService;

    @Bean
    CommandLineRunner initStorage() {
        return args -> {
            try {
                storageService.init();
                log.info("Storage initialized successfully");
            } catch (Exception e) {
                log.error("Failed to initialize storage", e);
                throw new RuntimeException("Could not initialize storage", e);
            }
        };
    }
}
