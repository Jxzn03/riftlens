package com.leaguetool.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Gemeinsamer, unkonfigurierter {@link ObjectMapper} und kleine JSON-Helfer. */
public final class Json {

    /** Threadsicher, solange er nicht umkonfiguriert wird. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** Eingerückter JSON-Text (Zeilenenden nach {@code line.separator}). */
    public static String pretty(JsonNode node) throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /** Wie {@link #pretty(JsonNode)}, als UTF-8-Bytes. */
    public static byte[] prettyBytes(JsonNode node) throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    /** Texte eines JSON-Arrays; fehlender Knoten → leere Liste. */
    public static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** Parst eingebettetes JSON, das immer gültig sein muss; sonst {@link IllegalStateException}. */
    public static JsonNode parseOrThrow(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
