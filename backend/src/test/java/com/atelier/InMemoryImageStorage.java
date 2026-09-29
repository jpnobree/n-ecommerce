package com.atelier;

import com.atelier.media.ImageStorage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Storage de imagens dos testes: guarda em memória e registra remoções. */
public class InMemoryImageStorage implements ImageStorage {

    public static final String BASE_URL = "http://localhost:9000/atelier-media/";

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    public final Set<String> deleted = ConcurrentHashMap.newKeySet();

    @Override
    public String put(String key, byte[] data, String contentType) {
        objects.put(key, data);
        return BASE_URL + key;
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
        deleted.add(key);
    }
}
