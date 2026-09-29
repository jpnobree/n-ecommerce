package com.atelier.identity.service;

import com.atelier.identity.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Rejeita access tokens cuja claim "tv" ficou para trás (senha trocada, conta bloqueada, logout geral).
 * Cache de 30 s por instância; a própria instância invalida na hora via {@link #evict}.
 */
@Component
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "Token revogado", null);

    private final UserRepository users;
    // ponytail: cache local, até 30 s de atraso entre instâncias; trocar por Redis pub/sub se isso importar
    private final Cache<Long, Optional<Integer>> versions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(100_000)
            .build();

    TokenVersionValidator(UserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        Number tv = jwt.getClaim("tv");
        Optional<Integer> current = versions.get(userId, users::findActiveTokenVersion);
        boolean valid = tv != null && current.isPresent() && current.get() == tv.intValue();
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(REVOKED);
    }

    public void evict(Long userId) {
        versions.invalidate(userId);
    }
}
