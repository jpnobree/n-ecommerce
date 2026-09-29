package com.atelier.identity.api;

import com.atelier.identity.AuthProperties;
import com.atelier.identity.api.dto.AuthResponse;
import com.atelier.identity.service.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Cookie do refresh token: HttpOnly, SameSite=Strict, restrito a /api/auth (PRD, seção 8).
 * Junto vai "has_session=1", legível pelo JavaScript e sem segredo: só avisa ao frontend que vale tentar
 * o refresh (visitante anônimo não dispara uma chamada que sempre falha).
 */
@Component
class RefreshCookies {

    static final String NAME = "refresh_token";
    static final String HINT = "has_session";
    private static final String PATH = "/api/auth";

    private final AuthProperties props;
    private final Clock clock;

    RefreshCookies(AuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    ResponseEntity<AuthResponse> respond(HttpStatus status, SessionService.Session session) {
        Duration maxAge = Duration.between(clock.instant(), session.refreshExpiresAt());
        var refresh = base(session.refreshToken()).maxAge(maxAge).build();
        var hint = hint("1").maxAge(maxAge).build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refresh.toString(), hint.toString())
                .body(AuthResponse.of(session));
    }

    String[] cleared() {
        return new String[]{base("").maxAge(0).build().toString(), hint("").maxAge(0).build().toString()};
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path(PATH);
    }

    private ResponseCookie.ResponseCookieBuilder hint(String value) {
        return ResponseCookie.from(HINT, value).secure(props.cookieSecure()).sameSite("Lax").path("/");
    }
}
