package com.leaguetool.calc;

import com.leaguetool.util.Text;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.leaguetool.calc.StatType.ABILITY_HASTE;
import static com.leaguetool.calc.StatType.ADAPTIVE_FORCE;
import static com.leaguetool.calc.StatType.ARMOR;
import static com.leaguetool.calc.StatType.ATTACK_SPEED;
import static com.leaguetool.calc.StatType.BASIC_ABILITY_HASTE;
import static com.leaguetool.calc.StatType.LIFE_STEAL;
import static com.leaguetool.calc.StatType.MAGIC_RESIST;
import static com.leaguetool.calc.StatType.MANA;
import static com.leaguetool.calc.StatType.MAX_HEALTH_PERCENT;
import static com.leaguetool.calc.StatType.MOVE_BONUS_AMP;
import static com.leaguetool.calc.StatType.MOVE_SPEED;
import static com.leaguetool.calc.StatType.MOVE_SPEED_PERCENT;
import static com.leaguetool.calc.StatType.RESIST_PERCENT;
import static com.leaguetool.calc.StatType.RUNE_HEALTH;
import static com.leaguetool.calc.StatType.SLOW_RESIST;
import static com.leaguetool.calc.StatType.SUMMONER_SPELL_HASTE;
import static com.leaguetool.calc.StatType.TENACITY;
import static com.leaguetool.calc.StatType.ULTIMATE_HASTE;

/**
 * Werte, die Runen und Rune-Shards dem Build-Rechner geben.
 *
 * <p>Fest im Code steht nur, <em>wie</em> eine Rune wirkt; ihre Zahlen ({@link Definition#defaults()}) erneuert der
 * Patch-Abgleich ({@code RuneEffectUpdater}) beim ersten Start nach einem Patchwechsel: Runen aus den
 * Data-Dragon-Texten ({@link Definition#source()}), Shards samt Reihen aus der Wiki-Seite „Rune“. Mitgeliefert ist der
 * Stand von Patch 16.22. Arten:
 * <ul>
 *   <li>{@link Kind#ALWAYS}: immer aktiv, höchstens vom Level abhängig (Transcendence, Health-Scaling-Shard).</li>
 *   <li>{@link Kind#STACKS}: je Stapel; wie viele, wählt der Nutzer (Legend: Haste, Ultimate Hunter).</li>
 *   <li>{@link Kind#TOGGLE}: nur unter einer Bedingung, als Schalter (Conditioning nach 12 Minuten).</li>
 * </ul>
 * Item Haste (Cosmic Insight) und Trinket Haste (Grisly Mementos) bleiben außen vor: Der Rechner kennt keine
 * Abklingzeiten von Items.
 */
public final class RuneEffects {

    /** Höchstes Level für lineare Werte "based on level". */
    static final int MAX_LEVEL = 18;

    /** Wann ein Effekt wirkt. */
    public enum Kind { ALWAYS, STACKS, TOGGLE }

    /** Wert eines Effekts auf einem Level bei {@code s} Stapeln (Schalter: 0 oder 1) mit den Zahlen {@code p}. */
    @FunctionalInterface
    interface Value {
        double at(int level, int s, double[] p);
    }

    record Effect(StatType type, Value value) {
    }

    /**
     * Wirkung einer Rune oder eines Shards.
     *
     * @param defaults  mitgelieferte Zahlen (Patch 16.22)
     * @param source    Muster im Data-Dragon-Text; seine Gruppen sind die Zahlen in Reihenfolge von {@code defaults}
     *                  ({@code null} bei Shards, deren Werte aus dem Wiki kommen)
     * @param maxStacks größte Stapelzahl aus den Zahlen (Schalter: 1, immer aktiv: 0)
     * @param summary   Kurzbeschreibung aus den Zahlen
     */
    public record Definition(int id, String name, Kind kind, String stackLabel, double[] defaults, Pattern source,
                             ToIntFunction<double[]> maxStacks, Function<double[], String> summary,
                             List<Effect> effects) {

        public boolean isShard() {
            return source == null;
        }
    }

