package com.atelier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Infraestrutura da Fase 1: migrations, endpoints públicos, X-Request-Id, formato de erro. */
class ApplicationTests extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsCreateExtensions() {
        var extensions = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);
        assertThat(extensions).contains("citext", "pg_trgm", "unaccent");
    }

    @Test
    void publicEndpointsRespond() {
        var status = get("/api/status").send();
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.text("status")).isEqualTo("UP");
        assertThat(status.raw().headers().firstValue("X-Request-Id")).isPresent();

        assertThat(get("/actuator/health/readiness").send().status()).isEqualTo(200);
    }

    @Test
    void requestIdIsEchoedOnlyWhenSafe() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var base = java.net.URI.create("http://localhost:" + port + "/api/status");
        var ok = client.send(java.net.http.HttpRequest.newBuilder(base).header("X-Request-Id", "abc12345-ok").build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        var bad = client.send(java.net.http.HttpRequest.newBuilder(base).header("X-Request-Id", "bad id!").build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(ok.headers().firstValue("X-Request-Id")).hasValue("abc12345-ok");
        assertThat(bad.headers().firstValue("X-Request-Id")).isNotEqualTo("bad id!");
    }

    @Test
    void validationErrorsUseProblemDetails() {
        var res = post("/api/auth/register").body("{\"email\":\"nao-e-email\"}").send();
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.raw().headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("problem+json"));
        assertThat(res.text("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(res.json().path("errors").toString()).contains("email", "password", "name");
        assertThat(res.text("requestId")).isNotBlank();
    }
}
