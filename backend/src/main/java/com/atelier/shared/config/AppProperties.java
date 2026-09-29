package com.atelier.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue("dev") String version,
        /* Base dos links enviados por e-mail. */
        @DefaultValue("http://localhost:4200") String frontendUrl,
        @DefaultValue("Atelier <nao-responda@atelier.local>") String mailFrom) {
}
