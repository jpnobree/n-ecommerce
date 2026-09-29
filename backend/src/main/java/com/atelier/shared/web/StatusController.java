package com.atelier.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint público mínimo para o "hello" ponta a ponta da Fase 1 (frontend → API). */
@RestController
public class StatusController {

    record Status(String status, String version) {}

    private final String version;

    StatusController(@Value("${app.version:dev}") String version) {
        this.version = version;
    }

    @GetMapping("/api/status")
    Status status() {
        return new Status("UP", version);
    }
}
