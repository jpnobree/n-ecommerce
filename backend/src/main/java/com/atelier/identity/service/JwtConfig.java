package com.atelier.identity.service;

import com.atelier.identity.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** JWT RS256: chave privada só no backend, pública permite validar em outros serviços no futuro. */
@Configuration
class JwtConfig {

    static final String ISSUER = "atelier";
    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    RSAKey jwtSigningKey(AuthProperties props, Environment env) throws GeneralSecurityException {
        boolean configured = notBlank(props.jwtPrivateKey()) && notBlank(props.jwtPublicKey());
        if (!configured) {
            if (env.acceptsProfiles(Profiles.of("hml", "prod"))) {
                throw new IllegalStateException("JWT_PRIVATE_KEY e JWT_PUBLIC_KEY são obrigatórias em hml/prod");
            }
            log.warn("Chaves JWT não configuradas: gerando par RSA efêmero (sessões não sobrevivem a restart)");
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).keyID("dev").build();
        }
        var factory = KeyFactory.getInstance("RSA");
        var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(pem(props.jwtPublicKey())));
        var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(pem(props.jwtPrivateKey())));
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("k1").build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey key, TokenVersionValidator tokenVersionValidator) throws JOSEException {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), tokenVersionValidator));
        return decoder;
    }

    private static byte[] pem(String value) {
        String base64 = value.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
