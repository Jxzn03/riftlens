package com.leaguetool.calc;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stat-Namen in der eingestellten Data-Dragon-Sprache, für Filter und Tabelle im Items-Tab.
 *
 * <p>Deutsch nutzt die App-Namen aus {@link StatType#label()}. Die übrigen Namen stammen aus den
 * {@code <stats>}-Zeilen der Item-Beschreibungen von Data Dragon (Patch 16.23, je Sprache zeilenweise
 * den englischen Zeilen zugeordnet), ohne Artikel und Zählwörter, polnisch im Nominativ. Ein fehlender
 * Name fällt auf Englisch zurück, ein fehlender englischer auf das deutsche Label.
 */
public final class StatLabels {

    private static final Map<String, Map<StatType, String>> LABELS = Map.of(
            "en", english(),
            "fr", french(),
            "es", spanish(),
            "it", italian(),
            "pl", polish());

    /** Kurznamen für die Tabellenspalte; andere Sprachen nutzen den vollen Namen. */
    private static final Map<String, Map<StatType, String>> SHORT = Map.of(
            "de", germanShort(),
            "en", englishShort());

    private StatLabels() {
    }

    /** Voller Name, z. B. {@code "Attack Damage"} bei {@code en_US}, {@code "Angriffsschaden"} bei {@code de_DE}. */
    public static String label(StatType type, String locale) {
        String language = language(locale);
        if (language.equals("de")) {
            return type.label();
        }
        String name = LABELS.getOrDefault(language, LABELS.get("en")).get(type);
        if (name == null) {
            name = LABELS.get("en").get(type);
        }
        return name != null ? name : type.label();
    }

    /** Kurzname für enge Spalten (z. B. {@code "AD"}), sonst der volle Name. */
    public static String shortLabel(StatType type, String locale) {
        Map<StatType, String> names = SHORT.get(language(locale));
        String name = names == null ? null : names.get(type);
        return name != null ? name : label(type, locale);
    }

    private static String language(String locale) {
        return locale == null ? "en" : locale.split("_")[0].toLowerCase(Locale.ROOT);
    }

    private static Map<StatType, String> english() {
        Map<StatType, String> m = new EnumMap<>(StatType.class);
        m.put(StatType.HEALTH, "Health");
        m.put(StatType.MANA, "Mana");
        m.put(StatType.ATTACK_DAMAGE, "Attack Damage");
        m.put(StatType.ABILITY_POWER, "Ability Power");
        m.put(StatType.ADAPTIVE_FORCE, "Adaptive Force");
        m.put(StatType.ARMOR, "Armor");
        m.put(StatType.MAGIC_RESIST, "Magic Resist");
        m.put(StatType.ATTACK_SPEED, "Attack Speed");
        m.put(StatType.CRIT_CHANCE, "Critical Strike Chance");
        m.put(StatType.CRIT_DAMAGE, "Critical Strike Damage");
        m.put(StatType.MOVE_SPEED, "Move Speed");
        m.put(StatType.MOVE_SPEED_PERCENT, "Move Speed");
        m.put(StatType.ABILITY_HASTE, "Ability Haste");
        m.put(StatType.COOLDOWN_REDUCTION, "Cooldown Reduction");
        m.put(StatType.LETHALITY, "Lethality");
        m.put(StatType.ARMOR_PENETRATION, "Armor Penetration");
        m.put(StatType.MAGIC_PENETRATION, "Magic Penetration");
        m.put(StatType.MAGIC_PENETRATION_PERCENT, "Magic Penetration");
        m.put(StatType.LIFE_STEAL, "Life Steal");
        m.put(StatType.OMNIVAMP, "Omnivamp");
        m.put(StatType.HEALTH_REGEN, "Base Health Regen");
        m.put(StatType.HEALTH_REGEN_FLAT, "Health Regen per 5 s");
        m.put(StatType.MANA_REGEN, "Base Mana Regen");
        m.put(StatType.MANA_REGEN_FLAT, "Mana Regen per 5 s");
        m.put(StatType.HEAL_SHIELD_POWER, "Heal and Shield Power");
        m.put(StatType.TENACITY, "Tenacity");
        m.put(StatType.GOLD_PER_10, "Gold per 10 Seconds");
        return m;
    }

    private static Map<StatType, String> french() {
        Map<StatType, String> m = new EnumMap<>(StatType.class);
        m.put(StatType.HEALTH, "PV");
        m.put(StatType.MANA, "Mana");
        m.put(StatType.ATTACK_DAMAGE, "Dégâts d'attaque");
        m.put(StatType.ABILITY_POWER, "Puissance");
        m.put(StatType.ADAPTIVE_FORCE, "Force adaptative");
        m.put(StatType.ARMOR, "Armure");
        m.put(StatType.MAGIC_RESIST, "Résistance magique");
        m.put(StatType.ATTACK_SPEED, "Vitesse d'attaque");
        m.put(StatType.CRIT_CHANCE, "Chances de coup critique");
        m.put(StatType.CRIT_DAMAGE, "Dégâts de coup critique");
        m.put(StatType.MOVE_SPEED, "Vitesse de déplacement");
        m.put(StatType.MOVE_SPEED_PERCENT, "Vitesse de déplacement");
        m.put(StatType.ABILITY_HASTE, "Accélération de compétence");
        m.put(StatType.LETHALITY, "Létalité");
        m.put(StatType.ARMOR_PENETRATION, "Pénétration d'armure");
        m.put(StatType.MAGIC_PENETRATION, "Pénétration magique");
        m.put(StatType.MAGIC_PENETRATION_PERCENT, "Pénétration magique");
        m.put(StatType.LIFE_STEAL, "Vol de vie");
        m.put(StatType.OMNIVAMP, "Omnivampirisme");
        m.put(StatType.HEALTH_REGEN, "Régénération de base des PV");
        m.put(StatType.MANA_REGEN, "Régénération de base du mana");
        m.put(StatType.HEAL_SHIELD_POWER, "Efficacité des soins et boucliers");
        m.put(StatType.TENACITY, "Ténacité");
        m.put(StatType.GOLD_PER_10, "PO toutes les 10 sec");
        return m;
    }

    private static Map<StatType, String> spanish() {
        Map<StatType, String> m = new EnumMap<>(StatType.class);
        m.put(StatType.HEALTH, "Vida");
        m.put(StatType.MANA, "Maná");
        m.put(StatType.ATTACK_DAMAGE, "Daño de ataque");
        m.put(StatType.ABILITY_POWER, "Poder de habilidad");
        m.put(StatType.ADAPTIVE_FORCE, "Fuerza adaptable");
        m.put(StatType.ARMOR, "Armadura");
        m.put(StatType.MAGIC_RESIST, "Resistencia mágica");
        m.put(StatType.ATTACK_SPEED, "Velocidad de ataque");
        m.put(StatType.CRIT_CHANCE, "Probabilidad de impacto crítico");
        m.put(StatType.CRIT_DAMAGE, "Daño de impacto crítico");
        m.put(StatType.MOVE_SPEED, "Velocidad de movimiento");
        m.put(StatType.MOVE_SPEED_PERCENT, "Velocidad de movimiento");
        m.put(StatType.ABILITY_HASTE, "Velocidad de habilidades");
        m.put(StatType.LETHALITY, "Letalidad");
        m.put(StatType.ARMOR_PENETRATION, "Penetración de armadura");
        m.put(StatType.MAGIC_PENETRATION, "Penetración mágica");
        m.put(StatType.MAGIC_PENETRATION_PERCENT, "Penetración mágica");
        m.put(StatType.LIFE_STEAL, "Robo de vida");
        m.put(StatType.OMNIVAMP, "Omnisucción");
        m.put(StatType.HEALTH_REGEN, "Regeneración de vida básica");
        m.put(StatType.MANA_REGEN, "Regeneración de maná básica");
        m.put(StatType.HEAL_SHIELD_POWER, "Poder de curaciones y escudos");
        m.put(StatType.TENACITY, "Tenacidad");
        m.put(StatType.GOLD_PER_10, "Oro cada 10 segundos");
        return m;
    }

    private static Map<StatType, String> italian() {
        Map<StatType, String> m = new EnumMap<>(StatType.class);
        m.put(StatType.HEALTH, "Salute");
        m.put(StatType.MANA, "Mana");
        m.put(StatType.ATTACK_DAMAGE, "Attacco fisico");
        m.put(StatType.ABILITY_POWER, "Potere magico");
        m.put(StatType.ADAPTIVE_FORCE, "Forza adattiva");
        m.put(StatType.ARMOR, "Armatura");
        m.put(StatType.MAGIC_RESIST, "Resistenza magica");
        m.put(StatType.ATTACK_SPEED, "Velocità d'attacco");
        m.put(StatType.CRIT_CHANCE, "Probabilità colpo critico");
        m.put(StatType.CRIT_DAMAGE, "Danni critici");
        m.put(StatType.MOVE_SPEED, "Velocità di movimento");
        m.put(StatType.MOVE_SPEED_PERCENT, "Velocità di movimento");
        m.put(StatType.ABILITY_HASTE, "Velocità abilità");
        m.put(StatType.LETHALITY, "Letalità");
        m.put(StatType.ARMOR_PENETRATION, "Penetrazione armatura");
        m.put(StatType.MAGIC_PENETRATION, "Penetrazione magica");
        m.put(StatType.MAGIC_PENETRATION_PERCENT, "Penetrazione magica");
        m.put(StatType.LIFE_STEAL, "Rubavita");
        m.put(StatType.OMNIVAMP, "Rubavita globale");
        m.put(StatType.HEALTH_REGEN, "Rigenerazione salute base");
        m.put(StatType.MANA_REGEN, "Rigenerazione mana base");
        m.put(StatType.HEAL_SHIELD_POWER, "Guarigione e potere scudo");
        m.put(StatType.TENACITY, "Tenacia");
        m.put(StatType.GOLD_PER_10, "Oro ogni 10 secondi");
        return m;
    }

    private static Map<StatType, String> polish() {
        Map<StatType, String> m = new EnumMap<>(StatType.class);
        m.put(StatType.HEALTH, "Zdrowie");
        m.put(StatType.MANA, "Mana");
        m.put(StatType.ATTACK_DAMAGE, "Obrażenia od ataku");
        m.put(StatType.ABILITY_POWER, "Moc umiejętności");
        m.put(StatType.ADAPTIVE_FORCE, "Siła adaptacyjna");
        m.put(StatType.ARMOR, "Pancerz");
        m.put(StatType.MAGIC_RESIST, "Odporność na magię");
        m.put(StatType.ATTACK_SPEED, "Prędkość ataku");
        m.put(StatType.CRIT_CHANCE, "Szansa na trafienie krytyczne");
        m.put(StatType.CRIT_DAMAGE, "Obrażenia trafienia krytycznego");
        m.put(StatType.MOVE_SPEED, "Prędkość ruchu");
        m.put(StatType.MOVE_SPEED_PERCENT, "Prędkość ruchu");
        m.put(StatType.ABILITY_HASTE, "Przyspieszenie umiejętności");
        m.put(StatType.LETHALITY, "Destrukcja");
        m.put(StatType.ARMOR_PENETRATION, "Przebicie pancerza");
        m.put(StatType.MAGIC_PENETRATION, "Przebicie odporności na magię");
        m.put(StatType.MAGIC_PENETRATION_PERCENT, "Przebicie odporności na magię");
        m.put(StatType.LIFE_STEAL, "Kradzież życia");
        m.put(StatType.OMNIVAMP, "Wszechwampiryzm");
        m.put(StatType.HEALTH_REGEN, "Podstawowa regeneracja zdrowia");
        m.put(StatType.MANA_REGEN, "Podstawowa regeneracja many");
        m.put(StatType.HEAL_SHIELD_POWER, "Siła leczenia i tarcz");
        m.put(StatType.TENACITY, "Nieustępliwość");
        m.put(StatType.GOLD_PER_10, "Złoto co 10 sek.");
        return m;
    }

    private static Map<StatType, String> germanShort() {
        Map<StatType, String> m = new EnumMap<>(StatType.class);
        m.put(StatType.ATTACK_DAMAGE, "AD");
        m.put(StatType.ABILITY_POWER, "AP");
        m.put(StatType.MAGIC_RESIST, "MR");
        m.put(StatType.ATTACK_SPEED, "AS");
        m.put(StatType.CRIT_CHANCE, "Krit");
        m.put(StatType.CRIT_DAMAGE, "Krit-Schaden");
        m.put(StatType.ABILITY_HASTE, "Haste");
        m.put(StatType.ARMOR_PENETRATION, "Rüstungsdurchdr.");
        m.put(StatType.MAGIC_PENETRATION, "Magiedurchdr.");
        m.put(StatType.MAGIC_PENETRATION_PERCENT, "Magiedurchdr.");
        m.put(StatType.HEALTH_REGEN, "Lebensreg.");
        m.put(StatType.HEALTH_REGEN_FLAT, "Lebensreg.");
        m.put(StatType.MANA_REGEN, "Manareg.");
        m.put(StatType.MANA_REGEN_FLAT, "Manareg.");
        m.put(StatType.HEAL_SHIELD_POWER, "Heil/Schild");
        m.put(StatType.GOLD_PER_10, "Gold/10 s");
        return m;
    }

    private static Map<StatType, String> englishShort() {
        Map<StatType, String> m = new EnumMap<>(StatType.class);
        m.put(StatType.ATTACK_DAMAGE, "AD");
        m.put(StatType.ABILITY_POWER, "AP");
        m.put(StatType.MAGIC_RESIST, "MR");
        m.put(StatType.ATTACK_SPEED, "AS");
        m.put(StatType.CRIT_CHANCE, "Crit");
        m.put(StatType.CRIT_DAMAGE, "Crit Damage");
        m.put(StatType.MOVE_SPEED, "MS");
        m.put(StatType.MOVE_SPEED_PERCENT, "MS");
        m.put(StatType.ABILITY_HASTE, "Haste");
        m.put(StatType.ARMOR_PENETRATION, "Armor Pen");
        m.put(StatType.MAGIC_PENETRATION, "Magic Pen");
        m.put(StatType.MAGIC_PENETRATION_PERCENT, "Magic Pen");
        m.put(StatType.HEALTH_REGEN, "Health Regen");
        m.put(StatType.HEALTH_REGEN_FLAT, "Health Regen");
        m.put(StatType.MANA_REGEN, "Mana Regen");
        m.put(StatType.MANA_REGEN_FLAT, "Mana Regen");
        m.put(StatType.HEAL_SHIELD_POWER, "Heal/Shield");
        m.put(StatType.GOLD_PER_10, "Gold/10 s");
        return m;
    }
}
