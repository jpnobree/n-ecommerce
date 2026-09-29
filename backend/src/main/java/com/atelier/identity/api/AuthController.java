package com.atelier.identity.api;

import com.atelier.identity.api.dto.*;
import com.atelier.identity.service.AccountService;
import com.atelier.identity.service.AuthService;
import com.atelier.identity.service.SessionService;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import com.atelier.shared.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final SessionService sessions;
    private final AccountService accounts;
    private final RefreshCookies cookies;

    AuthController(AuthService auth, SessionService sessions, AccountService accounts, RefreshCookies cookies) {
        this.auth = auth;
        this.sessions = sessions;
        this.accounts = accounts;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        var session = auth.register(req.name(), req.email(), req.password(), Boolean.TRUE.equals(req.marketingOptIn()), client(http));
        return cookies.respond(HttpStatus.CREATED, session);
    }

    @PostMapping("/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return cookies.respond(HttpStatus.OK, auth.login(req.email(), req.password(), client(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
                              HttpServletRequest http) {
        requireAjax(http);
        if (refreshToken == null) throw new BusinessException(ErrorCode.REFRESH_INVALID);
        try {
            return cookies.respond(HttpStatus.OK, sessions.refresh(refreshToken, client(http)));
        } catch (BusinessException e) {
            // Cookie inválido não serve para nada: apaga para o navegador parar de enviá-lo.
            return ResponseEntity.status(e.code.status)
                    .header(HttpHeaders.SET_COOKIE, cookies.cleared())
                    .body(GlobalExceptionHandler.problemBody(e.code, e.getMessage()));
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
                                HttpServletRequest http) {
        requireAjax(http);
        if (refreshToken != null) sessions.revoke(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.cleared()).build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        accounts.logoutEverywhere(Long.valueOf(jwt.getSubject()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.cleared()).build();
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody TokenRequest req) {
        auth.verifyEmail(req.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@AuthenticationPrincipal Jwt jwt) {
        auth.resendVerification(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody EmailRequest req) {
        auth.forgotPassword(req.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        auth.resetPassword(req.token(), req.newPassword());
    }

    static SessionService.ClientInfo client(HttpServletRequest http) {
        return new SessionService.ClientInfo(http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    }

    /**
     * Header customizado não pode ser enviado entre origens sem preflight CORS (que não liberamos):
     * garante que só o nosso frontend dispare as rotas que usam o cookie.
     */
    private static void requireAjax(HttpServletRequest http) {
        if (!"XMLHttpRequest".equals(http.getHeader("X-Requested-With"))) {
            throw new BusinessException(ErrorCode.CSRF_CHECK_FAILED);
        }
    }
}
