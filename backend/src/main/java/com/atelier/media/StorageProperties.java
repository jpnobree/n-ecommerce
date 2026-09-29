package com.atelier.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3/R2/MinIO. endpoint vazio = AWS S3 padrão da região.
 * publicBaseUrl = de onde a loja lê as imagens (CDN em produção; o próprio MinIO em dev).
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(
        String endpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("atelier-media") String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("http://localhost:9000/atelier-media") String publicBaseUrl,
        @DefaultValue("true") boolean pathStyle) {
}
