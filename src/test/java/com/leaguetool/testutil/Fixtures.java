package com.leaguetool.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Lädt Test-Fixtures aus {@code src/test/resources/fixtures}. */
public final class Fixtures {

    private Fixtures() {
    }

    /** @param path relativ zu {@code fixtures/}, z. B. {@code "ddragon/item.json"} */
    public static String read(String path) {
        String resource = "/fixtures/" + path;
        try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture nicht gefunden: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
