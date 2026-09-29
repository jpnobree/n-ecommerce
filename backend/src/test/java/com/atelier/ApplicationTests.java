package com.atelier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Sobe a aplicação inteira contra um PostgreSQL real: migrations, segurança e endpoints públicos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplicationTests.Containers.class)
class ApplicationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String requestId) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (requestId != null) req.header("X-Request-Id", requestId);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void migrationsCreateExtensions() {
        var extensions = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);
        assertThat(extensions).contains("citext", "pg_trgm", "unaccent");
    }

    @Test
    void publicEndpointsRespond() throws Exception {
        var status = get("/api/status", null);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("\"status\":\"UP\"");
        assertThat(status.headers().firstValue("X-Request-Id")).isPresent();

        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void requestIdIsEchoedOnlyWhenSafe() throws Exception {
        assertThat(get("/api/status", "abc12345-ok").headers().firstValue("X-Request-Id")).hasValue("abc12345-ok");
        assertThat(get("/api/status", "bad id!").headers().firstValue("X-Request-Id")).isNotEqualTo("bad id!");
    }

    @Test
    void everythingElseRequiresAuthentication() throws Exception {
        assertThat(get("/api/orders", null).statusCode()).isEqualTo(401);
    }
}