    /** Rune oder Shard mit den aktuell gültigen Zahlen (für Anzeige und Rechnung). */
    public record Entry(int id, String name, Kind kind, int maxStacks, String stackLabel, String summary) {
    }

    /** Shard-Reihen laut Patch 16.22: Offensive, Flex, Defensive (IDs wie {@code perks.statPerks}). */
    public static final List<List<Integer>> DEFAULT_SHARD_ROWS = List.of(
            List.of(5008, 5005, 5007),
            List.of(5008, 5010, 5001),
            List.of(5011, 5013, 5001));

    private static final String N = "(\\d+(?:\\.\\d+)?)";

    private static final List<Definition> DEFINITIONS = List.of(
            // ---- Shards (Wiki "Rune")
            shard(5008, "Adaptive Force", 9, p -> "+" + n(p[0]) + " adaptive Kraft", fixed(ADAPTIVE_FORCE, 0)),
            shard(5005, "Attack Speed", 10, p -> "+" + n(p[0]) + " % Angriffstempo", fixed(ATTACK_SPEED, 0)),
            shard(5007, "Ability Haste", 8, p -> "+" + n(p[0]) + " Fähigkeitstempo", fixed(ABILITY_HASTE, 0)),
            shard(5010, "Move Speed", 2.5, p -> "+" + n(p[0]) + " % Lauftempo", fixed(MOVE_SPEED_PERCENT, 0)),
            shard(5001, "Health Scaling", 10, p -> "+" + n(p[0]) + " Leben je Level (" + n(p[0]) + "–" + n(p[0] * MAX_LEVEL) + ")",
                    new Effect(RUNE_HEALTH, (level, s, p) -> p[0] * level)),
            shard(5011, "Health", 65, p -> "+" + n(p[0]) + " Leben", fixed(RUNE_HEALTH, 0)),
            shard(5013, "Tenacity and Slow Resist", 15, p -> "+" + n(p[0]) + " % Zähigkeit und Verlangsamungsresistenz",
                    fixed(TENACITY, 0), fixed(SLOW_RESIST, 0)),

            // ---- Precision
            new Definition(9104, "Legend: Alacrity", Kind.STACKS, "Legend-Stapel", new double[] {3, 1.5, 10},
                    source("Gain " + N + "% attack speed plus an additional " + N + "% for every Legend stack \\(max " + N + " stacks\\)"),
                    p -> (int) p[2], p -> "+" + n(p[0]) + " % Angriffstempo, +" + n(p[1]) + " % je Stapel",
                    List.of(new Effect(ATTACK_SPEED, (level, s, p) -> p[0] + p[1] * s))),
            new Definition(9105, "Legend: Haste", Kind.STACKS, "Legend-Stapel", new double[] {1.5, 10},
                    source("Gain " + N + " basic ability haste for every Legend stack \\(max " + N + " stacks\\)"),
                    p -> (int) p[1], p -> "+" + n(p[0]) + " Fähigkeitstempo für Grundfähigkeiten je Stapel",
                    List.of(new Effect(BASIC_ABILITY_HASTE, (level, s, p) -> p[0] * s))),
            new Definition(9103, "Legend: Bloodline", Kind.STACKS, "Legend-Stapel", new double[] {0.45, 15, 85},
                    source("Gain " + N + "% Life Steal for every Legend stack \\(max " + N + " stacks\\)\\. At maximum Legend stacks, gain " + N + " max health"),
                    p -> (int) p[1], p -> "+" + n(p[0]) + " % Lebensraub je Stapel, bei " + n(p[1]) + " Stapeln +" + n(p[2]) + " Leben",
                    List.of(new Effect(LIFE_STEAL, (level, s, p) -> p[0] * s),
                            new Effect(RUNE_HEALTH, (level, s, p) -> s >= p[1] ? p[2] : 0))),

            // ---- Domination
            new Definition(8106, "Ultimate Hunter", Kind.STACKS, "Bounty-Hunter-Stapel (je Gegner einmal)", new double[] {6, 5},
                    source("Your ultimate gains " + N + " Ability Haste, plus an additional " + N + " Ability Haste per Bounty Hunter stack"),
                    p -> 5, p -> "+" + n(p[0]) + " Fähigkeitstempo für die Ultimate, +" + n(p[1]) + " je Stapel",
                    List.of(new Effect(ULTIMATE_HASTE, (level, s, p) -> p[0] + p[1] * s))),

            // ---- Sorcery
            new Definition(8226, "Manaflow Band", Kind.STACKS, "Treffer mit Mana-Bonus", new double[] {25, 250},
                    source("permanently increases your maximum mana by " + N + ", up to " + N + " mana"),
                    p -> (int) Math.round(p[1] / p[0]), p -> "+" + n(p[0]) + " Mana je Stapel, höchstens " + n(p[1]),
                    List.of(new Effect(MANA, (level, s, p) -> p[0] * s))),
            new Definition(8210, "Transcendence", Kind.ALWAYS, "", new double[] {5, 5, 8, 5},
                    source("Level " + N + ": \\+" + N + " Ability Haste.*?Level " + N + ": \\+" + N + " Ability Haste"),
                    p -> 0, p -> "+" + n(p[1]) + " Fähigkeitstempo ab Level " + n(p[0]) + ", +" + n(p[1] + p[3]) + " ab Level " + n(p[2]),
                    List.of(new Effect(ABILITY_HASTE, (level, s, p) -> (level >= p[0] ? p[1] : 0) + (level >= p[2] ? p[3] : 0)))),
            new Definition(8234, "Celerity", Kind.ALWAYS, "", new double[] {7, 1},
                    source("All movement bonuses are " + N + "% more effective on you and gain " + N + "% Move Speed"),
                    p -> 0, p -> "+" + n(p[1]) + " % Lauftempo, Lauftempo-Boni wirken " + n(p[0]) + " % stärker",
                    List.of(new Effect(MOVE_SPEED_PERCENT, (level, s, p) -> p[1]), new Effect(MOVE_BONUS_AMP, (level, s, p) -> p[0]))),
            // Adaptiv: 1 AP entspricht einem Punkt adaptiver Kraft, darum zählen die AP-Werte.
            new Definition(8233, "Absolute Focus", Kind.TOGGLE, "über 70 % Leben", new double[] {18, 30, 1.8, 3},
                    source("up to " + N + " Attack Damage or " + N + " Ability Power.*?Grants " + N + " Attack Damage or " + N + " Ability Power at level 1"),
                    p -> 1, p -> n(p[3]) + "–" + n(p[1]) + " adaptive Kraft (je nach Level)",
                    List.of(new Effect(ADAPTIVE_FORCE, (level, s, p) -> byLevel(p[3], p[1], level)))),
            new Definition(8232, "Waterwalking", Kind.TOGGLE, "im Fluss", new double[] {10, 13, 30},
                    source("Gain " + N + " Move Speed and " + N + " - " + N + " Adaptive Force"),
                    p -> 1, p -> "+" + n(p[0]) + " Lauftempo und " + n(p[1]) + "–" + n(p[2]) + " adaptive Kraft (je nach Level)",
                    List.of(new Effect(MOVE_SPEED, (level, s, p) -> p[0]), new Effect(ADAPTIVE_FORCE, (level, s, p) -> byLevel(p[1], p[2], level)))),
            // 8, 24, 48, 80, 120, 168 AP (Dreieckszahlen × erster Wert); die AD-Werte entsprechen 0,6 je Punkt.
            new Definition(8236, "Gathering Storm", Kind.STACKS, "abgelaufene 10 Minuten", new double[] {8},
                    source("10 min: \\+ ?" + N + " AP"),
                    p -> 6, p -> n(p[0]) + " / " + n(p[0] * 3) + " / " + n(p[0] * 6) + " / … adaptive Kraft je 10 Minuten",
                    List.of(new Effect(ADAPTIVE_FORCE, (level, s, p) -> p[0] * s * (s + 1) / 2))),

            // ---- Resolve
            new Definition(8429, "Conditioning", Kind.TOGGLE, "nach 12 Minuten", new double[] {12, 8, 8, 3},
                    source("After " + N + " min gain \\+" + N + " Armor and \\+" + N + " Magic Resist and increase your Armor and Magic Resist by " + N + "%"),
                    p -> 1, p -> "nach " + n(p[0]) + " Minuten +" + n(p[1]) + " Rüstung und +" + n(p[2]) + " Magieresistenz, danach +" + n(p[3]) + " %",
                    List.of(new Effect(ARMOR, (level, s, p) -> p[1]), new Effect(MAGIC_RESIST, (level, s, p) -> p[2]),
                            new Effect(RESIST_PERCENT, (level, s, p) -> p[3]))),
            new Definition(8451, "Overgrowth", Kind.STACKS, "Stapel je Vasallen/Monster-Paket", new double[] {3, 8, 120, 3.5},
                    source("gaining " + N + " maximum health for every " + N + "\\..*?absorbed " + N + " monsters or enemy minions, gain an additional " + N + "% maximum health"),
                    p -> (int) Math.round(p[2] / p[1]), p -> "+" + n(p[0]) + " Leben je " + n(p[1]) + " Vasallen/Monster, bei " + n(p[2]) + " +" + n(p[3]) + " % Leben",
                    List.of(new Effect(RUNE_HEALTH, (level, s, p) -> p[0] * s),
                            new Effect(MAX_HEALTH_PERCENT, (level, s, p) -> s * p[1] >= p[2] ? p[3] : 0))),
            new Definition(8242, "Unflinching", Kind.TOGGLE, "unter Kontrolleffekt", new double[] {10},
                    source("Gain " + N + " Armor and Magic Resist when crowd controlled"),
                    p -> 1, p -> "+" + n(p[0]) + " Rüstung und Magieresistenz",
                    List.of(new Effect(ARMOR, (level, s, p) -> p[0]), new Effect(MAGIC_RESIST, (level, s, p) -> p[0]))),

            // ---- Inspiration
            new Definition(8347, "Cosmic Insight", Kind.ALWAYS, "", new double[] {18},
                    source("\\+" + N + " Summoner Spell Haste"),
                    p -> 0, p -> "+" + n(p[0]) + " Beschwörerzauber-Tempo (Item Haste nicht verrechnet)",
                    List.of(new Effect(SUMMONER_SPELL_HASTE, (level, s, p) -> p[0]))),
            new Definition(8316, "Jack Of All Trades", Kind.STACKS, "verschiedene Werte aus Items", new double[] {1, 8, 20, 5, 10},
                    source("Each stack grants you " + N + " Ability Haste.*?Gain " + N + " or " + N + " bonus Adaptive Force at " + N + " and " + N + " stacks"),
                    p -> 15, p -> "+" + n(p[0]) + " Fähigkeitstempo je Stapel, +" + n(p[1]) + " / +" + n(p[2]) + " adaptive Kraft ab " + n(p[3]) + " / " + n(p[4]) + " Stapeln",
                    List.of(new Effect(ABILITY_HASTE, (level, s, p) -> p[0] * s),
                            new Effect(ADAPTIVE_FORCE, (level, s, p) -> s >= p[4] ? p[2] : s >= p[3] ? p[1] : 0))));

