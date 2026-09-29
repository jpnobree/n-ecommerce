package com.atelier.identity;

import com.atelier.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Endereços (um principal, isolamento por usuário) e matriz inicial de autorização. */
class AddressAndAccessTests extends IntegrationTest {

    private static Map<String, Object> address(String label) {
        var a = new HashMap<String, Object>();
        a.put("label", label);
        a.put("recipientName", "Ana Souza");
        a.put("phone", "11987654321");
        a.put("postalCode", "01310100");
        a.put("state", "SP");
        a.put("city", "São Paulo");
        a.put("district", "Bela Vista");
        a.put("street", "Avenida Paulista");
        a.put("number", "1000");
        return a;
    }

    @Test
    void exactlyOneDefaultAddress() {
        String token = register(uniqueEmail(), "senha-forte-1").text("accessToken");

        var casa = post("/api/me/addresses").bearer(token).body(address("Casa")).send();
        var trabalho = post("/api/me/addresses").bearer(token).body(address("Trabalho")).send();
        assertThat(casa.json().path("isDefault").asBoolean()).isTrue();
        assertThat(trabalho.json().path("isDefault").asBoolean()).isFalse();

        long trabalhoId = trabalho.json().path("id").asLong();
        assertThat(put("/api/me/addresses/" + trabalhoId + "/default").bearer(token).send().status()).isEqualTo(204);
        var list = get("/api/me/addresses").bearer(token).send().json();
        assertThat(list.get(0).path("id").asLong()).isEqualTo(trabalhoId);
        assertThat(list.get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(list.get(1).path("isDefault").asBoolean()).isFalse();

        // Setar de novo o que já é principal não quebra nada.
        assertThat(put("/api/me/addresses/" + trabalhoId + "/default").bearer(token).send().status()).isEqualTo(204);

        // Remover o principal promove o restante.
        assertThat(delete("/api/me/addresses/" + trabalhoId).bearer(token).send().status()).isEqualTo(204);
        var after = get("/api/me/addresses").bearer(token).send().json();
        assertThat(after.size()).isEqualTo(1);
        assertThat(after.get(0).path("isDefault").asBoolean()).isTrue();
    }

    @Test
    void addressOfAnotherUserIsNotFound() {
        String owner = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        String intruder = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        long id = post("/api/me/addresses").bearer(owner).body(address("Casa")).send().json().path("id").asLong();

        assertThat(put("/api/me/addresses/" + id).bearer(intruder).body(address("X")).send().status()).isEqualTo(404);
        assertThat(delete("/api/me/addresses/" + id).bearer(intruder).send().status()).isEqualTo(404);
        assertThat(put("/api/me/addresses/" + id + "/default").bearer(intruder).send().status()).isEqualTo(404);
    }

    @Test
    void invalidPostalCodeIsRejected() {
        String token = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        var body = address("Casa");
        body.put("postalCode", "01310-100");
        var res = post("/api/me/addresses").bearer(token).body(body).send();
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body()).contains("postalCode");
    }

    @Test
    void addressLimitIsTen() {
        String token = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        for (int i = 0; i < 10; i++) post("/api/me/addresses").bearer(token).body(address("E" + i)).send();
        var eleventh = post("/api/me/addresses").bearer(token).body(address("E10")).send();
        assertThat(eleventh.status()).isEqualTo(422);
        assertThat(eleventh.text("code")).isEqualTo("ADDRESS_LIMIT_REACHED");
    }

    @Test
    void accessMatrix() {
        String customer = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        var adminLogin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(adminLogin.status()).as("admin criado no bootstrap").isEqualTo(200);
        String admin = adminLogin.text("accessToken");

        // Anônimo
        assertThat(get("/api/me").send().status()).isEqualTo(401);
        assertThat(get("/api/me/addresses").send().status()).isEqualTo(401);
        assertThat(get("/api/admin/dashboard").send().status()).isEqualTo(401);
        assertThat(post("/api/auth/resend-verification").send().status()).isEqualTo(401);
        // Token adulterado
        assertThat(get("/api/me").bearer(customer + "x").send().status()).isEqualTo(401);
        // CUSTOMER não entra no admin
        assertThat(get("/api/admin/dashboard").bearer(customer).send().status()).isEqualTo(403);
        // ADMIN passa pela autorização (rota ainda não existe → 404)
        assertThat(get("/api/admin/dashboard").bearer(admin).send().status()).isEqualTo(404);
    }

    @Test
    void logoutEverywhereInvalidatesAccessTokens() {
        var session = register(uniqueEmail(), "senha-forte-1");
        String access = session.text("accessToken");
        assertThat(post("/api/auth/logout-all").bearer(access).send().status()).isEqualTo(204);
        assertThat(get("/api/me").bearer(access).send().status()).isEqualTo(401);
        assertThat(post("/api/auth/refresh").ajax().refreshCookie(session.refreshCookie()).send().status()).isEqualTo(401);
    }
}
