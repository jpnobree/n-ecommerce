package com.atelier.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;

@Component
class S3ImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ImageStorage.class);

    private final StorageProperties props;
    private volatile S3Client client;

    S3ImageStorage(StorageProperties props) {
        this.props = props;
    }

    /** Cliente criado sob demanda: a API sobe mesmo sem storage configurado (só upload falha). */
    private S3Client client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    var builder = S3Client.builder()
                            .region(Region.of(props.region()))
                            .httpClient(UrlConnectionHttpClient.create())
                            .forcePathStyle(props.pathStyle())
                            .credentialsProvider(props.accessKey() == null || props.accessKey().isBlank()
                                    ? DefaultCredentialsProvider.builder().build()
                                    : StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey())));
                    if (props.endpoint() != null && !props.endpoint().isBlank()) {
                        builder.endpointOverride(URI.create(props.endpoint()));
                    }
                    client = builder.build();
                }
            }
        }
        return client;
    }

    @Override
    public String put(String key, byte[] data, String contentType) {
        client().putObject(PutObjectRequest.builder()
                        .bucket(props.bucket())
                        .key(key)
                        .contentType(contentType)
                        // Chave única por upload: pode ser cacheada para sempre na CDN.
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(data));
        return props.publicBaseUrl().replaceAll("/$", "") + "/" + key;
    }

    @Override
    public void delete(String key) {
        try {
            client().deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
        } catch (S3Exception e) {
            // Sobra no bucket não afeta a loja; a limpeza pode ser feita por lifecycle rule.
            log.warn("Falha ao remover {} do storage: {}", key, e.getMessage());
        }
    }
}