    private static final Map<Integer, Definition> BY_ID =
            DEFINITIONS.stream().collect(Collectors.toUnmodifiableMap(Definition::id, Function.identity()));

    private static volatile Map<Integer, double[]> current = defaults();
    private static volatile List<List<Integer>> shardRows = DEFAULT_SHARD_ROWS;

    private RuneEffects() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Optional<Definition> definition(int id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Mitgelieferte Zahlen je ID (Kopie). */
    public static Map<Integer, double[]> defaults() {
        Map<Integer, double[]> out = new HashMap<>();
        DEFINITIONS.forEach(d -> out.put(d.id(), d.defaults().clone()));
        return out;
    }

    /** Aktuelle Shard-Reihen. */
    public static List<List<Integer>> shardRows() {
        return shardRows;
    }

    /**
     * Ersetzt die gültigen Zahlen und Shard-Reihen. Fehlende oder unpassende Einträge behalten die mitgelieferten
     * Zahlen.
     */
    public static void install(Map<Integer, double[]> params, List<List<Integer>> rows) {
        Map<Integer, double[]> next = defaults();
        params.forEach((id, p) -> {
            Definition d = BY_ID.get(id);
            if (d != null && p != null && p.length == d.defaults().length) {
                next.put(id, p.clone());
            }
        });
        current = next;
        shardRows = rows == null || rows.size() != 3 ? DEFAULT_SHARD_ROWS : rows.stream().map(List::copyOf).toList();
    }

    /** Alle Runen und Shards mit den aktuell gültigen Zahlen. */
    public static List<Entry> all() {
        Map<Integer, double[]> params = current;
        return DEFINITIONS.stream().map(d -> entry(d, params.get(d.id()))).toList();
    }

    private static Entry entry(Definition d, double[] p) {
        int max = switch (d.kind()) {
            case ALWAYS -> 0;
            case TOGGLE -> 1;
            case STACKS -> Math.max(0, d.maxStacks().applyAsInt(p));
        };
        return new Entry(d.id(), d.name(), d.kind(), max, d.stackLabel(), d.summary().apply(p));
    }

    /**
     * Bonus-Werte einer Runenseite auf einem Level.
     *
     * @param runes  gewählte Runen (doppelte zählen einmal)
     * @param shards gewählte Shards je Reihe (derselbe Shard in zwei Reihen zählt zweimal)
     * @param stacks Stapel bzw. Schalter (0/1) je Runen-ID; fehlend = 0
     */
    public static StatBlock stats(List<Integer> runes, List<Integer> shards, Map<Integer, Integer> stacks, int level) {
        int lvl = StatCalculator.clampLevel(level);
        Map<Integer, double[]> params = current;
        List<List<Integer>> rows = shardRows;
        StatBlock block = StatBlock.EMPTY;
        for (int id : new LinkedHashSet<>(runes)) {
            Definition d = BY_ID.get(id);
            if (d != null && !d.isShard()) {
                block = block.plus(stats(d, params.get(id), lvl, stacks.getOrDefault(id, 0)));
            }
        }
        for (int i = 0; i < Math.min(shards.size(), rows.size()); i++) {
            int id = shards.get(i);
            Definition d = BY_ID.get(id);
            if (d != null && d.isShard() && rows.get(i).contains(id)) {
                block = block.plus(stats(d, params.get(id), lvl, 0));
            }
        }
        return block;
    }

    private static StatBlock stats(Definition d, double[] p, int level, int stacks) {
        Entry e = entry(d, p);
        int s = d.kind() == Kind.ALWAYS ? 0 : Math.max(0, Math.min(e.maxStacks(), stacks));
        if (d.kind() == Kind.TOGGLE && s == 0) {
            return StatBlock.EMPTY;
        }
        StatBlock block = StatBlock.EMPTY;
        for (Effect effect : d.effects()) {
            double v = effect.value().at(level, s, p);
            if (v != 0) {
                block = block.plus(StatBlock.of(effect.type(), v));
            }
        }
        return block;
    }

    /** Linear von {@code from} (Level 1) bis {@code to} (Level 18). */
    static double byLevel(double from, double to, int level) {
        int lvl = StatCalculator.clampLevel(level);
        return from + (to - from) * (lvl - 1) / (MAX_LEVEL - 1);
    }

    private static String n(double v) {
        return Text.num(v);
    }

    private static Pattern source(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /** Effekt mit der Zahl an Stelle {@code index}. */
    private static Effect fixed(StatType type, int index) {
        return new Effect(type, (level, s, p) -> p[index]);
    }

    private static Definition shard(int id, String name, double value, Function<double[], String> summary, Effect... effects) {
        return new Definition(id, name, Kind.ALWAYS, "", new double[] {value}, null, p -> 0, summary, List.of(effects));
    }
}
