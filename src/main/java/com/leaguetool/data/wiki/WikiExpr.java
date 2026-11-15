package com.leaguetool.data.wiki;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rechenhilfen für Wiki-Werte: einfache arithmetische Ausdrücke ({@code 35*2}, {@code 40*(1+0.4*2)}),
 * Zahlenformatierung und die Rang-Syntax des {@code {{ap|...}}}-Templates.
 */
final class WikiExpr {

    /** {@code Formel for N} (Rang- bzw. Level-Formel mit Variable x). */
    static final Pattern FOR_SPEC = Pattern.compile("^(.+?)\\s+for\\s+(\\d+)$");
    /** Trenner in {@code A to B}. */
    static final Pattern TO_SPLIT = Pattern.compile("\\s+to\\s+");
    /** Trenner einer Klartext-Werteliste {@code 1 / 2 / 3} (nur mit Leerzeichen; {@code 100/3} ist eine Division). */
    private static final Pattern LIST_SPLIT = Pattern.compile("\\s+/\\s+");
    private static final Pattern TRAILING_COUNT = Pattern.compile("^(.*\\S)\\s+(\\d+)$");

    private WikiExpr() {
    }

    /** Art einer Rang-Angabe. */
    enum Kind {
        /** Einzelner Wert, gilt für alle Ränge. */
        CONSTANT,
        /** {@code A to B}: linear auf die Standard-Rangzahl interpoliert. */
        INTERPOLATED,
        /** Explizite Werteliste, {@code A to B N} oder {@code Formel for N}: eigene Länge. */
        FIXED
    }

    /**
     * Werte je Rang.
     *
     * @param values Werte (Länge = Standard-Rangzahl bzw. eigene Länge bei {@link Kind#FIXED})
     * @param kind   Art der Angabe
     * @param first  erster Wert (für Klartext "A – B")
     * @param last   letzter Wert
     */
    record RankValues(List<Double> values, Kind kind, double first, double last) {

        /** Werte für eine andere Rangzahl (nur für CONSTANT und INTERPOLATED möglich). */
        RankValues withRanks(int ranks) {
            return switch (kind) {
                case CONSTANT -> new RankValues(Collections.nCopies(Math.max(1, ranks), first), kind, first, last);
                case INTERPOLATED -> new RankValues(interpolate(first, last, ranks), kind, first, last);
                case FIXED -> this;
            };
        }
    }

    /** Wertet einen Ausdruck aus; leer, wenn er nicht auswertbar ist. */
    static OptionalDouble tryEval(String expr) {
        return tryEval(expr, Double.NaN);
    }

    /** Wie {@link #tryEval(String)}, mit Wert für die Variable {@code x}. */
    static OptionalDouble tryEval(String expr, double x) {
        if (expr == null || expr.isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            double v = new Parser(expr, x).parseAll();
            return Double.isFinite(v) ? OptionalDouble.of(v) : OptionalDouble.empty();
        } catch (IllegalArgumentException e) {
            return OptionalDouble.empty();
        }
    }

    /** Rundet auf 6 Nachkommastellen (gegen Gleitkomma-Rauschen wie 16.000000000000004). */
    static double round6(double v) {
        double r = Math.round(v * 1_000_000d) / 1_000_000d;
        return r == 0 ? 0 : r;
    }

