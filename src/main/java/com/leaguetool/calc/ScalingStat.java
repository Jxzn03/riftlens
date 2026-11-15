package com.leaguetool.calc;

import com.leaguetool.util.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/** Wert des Angreifers, mit dem eine Fähigkeit skaliert (z. B. "50% AP" → {@link #AP}). */
public enum ScalingStat {
    AP("Fähigkeitsstärke", DamageInput::ap),
    AD("Angriffsschaden", DamageInput::totalAd),
    BONUS_AD("zusätzlicher Angriffsschaden", DamageInput::bonusAd),
    MAX_HEALTH("maximales Leben", DamageInput::maxHealth),
    BONUS_HEALTH("zusätzliches Leben", DamageInput::bonusHealth),
    ARMOR("Rüstung", DamageInput::armor),
    BONUS_ARMOR("zusätzliche Rüstung", DamageInput::bonusArmor),
    MAGIC_RESIST("Magieresistenz", DamageInput::magicResist),
    BONUS_MAGIC_RESIST("zusätzliche Magieresistenz", DamageInput::bonusMagicResist),
    /** Champion-Level; die Ratio ist dann ein Wert pro Level (z. B. 10 pro Level). */
    LEVEL("Level", DamageInput::level);

    private static final Pattern LEADING_RATIO = Pattern.compile("^[+(\\s]*\\d+(?:\\.\\d+)?\\s*%?\\s*");
    private static final Map<String, ScalingStat> NAMES = buildNames();

    private final String label;
    private final ToDoubleFunction<DamageInput> getter;

    ScalingStat(String label, ToDoubleFunction<DamageInput> getter) {
        this.label = label;
        this.getter = getter;
    }

    /** Deutsche Bezeichnung für die Oberfläche. */
    public String label() {
        return label;
    }

    /** Wert dieses Stats im {@code input}. */
    public double valueFrom(DamageInput input) {
        return getter.applyAsDouble(input);
    }

    /**
     * Erkennt englische Wiki-Bezeichnungen wie "AP", "bonus AD", "AD", "maximum health", "bonus health",
     * "armor", "bonus armor", "bonus magic resistance" (Groß-/Kleinschreibung egal). Eine vorangestellte
     * Ratio ("50% AP", "(+ 60% bonus AD)") wird ignoriert. Werte des Ziels ("target's maximum health")
     * werden nicht erkannt, da sie sich nicht mit den Werten des Angreifers ausdrücken lassen.
     */
    public static Optional<ScalingStat> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = Text.normalizeName(text);
        s = LEADING_RATIO.matcher(s).replaceFirst("");
        s = Text.normalizeName(s.replace("(", " ").replace(")", " ").replace("+", " "));
        if (s.startsWith("of ")) {
            s = s.substring(3).strip();
        }
        return Optional.ofNullable(NAMES.get(s));
    }

    private static Map<String, ScalingStat> buildNames() {
        Map<String, ScalingStat> m = new HashMap<>();
        put(m, AP, "ap", "ability power", "total ap", "total ability power");
        put(m, AD, "ad", "attack damage", "total ad", "total attack damage");
        put(m, BONUS_AD, "bonus ad", "bonus attack damage");
        put(m, MAX_HEALTH, "maximum health", "max health", "total health", "health", "maximum hp", "max hp");
        put(m, BONUS_HEALTH, "bonus health", "bonus hp");
        put(m, ARMOR, "armor", "total armor", "armour", "total armour");
        put(m, BONUS_ARMOR, "bonus armor", "bonus armour");
        put(m, MAGIC_RESIST, "magic resistance", "magic resist", "mr", "total magic resistance",
                "total magic resist", "total mr");
        put(m, BONUS_MAGIC_RESIST, "bonus magic resistance", "bonus magic resist", "bonus mr");
        put(m, LEVEL, "level", "champion level", "per level");
        return Map.copyOf(m);
    }

    private static void put(Map<String, ScalingStat> m, ScalingStat stat, String... names) {
        for (String n : names) {
            m.put(n, stat);
        }
    }
}
