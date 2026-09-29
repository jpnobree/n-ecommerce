package com.atelier.shared.web;

import com.atelier.shared.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint público mínimo para o "hello" ponta a ponta (frontend → API). */
@RestController
public class StatusController {

    record Status(String status, String version) {}

    private final AppProperties app;

    StatusController(AppProperties app) {
        this.app = app;
    }

    @GetMapping("/api/status")
    Status status() {
        return new Status("UP", app.version());
    }
}