    /** Formatiert eine Zahl ohne überflüssige Nachkommastellen (max. 4), z. B. 100.0 → "100", 1.35 → "1.35". */
    static String format(double v) {
        if (!Double.isFinite(v)) {
            return String.valueOf(v);
        }
        BigDecimal bd = BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.signum() == 0) {
            return "0";
        }
        return bd.toPlainString();
    }

    /** Lineare Interpolation von {@code a} nach {@code b} über {@code n} Werte (gerundet). */
    static List<Double> interpolate(double a, double b, int n) {
        List<Double> out = new ArrayList<>();
        if (n <= 1) {
            out.add(round6(a));
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(round6(a + (b - a) * i / (n - 1)));
        }
        return out;
    }

    /**
     * Wertet die positionalen Argumente eines {@code {{ap|...}}} aus.
     *
     * <ul>
     *   <li>mehrere Argumente: explizite Werte je Rang</li>
     *   <li>{@code A to B} (optional {@code A to B N}): linear interpoliert</li>
     *   <li>{@code Formel for N}: Formel mit {@code x} = 1..N</li>
     *   <li>ein Ausdruck: konstanter Wert</li>
     * </ul>
     *
     * @return leer, wenn nicht alles auswertbar ist
     */
    static Optional<RankValues> rankValues(List<String> args, int defaultRanks) {
        List<String> parts = args.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        if (parts.size() > 1) {
            List<Double> values = new ArrayList<>();
            for (String p : parts) {
                OptionalDouble v = tryEval(p);
                if (v.isEmpty()) {
                    return Optional.empty();
                }
                values.add(round6(v.getAsDouble()));
            }
            return Optional.of(new RankValues(values, Kind.FIXED, values.get(0), values.get(values.size() - 1)));
        }
        return rankValues(parts.get(0), defaultRanks);
    }

    /**
     * Wie {@link #rankValues(List, int)} für einen einzelnen Ausdruck, auch Klartext wie {@code "20 – 40"} oder
     * {@code "1 / 2 / 3"}. Ein {@code /} ohne umgebende Leerzeichen ist eine Division ({@code 100/3}).
     */
    static Optional<RankValues> rankValues(String spec, int defaultRanks) {
        String s = spec.trim();
        if (s.indexOf('–') >= 0) {
            // Klartext-Spanne "20 – 40" (Halbgeviertstrich, kein Minus)
            String[] dash = s.split("\\s*–\\s*");
            return dash.length == 2 ? range(dash[0], dash[1], defaultRanks) : Optional.empty();
        }
        s = s.replace('−', '-');
        Matcher forMatcher = FOR_SPEC.matcher(s);
        if (forMatcher.matches()) {
            int n = Integer.parseInt(forMatcher.group(2));
            if (n < 1 || n > 30) {
                return Optional.empty();
            }
            String[] toInFormula = TO_SPLIT.split(forMatcher.group(1), -1);
            if (toInFormula.length == 2) {
                // "A to B for N"
                return range(toInFormula[0], toInFormula[1] + " " + n, n);
            }
            List<Double> values = new ArrayList<>();
            for (int x = 1; x <= n; x++) {
                OptionalDouble v = tryEval(forMatcher.group(1), x);
                if (v.isEmpty()) {
                    return Optional.empty();
                }
                values.add(round6(v.getAsDouble()));
            }
            return Optional.of(new RankValues(values, Kind.FIXED, values.get(0), values.get(n - 1)));
        }
        String[] toParts = TO_SPLIT.split(s, -1);
        if (toParts.length == 2) {
            return range(toParts[0], toParts[1], defaultRanks);
        }
        String[] items = LIST_SPLIT.split(s, -1);
        if (items.length > 1 && items.length <= 30) {
            return rankValues(List.of(items), defaultRanks);
        }
        OptionalDouble single = tryEval(s);
        if (single.isPresent()) {
            double v = round6(single.getAsDouble());
            return Optional.of(new RankValues(Collections.nCopies(Math.max(1, defaultRanks), v), Kind.CONSTANT, v, v));
        }
        return Optional.empty();
    }

    private static Optional<RankValues> range(String from, String toWithCount, int defaultRanks) {
        OptionalDouble a = tryEval(from);
        if (a.isEmpty()) {
            return Optional.empty();
        }
        OptionalDouble b = tryEval(toWithCount);
        if (b.isPresent()) {
            return Optional.of(new RankValues(interpolate(a.getAsDouble(), b.getAsDouble(), defaultRanks),
                    Kind.INTERPOLATED, round6(a.getAsDouble()), round6(b.getAsDouble())));
        }
        Matcher m = TRAILING_COUNT.matcher(toWithCount.trim());
        if (m.matches()) {
            OptionalDouble end = tryEval(m.group(1));
            int n = Integer.parseInt(m.group(2));
            if (end.isPresent() && n >= 1 && n <= 30) {
                return Optional.of(new RankValues(interpolate(a.getAsDouble(), end.getAsDouble(), n),
                        Kind.FIXED, round6(a.getAsDouble()), round6(end.getAsDouble())));
            }
        }
        return Optional.empty();
    }

    /** Rekursiver Abstiegsparser für + - * / ^, Klammern, unäres Minus und die Variable x. */
    private static final class Parser {
        private final String s;
        private final double x;
        private int pos;

        Parser(String s, double x) {
            this.s = s.toLowerCase(Locale.ROOT).replace('×', '*').replace('−', '-');
            this.x = x;
        }

        double parseAll() {
            double v = expr();
            skip();
            if (pos != s.length()) {
                throw new IllegalArgumentException("Unerwartetes Zeichen in Ausdruck: " + s);
            }
            return v;
        }

        private double expr() {
            double v = term();
            while (true) {
                skip();
                if (eat('+')) {
                    v += term();
                } else if (eat('-')) {
                    v -= term();
                } else {
                    return v;
                }
            }
        }

        private double term() {
            double v = unary();
            while (true) {
                skip();
                if (eat('*')) {
                    v *= unary();
                } else if (eat('/')) {
                    v /= unary();
                } else if (s.startsWith("mod", pos)) {
                    pos += 3;
                    v %= unary();
                } else {
                    return v;
                }
            }
        }

        private double unary() {
            skip();
            if (eat('-')) {
                return -unary();
            }
            if (eat('+')) {
                return unary();
            }
            double base = atom();
            skip();
            if (eat('^')) {
                return Math.pow(base, unary());
            }
            return base;
        }

        private double atom() {
            skip();
            if (eat('(')) {
                double v = expr();
                skip();
                if (!eat(')')) {
                    throw new IllegalArgumentException("')' erwartet: " + s);
                }
                return v;
            }
            if (pos < s.length() && s.charAt(pos) == 'x' && !Double.isNaN(x)) {
                pos++;
                return x;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("Zahl erwartet: " + s);
            }
            if (pos < s.length() && s.charAt(pos) == 'e' && pos + 1 < s.length()
                    && (Character.isDigit(s.charAt(pos + 1)) || s.charAt(pos + 1) == '-' || s.charAt(pos + 1) == '+')) {
                pos += 2;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Ungültige Zahl: " + s, e);
            }
        }

        private boolean eat(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }
}
