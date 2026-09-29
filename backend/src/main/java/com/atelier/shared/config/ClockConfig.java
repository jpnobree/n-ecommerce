package com.atelier.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class ClockConfig {

    /** Injetado em vez de Instant.now() para permitir testes com tempo controlado. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
