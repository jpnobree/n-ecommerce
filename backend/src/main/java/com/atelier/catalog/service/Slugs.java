package com.atelier.catalog.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/** "Camiseta Básica Algodão" -> "camiseta-basica-algodao". */
public final class Slugs {

    public static final String PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private Slugs() {}

    public static String of(String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "item" : slug;
    }

    /** Primeiro slug livre: base, base-2, base-3... */
    public static String unique(String base, Predicate<String> taken) {
        String candidate = base;
        for (int i = 2; taken.test(candidate); i++) candidate = base + "-" + i;
        return candidate;
    }
}
