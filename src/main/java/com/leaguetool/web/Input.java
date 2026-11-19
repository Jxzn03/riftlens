package com.leaguetool.web;

/** Kleine Helfer zum Einlesen von Anfrage-Werten; Fehler werden zu {@link ApiException} mit festem Status. */
final class Input {

    /** Form der IDs von Archiv-Einträgen und Fearless-Serien (UUID). */
    static final String ID_PATTERN = "[0-9a-f-]{36}";

    private Input() {
    }

    /** Enum-Wert zum Namen; unbekannter Name → 400 mit {@code message}. */
    static <E extends Enum<E>> E enumValue(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, message);
        }
    }

    /** Ganze Zahl aus {@code raw} (ohne Trimmen); keine Zahl → {@code status} mit {@code message}. */
    static int intValue(String raw, int status, String message) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(status, message);
        }
    }
}
