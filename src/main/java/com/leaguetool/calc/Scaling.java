package com.leaguetool.calc;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skalierung einer Fähigkeit mit einem Wert des Angreifers.
 *
 * @param ratio Anteil als Bruch (0.5 = 50 %); bei {@link ScalingStat#LEVEL} der Wert pro Level
 * @param stat  skalierender Wert
 */
public record Scaling(double ratio, ScalingStat stat) {

    private static final Pattern RATIO_TEXT =
            Pattern.compile("^[+(\\s]*(\\d+(?:\\.\\d+)?)\\s*(%)?\\s*(.+)$");

    public Scaling {
        Objects.requireNonNull(stat, "stat");
    }

    /** Beitrag zum Schaden: {@code ratio * stat}. */
    public double contribution(DamageInput input) {
        return ratio * stat.valueFrom(input);
    }

    /**
     * Liest eine Wiki-Ratio wie "50% AP", "60% bonus AD", "(+ 8% of maximum health)" oder für
     * Level-Skalierung "10 per level" (Wert pro Level, ohne "%").
     *
     * @return leer, wenn keine Zahl oder kein bekannter Stat erkannt wird, bei Prozent-Angabe für
     *         {@link ScalingStat#LEVEL} sowie bei fehlendem "%" für alle anderen Stats
     */
    public static Optional<Scaling> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = RATIO_TEXT.matcher(text.replace('\u00A0', ' ').strip()); // geschütztes Leerzeichen
        if (!m.matches()) {
            return Optional.empty();
        }
        double value = Double.parseDouble(m.group(1));
        boolean percent = m.group(2) != null;
        Optional<ScalingStat> stat = ScalingStat.parse(m.group(3));
        if (stat.isEmpty()) {
            return Optional.empty();
        }
        if (stat.get() == ScalingStat.LEVEL) {
            return percent ? Optional.empty() : Optional.of(new Scaling(value, ScalingStat.LEVEL));
        }
        return percent ? Optional.of(new Scaling(value / 100.0, stat.get())) : Optional.empty();
    }
}
