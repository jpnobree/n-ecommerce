package com.atelier.media;

/** Object storage das imagens. Implementação real: {@link S3ImageStorage}; nos testes, uma versão em memória. */
public interface ImageStorage {

    /** Grava e devolve a URL pública (CDN em produção). */
    String put(String key, byte[] data, String contentType);

    void delete(String key);
}
