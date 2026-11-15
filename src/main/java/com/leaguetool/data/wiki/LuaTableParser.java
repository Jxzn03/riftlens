package com.leaguetool.data.wiki;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser für Lua-Literale, wie sie in Wiki-Datenmodulen ({@code Module:ChampionData/data}) vorkommen.
 *
 * <p>Ergebnis-Typen: Tables werden zu {@code Map<String, Object>} (Einfügereihenfolge bleibt erhalten) oder,
 * wenn sie nur die Schlüssel {@code 1..n} bzw. positionale Werte enthalten, zu {@code List<Object>}.
 * Zahlen werden zu {@link Double}, Strings zu {@link String}, {@code true}/{@code false} zu {@link Boolean}
 * und {@code nil} zu {@code null}. Ein leerer Table {@code {}} wird zu einer leeren Liste.
 *
 * <p>Unterstützt: {@code ["key"] = v}, {@code [1] = v}, {@code key = v}, positionale Werte, Strings in
 * {@code '} und {@code "} mit Escapes, lange Strings {@code [[...]]}, Dezimal-, Hex- und Exponentenzahlen,
 * einfache arithmetische Ausdrücke ({@code 84+1000/17}), Trailing Commas (auch {@code ;}),
 * Zeilenkommentare {@code --} und Blockkommentare {@code --[[ ... ]]}. Ein führendes {@code return} wird
 * übersprungen.
 */
public final class LuaTableParser {

    private final String src;
    private int pos;

    private LuaTableParser(String src) {
        this.src = src;
    }

    /**
     * Parst einen Lua-Ausdruck (optional mit führendem {@code return}).
     *
     * @throws LuaParseException bei Syntaxfehlern (mit Zeile, Spalte und Position)
     */
    public static Object parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("Lua-Quelltext ist null");
        }
        LuaTableParser p = new LuaTableParser(source);
        p.skipWhitespaceAndComments();
        if (p.peekKeyword("return")) {
            p.pos += "return".length();
        }
        Object value = p.parseValue();
        p.skipWhitespaceAndComments();
        if (p.pos < p.src.length() && p.src.charAt(p.pos) == ';') {
            p.pos++;
            p.skipWhitespaceAndComments();
        }
        if (p.pos < p.src.length()) {
            throw p.error("Unerwarteter Inhalt nach dem Ende des Ausdrucks");
        }
        return value;
    }

    /**
     * Wie {@link #parse(String)}, erwartet aber einen Table mit Schlüsseln (Map). Ein leerer Table ergibt
     * eine leere Map.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseTable(String source) {
        Object value = parse(source);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return new LinkedHashMap<>();
        }
        throw new LuaParseException("Lua-Ausdruck ist kein Table mit Schlüsseln", 0, 1, 1);
    }

    // ---------------------------------------------------------------- Werte

    private Object parseValue() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) {
            throw error("Unerwartetes Ende, Wert erwartet");
        }
        char c = src.charAt(pos);
        if (c == '{') {
            return parseTable();
        }
        if (c == '"' || c == '\'') {
            return parseQuotedString();
        }
        if (c == '[' && longBracketLevel(pos) >= 0) {
            return parseLongString();
        }
        if (peekKeyword("true")) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (peekKeyword("false")) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (peekKeyword("nil")) {
            pos += 3;
            return null;
        }
        if (c == '-' || c == '+' || c == '(' || c == '.' || Character.isDigit(c)) {
            return parseExpression();
        }
        throw error("Unerwartetes Zeichen '" + c + "', Wert erwartet");
    }

    private Object parseTable() {
        expect('{');
        Map<Object, Object> entries = new LinkedHashMap<>();
        boolean onlyIntegerKeys = true;
        int nextIndex = 1;
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                throw error("Unerwartetes Ende, '}' erwartet");
            }
            if (src.charAt(pos) == '}') {
                pos++;
                break;
            }
            Object key;
            Object value;
            char c = src.charAt(pos);
            if (c == '[' && longBracketLevel(pos) < 0) {
                pos++;
                Object rawKey = parseValue();
                skipWhitespaceAndComments();
                expect(']');
                skipWhitespaceAndComments();
                expect('=');
                key = normalizeKey(rawKey);
                value = parseValue();
            } else if (isIdentifierStart(c) && identifierFollowedByAssign()) {
                key = readIdentifier();
                skipWhitespaceAndComments();
                expect('=');
                value = parseValue();
            } else {
                key = nextIndex++;
                value = parseValue();
            }
            if (!(key instanceof Integer)) {
                onlyIntegerKeys = false;
            }
            entries.put(key, value);
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                throw error("Unerwartetes Ende, '}' erwartet");
            }
            char sep = src.charAt(pos);
            if (sep == ',' || sep == ';') {
                pos++;
            } else if (sep != '}') {
                throw error("',' oder '}' erwartet, gefunden '" + sep + "'");
            }
        }
        return toResult(entries, onlyIntegerKeys);
    }

    private static Object toResult(Map<Object, Object> entries, boolean onlyIntegerKeys) {
        if (onlyIntegerKeys) {
            boolean sequential = true;
            for (int i = 1; i <= entries.size(); i++) {
                if (!entries.containsKey(i)) {
                    sequential = false;
                    break;
                }
            }
            if (sequential) {
                List<Object> list = new ArrayList<>(entries.size());
                for (int i = 1; i <= entries.size(); i++) {
                    list.add(entries.get(i));
                }
                return list;
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        entries.forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
    }

    /** Ganzzahlige Zahlen-Schlüssel werden zu {@link Integer}, andere Zahlen zu ihrer Textform. */
    private Object normalizeKey(Object rawKey) {
        if (rawKey instanceof String s) {
            return s;
        }
        if (rawKey instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < Integer.MAX_VALUE) {
                return (int) d.doubleValue();
            }
            return WikiExpr.format(d);
        }
        if (rawKey instanceof Boolean b) {
            return b.toString();
        }
        throw error("Ungültiger Table-Schlüssel (nil)");
    }

    // ---------------------------------------------------------------- Zahlen und Ausdrücke

    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                return value;
            }
            char c = src.charAt(pos);
            if (c == '+') {
                pos++;
                value += parseTerm();
            } else if (c == '-' && !src.startsWith("--", pos)) {
                pos++;
                value -= parseTerm();
            } else {
                return value;
            }
        }
    }

    private double parseTerm() {
        double value = parseUnary();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                return value;
            }
            char c = src.charAt(pos);
            if (c == '*') {
                pos++;
                value *= parseUnary();
            } else if (c == '/') {
                pos++;
                value /= parseUnary();
            } else if (c == '%') {
                pos++;
                double m = parseUnary();
                value = value - Math.floor(value / m) * m;
            } else {
                return value;
            }
        }
    }

    private double parseUnary() {
        skipWhitespaceAndComments();
        if (pos < src.length() && src.charAt(pos) == '-' && !src.startsWith("--", pos)) {
            pos++;
            return -parseUnary();
        }
        if (pos < src.length() && src.charAt(pos) == '+') {
            pos++;
            return parseUnary();
        }
        return parsePower();
    }

    private double parsePower() {
        double base = parseAtom();
        skipWhitespaceAndComments();
        if (pos < src.length() && src.charAt(pos) == '^') {
            pos++;
            return Math.pow(base, parseUnary());
        }
        return base;
    }

    private double parseAtom() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) {
            throw error("Unerwartetes Ende, Zahl erwartet");
        }
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            double v = parseExpression();
            skipWhitespaceAndComments();
            expect(')');
            return v;
        }
        if (Character.isDigit(c) || c == '.') {
            return parseNumber();
        }
        throw error("Zahl erwartet, gefunden '" + c + "'");
    }

    private double parseNumber() {
        int start = pos;
        if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
            pos += 2;
            int hexStart = pos;
            while (pos < src.length() && Character.digit(src.charAt(pos), 16) >= 0) {
                pos++;
            }
            if (pos == hexStart) {
                throw errorAt(start, "Ungültige Hex-Zahl");
            }
            return Long.parseLong(src.substring(hexStart, pos), 16);
        }
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            int expStart = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            if (pos == expStart) {
                throw errorAt(start, "Ungültiger Exponent in Zahl");
            }
        }
        String text = src.substring(start, pos);
        if (pos < src.length() && isIdentifierPart(src.charAt(pos))) {
            throw errorAt(start, "Ungültige Zahl '" + text + src.charAt(pos) + "'");
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw errorAt(start, "Ungültige Zahl '" + text + "'");
        }
    }

    // ---------------------------------------------------------------- Strings

    private String parseQuotedString() {
        int start = pos;
        char quote = src.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        // Byte-Escapes (dezimal, hex, Unicode) werden gesammelt und als UTF-8 dekodiert
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            if (pos >= src.length()) {
                throw errorAt(start, "String ohne schließendes " + quote);
            }
            char c = src.charAt(pos++);
            if (c == quote) {
                flushBytes(bytes, sb, start);
                return sb.toString();
            }
            if (c == '\n') {
                throw errorAt(start, "Zeilenumbruch in String");
            }
            if (c != '\\') {
                flushBytes(bytes, sb, start);
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw errorAt(start, "String ohne schließendes " + quote);
            }
            int escapeStart = pos - 1;
            char e = src.charAt(pos++);
            switch (e) {
                case 'x' -> {
                    if (pos + 2 > src.length() || hexDigit(src.charAt(pos)) < 0 || hexDigit(src.charAt(pos + 1)) < 0) {
                        throw errorAt(escapeStart, "Ungültige Escape-Sequenz \\x (zwei Hex-Ziffern erwartet)");
                    }
                    bytes.write(hexDigit(src.charAt(pos)) * 16 + hexDigit(src.charAt(pos + 1)));
                    pos += 2;
                }
                case 'u' -> {
                    if (pos >= src.length() || src.charAt(pos) != '{') {
                        throw errorAt(escapeStart, "Ungültige Unicode-Escape-Sequenz ('{' erwartet)");
                    }
                    int i = pos + 1;
                    long cp = 0;
                    while (i < src.length() && hexDigit(src.charAt(i)) >= 0) {
                        cp = cp * 16 + hexDigit(src.charAt(i));
                        if (cp > Character.MAX_CODE_POINT) {
                            throw errorAt(escapeStart, "Unicode-Escape-Sequenz außerhalb des Unicode-Bereichs");
                        }
                        i++;
                    }
                    if (i == pos + 1 || i >= src.length() || src.charAt(i) != '}') {
                        throw errorAt(escapeStart, "Ungültige Unicode-Escape-Sequenz (Hex-Ziffern und '}' erwartet)");
                    }
                    if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                        throw errorAt(escapeStart, "Unicode-Escape-Sequenz ist ein Surrogat");
                    }
                    bytes.writeBytes(new String(Character.toChars((int) cp)).getBytes(StandardCharsets.UTF_8));
                    pos = i + 1;
                }
                case 'z' -> {
                    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                        pos++;
                    }
                }
                default -> {
                    if (e >= '0' && e <= '9') {
                        int end = pos - 1;
                        int value = 0;
                        while (end < src.length() && end < escapeStart + 4 && src.charAt(end) >= '0'
                                && src.charAt(end) <= '9') {
                            value = value * 10 + (src.charAt(end) - '0');
                            end++;
                        }
                        if (value > 255) {
                            throw errorAt(escapeStart, "Dezimal-Escape " + value + " ist größer als 255");
                        }
                        bytes.write(value);
                        pos = end;
                    } else {
                        char simple = simpleEscape(e);
                        if (simple == 0) {
                            throw errorAt(escapeStart, "Ungültige Escape-Sequenz \\" + e);
                        }
                        flushBytes(bytes, sb, start);
                        sb.append(simple);
                    }
                }
            }
        }
    }

    /** Zeichen eines einfachen Escapes (n, t, Anführungszeichen …) oder 0, wenn unbekannt. */
    private static char simpleEscape(char e) {
        return switch (e) {
            case 'n', '\n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'a' -> (char) 7;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'v' -> (char) 11;
            case '\\' -> '\\';
            case '"' -> '"';
            case '\'' -> '\'';
            default -> 0;
        };
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** Dekodiert gesammelte Escape-Bytes streng als UTF-8 und hängt sie an. */
    private void flushBytes(ByteArrayOutputStream bytes, StringBuilder sb, int stringStart) {
        if (bytes.size() == 0) {
            return;
        }
        try {
            sb.append(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())));
        } catch (CharacterCodingException ex) {
            throw errorAt(stringStart, "Ungültige UTF-8-Bytefolge in String-Escapes");
        }
        bytes.reset();
    }

    /** Ebene einer öffnenden langen Klammer ({@code [[} = 0, {@code [==[} = 2) oder -1. */
    private int longBracketLevel(int at) {
        if (at >= src.length() || src.charAt(at) != '[') {
            return -1;
        }
        int i = at + 1;
        int level = 0;
        while (i < src.length() && src.charAt(i) == '=') {
            level++;
            i++;
        }
        return i < src.length() && src.charAt(i) == '[' ? level : -1;
    }

    private String parseLongString() {
        int start = pos;
        int level = longBracketLevel(pos);
        pos += level + 2;
        String close = "]" + "=".repeat(level) + "]";
        int end = src.indexOf(close, pos);
        if (end < 0) {
            throw errorAt(start, "Langer String ohne schließendes " + close);
        }
        String text = src.substring(pos, end);
        pos = end + close.length();
        // Ein Zeilenumbruch direkt nach der öffnenden Klammer gehört nicht zum Inhalt.
        if (text.startsWith("\r\n")) {
            return text.substring(2);
        }
        return text.startsWith("\n") ? text.substring(1) : text;
    }

    // ---------------------------------------------------------------- Bezeichner, Leerraum, Kommentare

    private static boolean isIdentifierStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }

    private String readIdentifier() {
        int start = pos;
        while (pos < src.length() && isIdentifierPart(src.charAt(pos))) {
            pos++;
        }
        return src.substring(start, pos);
    }

    /** {@code true}, wenn ab {@code pos} ein Bezeichner mit folgendem {@code =} (nicht {@code ==}) steht. */
    private boolean identifierFollowedByAssign() {
        int save = pos;
        try {
            readIdentifier();
            skipWhitespaceAndComments();
            return pos < src.length() && src.charAt(pos) == '=' && !src.startsWith("==", pos);
        } finally {
            pos = save;
        }
    }

    private boolean peekKeyword(String keyword) {
        return src.startsWith(keyword, pos)
                && (pos + keyword.length() >= src.length() || !isIdentifierPart(src.charAt(pos + keyword.length())));
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                pos++;
            } else if (src.startsWith("--", pos)) {
                int level = longBracketLevel(pos + 2);
                if (level >= 0) {
                    String close = "]" + "=".repeat(level) + "]";
                    int end = src.indexOf(close, pos + 2 + level + 2);
                    if (end < 0) {
                        throw error("Blockkommentar ohne schließendes " + close);
                    }
                    pos = end + close.length();
                } else {
                    int nl = src.indexOf('\n', pos);
                    pos = nl < 0 ? src.length() : nl + 1;
                }
            } else {
                return;
            }
        }
    }

    private void expect(char c) {
        if (pos >= src.length()) {
            throw error("Unerwartetes Ende, '" + c + "' erwartet");
        }
        if (src.charAt(pos) != c) {
            throw error("'" + c + "' erwartet, gefunden '" + src.charAt(pos) + "'");
        }
        pos++;
    }

    private LuaParseException error(String message) {
        return errorAt(pos, message);
    }

    private LuaParseException errorAt(int at, String message) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < Math.min(at, src.length()); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new LuaParseException(message, at, line, col);
    }

    /** Syntaxfehler im Lua-Quelltext mit Positionsangabe. */
    public static final class LuaParseException extends IllegalArgumentException {

        private final int position;
        private final int line;
        private final int column;

        public LuaParseException(String message, int position, int line, int column) {
            super("Lua-Syntaxfehler in Zeile " + line + ", Spalte " + column + " (Position " + position + "): "
                    + message);
            this.position = position;
            this.line = line;
            this.column = column;
        }

        /** 0-basierte Zeichenposition. */
        public int position() {
            return position;
        }

        /** 1-basierte Zeile. */
        public int line() {
            return line;
        }

        /** 1-basierte Spalte. */
        public int column() {
            return column;
        }
    }
}
