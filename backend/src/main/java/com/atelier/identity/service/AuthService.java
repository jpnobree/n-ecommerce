package com.atelier.identity.service;

import com.atelier.identity.AuthProperties;
import com.atelier.identity.domain.AppUser;
import com.atelier.identity.domain.Role;
import com.atelier.identity.domain.UserStatus;
import com.atelier.identity.domain.UserToken;
import com.atelier.identity.repository.UserRepository;
import com.atelier.identity.repository.UserTokenRepository;
import com.atelier.notification.EmailRequested;
import com.atelier.shared.config.AppProperties;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final AuthProperties props;
    private final AppProperties app;
    private final Clock clock;
    /** Comparado quando o e-mail não existe, para o tempo de resposta não revelar contas cadastradas. */
    private final String dummyHash;

    AuthService(UserRepository users, UserTokenRepository userTokens, SessionService sessions,
                PasswordEncoder passwordEncoder, ApplicationEventPublisher events, AuthProperties props,
                AppProperties app, Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.props = props;
        this.app = app;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public SessionService.Session register(String name, String email, String password, boolean marketingOptIn,
                                           SessionService.ClientInfo client) {
        String normalized = normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        Instant now = clock.instant();
        var user = new AppUser();
        user.email = normalized;
        user.fullName = name.trim();
        user.passwordHash = passwordEncoder.encode(password);
        user.marketingOptIn = marketingOptIn;
        user.termsVersion = props.termsVersion();
        user.termsAcceptedAt = now;
        user.roles = EnumSet.of(Role.CUSTOMER);
        users.saveAndFlush(user);

        sendVerificationEmail(user);
        return sessions.start(user, client);
    }

    /** noRollbackFor: a contagem de falhas precisa ser gravada mesmo quando o login é recusado. */
    @Transactional(noRollbackFor = BusinessException.class)
    public SessionService.Session login(String email, String password, SessionService.ClientInfo client) {
        Instant now = clock.instant();
        AppUser user = users.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (user.isLocked(now)) {
            long minutes = Math.max(1, Duration.between(now, user.lockedUntil).toMinutes() + 1);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Tente novamente em " + minutes + " min.");
        }
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            user.recordFailedLogin(now);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (user.status != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_BLOCKED);
        }
        user.recordSuccessfulLogin(now);
        return sessions.start(user, client);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        UserToken token = usableToken(rawToken, UserToken.Type.EMAIL_VERIFY);
        AppUser user = users.findById(token.userId).orElseThrow();
        token.usedAt = clock.instant();
        if (user.emailVerifiedAt == null) user.emailVerifiedAt = token.usedAt;
    }

    @Transactional
    public void resendVerification(Long userId) {
        AppUser user = users.findById(userId).orElseThrow();
        if (user.emailVerifiedAt == null) sendVerificationEmail(user);
    }

    /** Sempre "aceito" para quem chama: não revela se o e-mail tem conta. */
    @Transactional
    public void forgotPassword(String email) {
        users.findByEmail(normalizeEmail(email))
                .filter(u -> u.status == UserStatus.ACTIVE)
                .ifPresent(user -> {
                    String raw = createToken(user, UserToken.Type.PASSWORD_RESET, props.resetPasswordTtl());
                    events.publishEvent(new EmailRequested(user.email, "Redefinição de senha",
                            "Olá, " + user.fullName + ".\n\nPara criar uma nova senha, acesse o link abaixo "
                                    + "(válido por " + props.resetPasswordTtl().toMinutes() + " minutos):\n\n"
                                    + app.frontendUrl() + "/redefinir-senha?token=" + raw
                                    + "\n\nSe você não pediu a redefinição, ignore este e-mail."));
                });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        UserToken token = usableToken(rawToken, UserToken.Type.PASSWORD_RESET);
        AppUser user = users.findById(token.userId).orElseThrow();
        token.usedAt = clock.instant();
        user.passwordHash = passwordEncoder.encode(newPassword);
        user.failedLoginCount = 0;
        user.lockedUntil = null;
        sessions.revokeAll(user);
        sendPasswordChangedEmail(user);
    }

    void sendPasswordChangedEmail(AppUser user) {
        events.publishEvent(new EmailRequested(user.email, "Sua senha foi alterada",
                "Olá, " + user.fullName + ".\n\nA senha da sua conta foi alterada e as outras sessões foram encerradas."
                        + "\nSe não foi você, redefina a senha imediatamente e fale com o atendimento."));
    }

    private void sendVerificationEmail(AppUser user) {
        String raw = createToken(user, UserToken.Type.EMAIL_VERIFY, props.verifyEmailTtl());
        events.publishEvent(new EmailRequested(user.email, "Confirme seu e-mail",
                "Olá, " + user.fullName + ".\n\nConfirme seu e-mail acessando o link abaixo:\n\n"
                        + app.frontendUrl() + "/verificar-email?token=" + raw));
    }

    private String createToken(AppUser user, UserToken.Type type, Duration ttl) {
        Instant now = clock.instant();
        userTokens.consumeAll(user.id, type, now);
        String raw = Tokens.random();
        var token = new UserToken();
        token.id = UUID.randomUUID();
        token.userId = user.id;
        token.type = type;
        token.tokenHash = Tokens.sha256(raw);
        token.createdAt = now;
        token.expiresAt = now.plus(ttl);
        userTokens.save(token);
        return raw;
    }

    private UserToken usableToken(String raw, UserToken.Type type) {
        return userTokens.findByTokenHashAndType(Tokens.sha256(raw), type)
                .filter(t -> t.isUsable(clock.instant()))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID_OR_EXPIRED));
    }
}
