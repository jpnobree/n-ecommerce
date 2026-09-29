package com.atelier.identity;

import com.atelier.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cenários críticos de autenticação (PRD, seção 22.3). */
class AuthFlowTests extends IntegrationTest {

    @Test
    void registerStartsSessionWithHardenedCookie() {
        String email = uniqueEmail();
        var res = register(email, "senha-forte-1");

        assertThat(res.text("accessToken")).isNotBlank();
        assertThat(res.json().path("user").path("roles").toString()).contains("CUSTOMER");
        assertThat(res.json().path("user").path("emailVerified").asBoolean()).isFalse();
        assertThat(res.body()).doesNotContain(res.refreshCookie()); // refresh só no cookie
        assertThat(res.setCookieHeader()).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth", "Secure");
        // Indicador sem segredo para o frontend saber que vale tentar o refresh.
        assertThat(res.raw().headers().allValues("Set-Cookie"))
                .anySatisfy(c -> assertThat(c).startsWith("has_session=1").contains("Path=/").doesNotContain("HttpOnly"));

        var me = get("/api/me").bearer(res.text("accessToken")).send();
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("email")).isEqualTo(email);
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        String email = uniqueEmail();
        register(email, "senha-forte-1");
        var dup = post("/api/auth/register").body(Map.of("name", "X", "email", email.toUpperCase(),
                "password", "senha-forte-1", "acceptTerms", true)).send();
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.text("code")).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void termsMustBeAccepted() {
        var res = post("/api/auth/register").body(Map.of("name", "X", "email", uniqueEmail(),
                "password", "senha-forte-1", "acceptTerms", false)).send();
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body()).contains("acceptTerms");
    }

    @Test
    void loginErrorsAreGenericAndAccountLocksAfterFiveFailures() {
        String email = uniqueEmail();
        register(email, "senha-forte-1");

        var unknown = login(uniqueEmail(), "qualquer-coisa");
        var wrong = login(email, "senha-errada");
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.text("detail")).isEqualTo(wrong.text("detail"));

        for (int i = 0; i < 4; i++) login(email, "senha-errada");
        var locked = login(email, "senha-forte-1");
        assertThat(locked.status()).isEqualTo(403);
        assertThat(locked.text("code")).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() {
        String email = uniqueEmail();
        register(email, "senha-forte-1");
        assertThat(login(email.toUpperCase(), "senha-forte-1").status()).isEqualTo(200);
    }

    @Test
    void refreshRotatesAndDetectsReuse() throws Exception {
        var session = register(uniqueEmail(), "senha-forte-1");
        String first = session.refreshCookie();

        var rotated = post("/api/auth/refresh").ajax().refreshCookie(first).send();
        assertThat(rotated.status()).isEqualTo(200);
        String second = rotated.refreshCookie();
        assertThat(second).isNotEqualTo(first);

        // Dentro da janela de tolerância (várias abas): aceito.
        assertThat(post("/api/auth/refresh").ajax().refreshCookie(first).send().status()).isEqualTo(200);

        // Depois da janela: reuso = roubo. Família inteira revogada, cookie limpo.
        Thread.sleep(2100);
        var reused = post("/api/auth/refresh").ajax().refreshCookie(first).send();
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.text("code")).isEqualTo("REFRESH_REUSED");
        assertThat(reused.refreshCookie()).isEmpty();
        assertThat(post("/api/auth/refresh").ajax().refreshCookie(second).send().status()).isEqualTo(401);
    }

    @Test
    void cookieEndpointsRequireAjaxHeader() {
        var session = register(uniqueEmail(), "senha-forte-1");
        var res = post("/api/auth/refresh").refreshCookie(session.refreshCookie()).send();
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.text("code")).isEqualTo("CSRF_CHECK_FAILED");
    }

    @Test
    void logoutRevokesRefreshToken() {
        var session = register(uniqueEmail(), "senha-forte-1");
        var out = post("/api/auth/logout").ajax().refreshCookie(session.refreshCookie()).send();
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.refreshCookie()).isEmpty();
        assertThat(post("/api/auth/refresh").ajax().refreshCookie(session.refreshCookie()).send().status()).isEqualTo(401);
    }

    @Test
    void emailVerificationTokenIsSingleUse() throws Exception {
        String email = uniqueEmail();
        var session = register(email, "senha-forte-1");
        String token = tokenFrom(lastMailTo(email, "Confirme seu e-mail"));

        assertThat(post("/api/auth/verify-email").body(Map.of("token", token)).send().status()).isEqualTo(204);
        assertThat(get("/api/me").bearer(session.text("accessToken")).send().json().path("emailVerified").asBoolean()).isTrue();

        var again = post("/api/auth/verify-email").body(Map.of("token", token)).send();
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.text("code")).isEqualTo("TOKEN_INVALID_OR_EXPIRED");
    }

    @Test
    void passwordResetRevokesEverySession() throws Exception {
        String email = uniqueEmail();
        var session = register(email, "senha-forte-1");

        assertThat(post("/api/auth/forgot-password").body(Map.of("email", uniqueEmail())).send().status()).isEqualTo(202);
        assertThat(post("/api/auth/forgot-password").body(Map.of("email", email)).send().status()).isEqualTo(202);
        String token = tokenFrom(lastMailTo(email, "Redefinição de senha"));

        var reset = post("/api/auth/reset-password").body(Map.of("token", token, "newPassword", "nova-senha-2")).send();
        assertThat(reset.status()).isEqualTo(204);

        assertThat(login(email, "senha-forte-1").status()).isEqualTo(401);
        assertThat(login(email, "nova-senha-2").status()).isEqualTo(200);
        assertThat(get("/api/me").bearer(session.text("accessToken")).send().status()).isEqualTo(401);
        assertThat(post("/api/auth/refresh").ajax().refreshCookie(session.refreshCookie()).send().status()).isEqualTo(401);
        assertThat(post("/api/auth/reset-password").body(Map.of("token", token, "newPassword", "outra-senha-3")).send().status())
                .isEqualTo(400);
    }

    @Test
    void changePasswordRequiresCurrentAndReplacesSession() {
        String email = uniqueEmail();
        var session = register(email, "senha-forte-1");
        String access = session.text("accessToken");

        var wrong = put("/api/me/password").bearer(access)
                .body(Map.of("currentPassword", "errada-123", "newPassword", "nova-senha-2")).send();
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.text("code")).isEqualTo("WRONG_PASSWORD");

        var changed = put("/api/me/password").bearer(access)
                .body(Map.of("currentPassword", "senha-forte-1", "newPassword", "nova-senha-2")).send();
        assertThat(changed.status()).isEqualTo(200);
        assertThat(get("/api/me").bearer(access).send().status()).isEqualTo(401);
        assertThat(get("/api/me").bearer(changed.text("accessToken")).send().status()).isEqualTo(200);
        assertThat(post("/api/auth/refresh").ajax().refreshCookie(changed.refreshCookie()).send().status()).isEqualTo(200);
    }

    @Test
    void profileValidatesCpf() {
        var session = register(uniqueEmail(), "senha-forte-1");
        String access = session.text("accessToken");

        var invalid = put("/api/me").bearer(access).body(Map.of("name", "Ana", "cpf", "12345678900")).send();
        assertThat(invalid.status()).isEqualTo(400);

        var ok = put("/api/me").bearer(access).body(Map.of("name", "Ana Souza", "cpf", "52998224725", "phone", "11987654321")).send();
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.text("cpf")).isEqualTo("52998224725");

        var other = register(uniqueEmail(), "senha-forte-1");
        var taken = put("/api/me").bearer(other.text("accessToken")).body(Map.of("name", "B", "cpf", "52998224725")).send();
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.text("code")).isEqualTo("CPF_IN_USE");
    }

    @Test
    void loginIsRateLimitedPerIp() {
        for (int i = 0; i < 10; i++) login(uniqueEmail(), "x");
        var limited = login(uniqueEmail(), "x");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.text("code")).isEqualTo("RATE_LIMITED");
        assertThat(limited.raw().headers().firstValue("Retry-After")).isPresent();
    }
}
