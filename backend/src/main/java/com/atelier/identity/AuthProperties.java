package com.atelier.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.auth")
public record AuthProperties(
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        /* Janela em que um refresh já rotacionado ainda é aceito (abas abrindo ao mesmo tempo). */
        @DefaultValue("10s") Duration refreshReuseGrace,
        @DefaultValue("24h") Duration verifyEmailTtl,
        @DefaultValue("30m") Duration resetPasswordTtl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("2026-10") String termsVersion,
        /* PEM (PKCS#8 / X.509). Vazios em dev: um par RSA efêmero é gerado a cada start. */
        String jwtPrivateKey,
        String jwtPublicKey,
        /* Cria o primeiro ADMIN no start se ainda não existir nenhum. */
        String bootstrapAdminEmail,
        String bootstrapAdminPassword) {
}
