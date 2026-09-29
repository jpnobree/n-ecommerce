package com.atelier;

import com.atelier.shared.web.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Base dos testes de integração: aplicação inteira + PostgreSQL real (um container, contexto compartilhado).
 * Mesma configuração em todas as subclasses para o Spring reaproveitar o contexto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.refresh-reuse-grace=2s",
        "app.auth.bootstrap-admin-email=admin@test.local",
        "app.auth.bootstrap-admin-password=admin-test-123456"
})
@Import(IntegrationTest.Containers.class)
public abstract class IntegrationTest {

    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String ADMIN_PASSWORD = "admin-test-123456";

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
    protected JsonMapper json;

    @Autowired
    RateLimitFilter rateLimit;

    @MockitoBean
    protected JavaMailSender mailSender;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetRateLimit() {
        rateLimit.reset();
    }

    /** Resposta HTTP simplificada. */
    public record Res(int status, String body, HttpResponse<String> raw, JsonMapper mapper) {
        public JsonNode json() {
            return mapper.readTree(body);
        }

        public String text(String field) {
            return json().path(field).asString();
        }

        /** Valor do cookie refresh_token definido pela resposta ("" quando foi limpo). */
        public String refreshCookie() {
            return raw.headers().allValues("Set-Cookie").stream()
                    .filter(c -> c.startsWith("refresh_token="))
                    .map(c -> c.substring("refresh_token=".length(), c.indexOf(';')))
                    .findFirst().orElse(null);
        }

        public String setCookieHeader() {
            return raw.headers().firstValue("Set-Cookie").orElse("");
        }
    }

    /** Requisição builder mínimo. */
    protected class Req {
        private final HttpRequest.Builder b;
        private String body = "";
        private String method;

        Req(String method, String path) {
            this.method = method;
            this.b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        }

        public Req body(Object value) {
            this.body = value instanceof String s ? s : json.writeValueAsString(value);
            return this;
        }

        public Req bearer(String token) {
            b.header("Authorization", "Bearer " + token);
            return this;
        }

        public Req refreshCookie(String value) {
            b.header("Cookie", "refresh_token=" + value);
            return this;
        }

        public Req ajax() {
            b.header("X-Requested-With", "XMLHttpRequest");
            return this;
        }

        public Res send() {
            try {
                var publisher = body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
                var res = http.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
                return new Res(res.statusCode(), res.body(), res, json);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    protected Req get(String path) { return new Req("GET", path); }
    protected Req post(String path) { return new Req("POST", path); }
    protected Req put(String path) { return new Req("PUT", path); }
    protected Req delete(String path) { return new Req("DELETE", path); }

    // ---- atalhos de domínio ----

    protected static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
    }

    protected Res register(String email, String password) {
        var res = post("/api/auth/register").body(java.util.Map.of(
                "name", "Cliente Teste", "email", email, "password", password,
                "acceptTerms", true, "marketingOptIn", false)).send();
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        return res;
    }

    protected Res login(String email, String password) {
        return post("/api/auth/login").body(java.util.Map.of("email", email, "password", password)).send();
    }

    /** Último e-mail com o assunto dado enviado ao destinatário; espera até 3 s (o envio é assíncrono). */
    protected SimpleMailMessage lastMailTo(String email, String subject) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            var found = mockingDetails(mailSender).getInvocations().stream()
                    .flatMap(inv -> java.util.Arrays.stream(inv.getArguments()))
                    .filter(SimpleMailMessage.class::isInstance)
                    .map(SimpleMailMessage.class::cast)
                    .filter(m -> m.getTo() != null && m.getTo()[0].equals(email) && subject.equals(m.getSubject()))
                    .reduce((a, b) -> b);
            if (found.isPresent()) return found.get();
            Thread.sleep(100);
        }
        throw new AssertionError("nenhum e-mail '" + subject + "' para " + email);
    }

    protected static String tokenFrom(SimpleMailMessage mail) {
        var m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(mail.getText());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
