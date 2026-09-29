package com.atelier.identity.service;

import com.atelier.identity.AuthProperties;
import com.atelier.identity.domain.AppUser;
import com.atelier.identity.domain.RefreshToken;
import com.atelier.identity.domain.UserStatus;
import com.atelier.identity.repository.RefreshTokenRepository;
import com.atelier.identity.repository.UserRepository;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Emissão de access tokens e ciclo de vida dos refresh tokens (rotação + detecção de reuso). */
@Service
public class SessionService {

    public record Session(AppUser user, String accessToken, long expiresInSeconds, String refreshToken, Instant refreshExpiresAt) {}

    public record ClientInfo(String userAgent, String ip) {}

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final TokenVersionValidator tokenVersions;
    private final AuthProperties props;
    private final Clock clock;

    SessionService(JwtEncoder encoder, RefreshTokenRepository refreshTokens, UserRepository users,
                   TokenVersionValidator tokenVersions, AuthProperties props, Clock clock) {
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.tokenVersions = tokenVersions;
        this.props = props;
        this.clock = clock;
    }

    /** Nova sessão (nova família de refresh). */
    @Transactional
    public Session start(AppUser user, ClientInfo client) {
        return issue(user, UUID.randomUUID(), client).session;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public Session refresh(String rawToken, ClientInfo client) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByHashForUpdate(Tokens.sha256(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_INVALID));

        if (current.revokedAt != null) {
            boolean withinGrace = current.replacedBy != null && current.revokedAt.plus(props.refreshReuseGrace()).isAfter(now);
            if (!withinGrace) {
                // Token já rotacionado reapareceu: provável roubo. Derruba a família inteira.
                log.warn("Reuso de refresh token detectado: userId={} family={}", current.userId, current.familyId);
                refreshTokens.revokeFamily(current.familyId, now);
                throw new BusinessException(ErrorCode.REFRESH_REUSED);
            }
        } else if (!current.expiresAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.REFRESH_INVALID);
        }

        AppUser user = users.findById(current.userId)
                .filter(u -> u.status == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_INVALID));

        Issued next = issue(user, current.familyId, client);
        if (current.revokedAt == null) {
            current.revokedAt = now;
            current.replacedBy = next.token.id;
        }
        return next.session;
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokens.findByTokenHash(Tokens.sha256(rawToken)).ifPresent(t -> {
            if (t.revokedAt == null) t.revokedAt = clock.instant();
        });
    }

    /** Encerra todas as sessões e invalida os access tokens já emitidos. */
    @Transactional
    public void revokeAll(AppUser user) {
        refreshTokens.revokeAllForUser(user.id, clock.instant());
        user.revokeAccessTokens();
        users.saveAndFlush(user);
        tokenVersions.evict(user.id);
    }

    private record Issued(Session session, RefreshToken token) {}

    private Issued issue(AppUser user, UUID familyId, ClientInfo client) {
        Instant now = clock.instant();
        String raw = Tokens.random();

        var token = new RefreshToken();
        token.id = UUID.randomUUID();
        token.userId = user.id;
        token.tokenHash = Tokens.sha256(raw);
        token.familyId = familyId;
        token.createdAt = now;
        token.expiresAt = now.plus(props.refreshTokenTtl());
        token.userAgent = truncate(client.userAgent(), 255);
        token.ip = truncate(client.ip(), 45);
        refreshTokens.save(token);

        var claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(user.id.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .claim("roles", user.roles.stream().map(Enum::name).toList())
                .claim("tv", user.tokenVersion)
                .build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

        return new Issued(new Session(user, access, props.accessTokenTtl().toSeconds(), raw, token.expiresAt), token);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
