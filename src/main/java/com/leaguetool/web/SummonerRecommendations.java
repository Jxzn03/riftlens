package com.leaguetool.web;

import com.leaguetool.data.lolalytics.LolalyticsFullTierList;
import com.leaguetool.data.lolalytics.LolalyticsRoleStrength;
import com.leaguetool.model.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Empfohlene Champions für einen Summoner, nachgebaut nach op.gg („Empfohlene Champions“, Quelle
 * {@code summoner-most-champions}): Kandidaten der Hauptposition, die der Spieler bisher kaum gespielt hat, gewichtet
 * nach Meta-Stärke, passendem Spielstil und Bann-Druck. Die op.gg-Kennzeichen werden übernommen: „Hauptposition
 * passt“, „Spielstil passt“, „Neu für dich“, „Starker Pick #n“ und „Geringer Bann-Druck“.
 *
 * <p>Die Meta-Werte kommen nur aus bereits gecachten Lolalytics-Tierlisten (kein Abruf, siehe {@code docs/statistik.md}).
 * Fehlt die Liste der Hauptposition, gibt es keine Empfehlungen, sondern einen Hinweis.
 */
final class SummonerRecommendations {

    static final int COUNT = 9;
    /** Empfohlene Bans (zusammen mit COUNT zwei volle Reihen zu je sechs Karten). */
    static final int BAN_COUNT = 3;
    /** Prozentpunkte je Niederlage über den Siegen gegen einen Lane-Gegner (0:3 = 3, 3:8 = 5 Niederlagen Überschuss). */
    private static final double BAN_NET_LOSS_WEIGHT = 1.5;
    /** Untergrenze für eine positive Bilanz, damit ein gut liegender Gegner nicht völlig aus der Wertung fällt. */
    private static final double BAN_MIN_RECORD = -3;
    private static final double BAN_RATE_WEIGHT = 5;
    /** Bannrate ab diesem Wert: „Häufig gebannt“. */
    static final double HIGH_BAN_RATE = 0.15;
    /** Ab so vielen Spielen gilt ein Champion als gespielt und fällt weg (op.gg: {@code exclude_played_champions}). */
    static final int PLAYED_GAMES = 3;
    /** Mindestanteil einer Position an den Spielen, damit sie als Nebenposition zählt. */
    static final double SUB_POSITION_SHARE = 0.15;
    /** Bannrate unter diesem Wert: „Geringer Bann-Druck“. */
    static final double LOW_BAN_RATE = 0.03;
    /** Ränge der Stärke-Rangliste bis hierhin bekommen „Starker Pick #n“ (op.gg zeigt Werte bis etwa #30). */
    static final int HONEY_RANKS = 30;
    /** Glättung des Spielstil-Werts: so viele Spiele mit 50 % Winrate kommen dazu. */
    private static final int STYLE_PRIOR_GAMES = 10;
    /** Ab dieser Nähe zu den eigenen Champions: „Spielstil passt“. */
    static final double STYLE_MATCH = 0.3;
    private static final double AFFINITY_WEIGHT = 3;
    /** Ab dieser Meisterschaftsstufe gilt ein Champion als vertraut statt neu. */
    static final int FAMILIAR_LEVEL = 5;
    /** Abschlag für Kandidaten der Nebenposition (Prozentpunkte). */
    private static final double SUB_ROLE_PENALTY = 1.0;
    /** Höchstens so viele Vorschläge derselben Hauptklasse, solange es Alternativen gibt. */
    static final int MAX_PER_CLASS = 3;
    /** Gewicht der Spiele weiterer eigener Konten gegenüber dem angezeigten Konto. */
    static final double OTHER_ACCOUNT_WEIGHT = 0.5;
    private static final Map<String, String> TAG_LABELS = Map.of("Assassin", "Assassinen", "Fighter", "Kämpfer",
            "Mage", "Magier", "Marksman", "Schützen", "Support", "Supporter", "Tank", "Tanks");

    private SummonerRecommendations() {
    }

    /**
     * @param rows      Match-Zeilen der Abfrage ({@code SummonerLookup.matchRow})
     * @param mastery   Champion-Key → Meisterschaftsstufe
     * @param tags      Champion-Key → Data-Dragon-Klassen (z. B. {@code Assassin})
     * @param lists     gecachte Tierlisten je Rolle; fehlende Rollen fehlen in der Map
     */
    static Map<String, Object> compute(List<Map<String, Object>> rows, Map<Integer, Integer> mastery,
                                       Map<Integer, List<String>> tags, Map<Role, LolalyticsFullTierList> lists) {
        return compute(rows, List.of(), mastery, tags, lists);
    }

    /**
     * Wie {@link #compute(List, Map, Map, Map)}, dazu die gecachten Spiele der übrigen eigenen Konten ({@code otherRows},
     * nur wenn das angezeigte Konto selbst ein eigenes ist). Sie zählen für Positionen und Spielstil mit
     * {@value #OTHER_ACCOUNT_WEIGHT}, das angezeigte Konto also doppelt; ob ein Champion schon gespielt ist, entscheiden
     * alle Spiele voll.
     */
    static Map<String, Object> compute(List<Map<String, Object>> rows, List<Map<String, Object>> otherRows,
                                       Map<Integer, Integer> mastery, Map<Integer, List<String>> tags,
                                       Map<Role, LolalyticsFullTierList> lists) {
        Map<Role, Double> positions = new EnumMap<>(Role.class);
        Map<Integer, Double> weighted = new HashMap<>();
        Map<Integer, Double> wins = new HashMap<>();
        Map<Integer, Integer> played = new HashMap<>();
        double games = 0;
        for (List<Map<String, Object>> source : List.of(rows, otherRows)) {
            double weight = source == rows ? 1 : OTHER_ACCOUNT_WEIGHT;
            for (Map<String, Object> row : source) {
                Role role = roleOf((String) row.get("position"));
                if (Boolean.TRUE.equals(row.get("remake")) || role == null || !OwnMatchups.RIFT_QUEUES.contains((Integer) row.get("queueId"))) {
                    continue;
                }
                games += weight;
                positions.merge(role, weight, Double::sum);
                int key = (Integer) row.get("championKey");
                played.merge(key, 1, Integer::sum);
                weighted.merge(key, weight, Double::sum);
                wins.merge(key, Boolean.TRUE.equals(row.get("win")) ? weight : 0, Double::sum);
            }
        }
        if (games == 0) {
            return Dto.obj("status", "no-games", "items", List.of());
        }
        List<Map.Entry<Role, Double>> byGames = positions.entrySet().stream()
                .sorted(Map.Entry.<Role, Double>comparingByValue().reversed()).toList();
        Role main = byGames.getFirst().getKey();
        Role sub = byGames.size() > 1 && byGames.get(1).getValue() >= SUB_POSITION_SHARE * games ? byGames.get(1).getKey() : null;
        LolalyticsFullTierList list = lists.get(main);
        if (list == null) {
            return Dto.obj("status", "no-meta", "mainRole", main.name(), "mainRoleLabel", main.label(), "items", List.of());
        }

        Map<Integer, Candidate> candidates = new HashMap<>();
        addCandidates(candidates, main, false, list, played, weighted, wins, mastery, tags);
        if (sub != null && lists.get(sub) != null) {
            addCandidates(candidates, sub, true, lists.get(sub), played, weighted, wins, mastery, tags);
        }
        // Gegner mit negativer eigener Bilanz sind als Bann wichtiger denn als Pick: sie fallen aus den Picks.
        // Die übrigen Bans (nur Meta) werden nach den Picks ohne diese bestimmt.
        bans(rows, otherRows, main, list, notBannable(played, List.of())).stream().filter(b -> Boolean.TRUE.equals(b.get("losing")))
                .forEach(b -> candidates.remove((Integer) b.get("championKey")));
        Comparator<Candidate> best = Comparator.comparingDouble(Candidate::score).reversed().thenComparingInt(Candidate::key);
        List<Candidate> ranked = new ArrayList<>(candidates.values());
        ranked.sort(best);
        // Abwechslung: höchstens MAX_PER_CLASS Vorschläge derselben Hauptklasse, solange es Alternativen gibt.
        List<Candidate> chosen = new ArrayList<>();
        Map<String, Integer> perClass = new HashMap<>();
        for (Candidate c : ranked) {
            if (chosen.size() < COUNT && perClass.getOrDefault(c.primaryTag(), 0) < MAX_PER_CLASS) {
                chosen.add(c);
                perClass.merge(c.primaryTag(), 1, Integer::sum);
            }
        }
        for (Candidate c : ranked) {
            if (chosen.size() < COUNT && !chosen.contains(c)) {
                chosen.add(c);
            }
        }
        chosen.sort(best);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Candidate c : chosen) {
            List<Map<String, Object>> labels = new ArrayList<>();
            labels.add(c.subRole()
                    ? tag("sub_position_match", "Nebenposition passt", "Entspricht deiner Nebenposition: " + c.role().label() + ".", "neutral")
                    : tag("main_position_match", "Hauptposition passt", "Entspricht deiner Hauptposition: " + main.label() + ".", "neutral"));
            if (c.affinity() >= STYLE_MATCH) {
                labels.add(tag("archetype_fit", "Spielstil passt",
                        "Ähnlich zu Champions, die du viel und erfolgreich spielst (Klassen: "
                                + String.join(", ", c.tags().stream().map(t -> TAG_LABELS.getOrDefault(t, t)).toList()) + ").", "neutral"));
            }
            int level = mastery.getOrDefault(c.key(), 0);
            int count = played.getOrDefault(c.key(), 0);
            if (level >= FAMILIAR_LEVEL) {
                labels.add(tag("familiar", "Schon vertraut", "Meisterschaft " + level + ", in der Auswahl aber nur " + count
                        + " Spiele: ein bekannter Champion, der gerade gut passt.", "positive"));
            } else {
                labels.add(tag("new_for_summoner", "Neu für dich", "Du hast diesen Champion bisher nur wenig gespielt ("
                        + count + " Spiele in der Auswahl, Meisterschaft " + level + "), daher lohnt sich ein Versuch.", "neutral"));
            }
            if (c.honeyRank() <= HONEY_RANKS) {
                labels.add(tag("honey_champion", "Starker Pick #" + c.honeyRank(),
                        "Platz " + c.honeyRank() + " der Stärke auf " + c.role().label() + " (Lolalytics, gecacht).", "honey"));
            }
            if (c.row().banRate() < LOW_BAN_RATE) {
                labels.add(tag("low_ban_rate", "Geringer Bann-Druck",
                        "Niedrige Bannrate, der Champion ist also meist wählbar.", "positive"));
            }
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (Role role : sub == null ? List.of(main) : List.of(main, sub)) {
                LolalyticsFullTierList roleList = lists.get(role);
                LolalyticsFullTierList.Row r = roleList == null ? null : roleList.rows().get(c.key());
                if (r != null && r.laneShare() >= SUB_POSITION_SHARE) {
                    summaries.add(Dto.obj("role", role.name(), "label", role.label(), "tier", r.tier(),
                            "winRate", r.winRate(), "pickRate", r.pickRate(), "banRate", r.banRate()));
                }
            }
            items.add(Dto.obj("championKey", c.key(), "rank", items.size() + 1, "tags", labels, "positions", summaries,
                    "mastery", level, "games", count));
        }
        return Dto.obj("status", "ok", "mainRole", main.name(), "mainRoleLabel", main.label(),
                "subRole", sub == null ? null : sub.name(), "patch", list.patch(), "items", items,
                "bans", bans(rows, otherRows, main, list, notBannable(played, chosen)));
    }

    /**
     * Kandidaten einer Rolle bewerten; ein Champion, der auf beiden Positionen vorkommt, behält den besseren Wert.
     * Wert = Meta-Stärke (Prozentpunkte) + Nähe zu den eigenen erfolgreichen Champions + Vertrautheit + Bann-Druck,
     * auf der Nebenposition mit Abschlag.
     */
    private static void addCandidates(Map<Integer, Candidate> out, Role role, boolean subRole, LolalyticsFullTierList list,
                                      Map<Integer, Integer> played, Map<Integer, Double> weighted, Map<Integer, Double> wins,
                                      Map<Integer, Integer> mastery,
                                      Map<Integer, List<String>> tags) {
        Map<Integer, Double> strength = LolalyticsRoleStrength.compute(list.rows());
        List<Integer> order = strength.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        strength.forEach((key, value) -> {
            LolalyticsFullTierList.Row row = list.rows().get(key);
            if (row == null || row.tierRank() <= 0 || played.getOrDefault(key, 0) >= PLAYED_GAMES) {
                return;
            }
            List<String> champTags = tags.getOrDefault(key, List.of());
            double affinity = affinity(champTags, weighted, wins, tags);
            int level = mastery.getOrDefault(key, 0);
            double familiar = level >= FAMILIAR_LEVEL ? 0.6 : level >= 2 ? 0.2 : 0;
            double ban = (row.banRate() < LOW_BAN_RATE ? 0.3 : 0) - Math.max(0, row.banRate() - 0.15) * 5;
            double score = value + AFFINITY_WEIGHT * affinity + familiar + ban - (subRole ? SUB_ROLE_PENALTY : 0);
            Candidate c = new Candidate(key, score, row, order.indexOf(key) + 1, affinity, champTags,
                    champTags.isEmpty() ? "" : champTags.getFirst(), role, subRole);
            out.merge(key, c, (a, b) -> a.score() >= b.score() ? a : b);
        });
    }

    /**
     * Nähe eines Kandidaten zu den eigenen Champions: je gespieltem Champion die Klassen-Ähnlichkeit (gleiche Hauptklasse 1,
     * gemeinsame Nebenklasse 0,5), gewichtet mit seinen Spielen und der geglätteten Winrate (1 ± 2 · Abstand zu 50 %).
     * Ergebnis etwa 0 (nichts Ähnliches) bis 1,5 (fast nur ähnliche Champions mit hoher Winrate).
     */
    static double affinity(List<String> candidateTags, Map<Integer, Double> played, Map<Integer, Double> wins,
                           Map<Integer, List<String>> tags) {
        if (candidateTags.isEmpty()) {
            return 0;
        }
        double sum = 0;
        double weight = 0;
        for (Map.Entry<Integer, Double> e : played.entrySet()) {
            double n = e.getValue();
            double winRate = (wins.getOrDefault(e.getKey(), 0.0) + STYLE_PRIOR_GAMES / 2.0) / (n + STYLE_PRIOR_GAMES);
            List<String> own = tags.getOrDefault(e.getKey(), List.of());
            double similarity = own.isEmpty() ? 0 : own.getFirst().equals(candidateTags.getFirst()) ? 1
                    : own.stream().anyMatch(candidateTags::contains) ? 0.5 : 0;
            sum += n * similarity * (1 + 2 * (winRate - 0.5));
            weight += n;
        }
        return weight == 0 ? 0 : sum / weight;
    }

    /**
     * Empfohlene Bans für die Hauptposition: starke Lane-Gegner laut Lolalytics (gecacht), verstärkt durch die eigene
     * Bilanz gegen sie (Niederlagen minus Siege, 1,5 Pp. je Niederlage Überschuss, nur Spiele auf der Hauptposition) und ihre Bannrate.
     */
    private static List<Map<String, Object>> bans(List<Map<String, Object>> rows, List<Map<String, Object>> otherRows,
                                                  Role main, LolalyticsFullTierList list, Set<Integer> exclude) {
        Map<Integer, double[]> record = new HashMap<>(); // Spiele, Niederlagen (gewichtet)
        Map<Integer, int[]> plain = new HashMap<>(); // Siege, Niederlagen (ungewichtet, zur Anzeige)
        for (List<Map<String, Object>> source : List.of(rows, otherRows)) {
            double weight = source == rows ? 1 : OTHER_ACCOUNT_WEIGHT;
            for (Map<String, Object> row : source) {
                if (Boolean.TRUE.equals(row.get("remake")) || roleOf((String) row.get("position")) != main
                        || !OwnMatchups.RIFT_QUEUES.contains((Integer) row.get("queueId"))
                        || !(row.get("opponent") instanceof Map<?, ?> opponent) || !(opponent.get("key") instanceof Integer key)) {
                    continue;
                }
                boolean win = Boolean.TRUE.equals(row.get("win"));
                double[] r = record.computeIfAbsent(key, k -> new double[2]);
                r[0] += weight;
                r[1] += win ? 0 : weight;
                plain.computeIfAbsent(key, k -> new int[2])[win ? 0 : 1]++;
            }
        }
        Map<Integer, Double> strength = LolalyticsRoleStrength.compute(list.rows());
        List<Integer> order = strength.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        Map<Integer, Double> score = new HashMap<>();
        strength.forEach((key, value) -> {
            LolalyticsFullTierList.Row row = list.rows().get(key);
            if (row == null || row.tierRank() <= 0 || exclude.contains(key)) {
                return;
            }
            double[] r = record.getOrDefault(key, new double[2]);
            double netLosses = 2 * r[1] - r[0]; // Niederlagen minus Siege (gewichtet)
            score.put(key, value + Math.max(BAN_MIN_RECORD, BAN_NET_LOSS_WEIGHT * netLosses) + BAN_RATE_WEIGHT * row.banRate());
        });
        List<Integer> best = score.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(BAN_COUNT).map(Map.Entry::getKey).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int key : best) {
            LolalyticsFullTierList.Row row = list.rows().get(key);
            int honey = order.indexOf(key) + 1;
            List<Map<String, Object>> labels = new ArrayList<>();
            int[] p = plain.getOrDefault(key, new int[2]);
            if (p[0] + p[1] > 0) {
                labels.add(tag("ban_record", "Deine Bilanz " + p[0] + ":" + p[1], "Deine Spiele gegen diesen Lane-Gegner auf "
                        + main.label() + ": " + p[0] + " Siege, " + p[1] + " Niederlagen.", p[1] > p[0] ? "negative" : "neutral"));
            }
            if (honey <= HONEY_RANKS) {
                labels.add(tag("ban_strong", "Starker Gegner #" + honey,
                        "Platz " + honey + " der Stärke auf " + main.label() + " (Lolalytics, gecacht).", "honey"));
            }
            if (row.banRate() >= HIGH_BAN_RATE) {
                labels.add(tag("ban_popular", "Häufig gebannt", "Bannrate " + Math.round(row.banRate() * 100) + " %.", "neutral"));
            }
            out.add(Dto.obj("championKey", key, "rank", out.size() + 1, "tags", labels, "losing", p[1] > p[0],
                    "positions", List.of(Dto.obj("role", main.name(), "label", main.label(), "tier", row.tier(),
                            "winRate", row.winRate(), "pickRate", row.pickRate(), "banRate", row.banRate()))));
        }
        return out;
    }

    /** Nicht als Bann vorschlagen: eigene, schon gespielte Champions und die gewählten Picks. */
    private static Set<Integer> notBannable(Map<Integer, Integer> played, List<Candidate> chosen) {
        Set<Integer> out = new HashSet<>();
        chosen.forEach(c -> out.add(c.key()));
        played.forEach((key, n) -> {
            if (n >= PLAYED_GAMES) {
                out.add(key);
            }
        });
        return out;
    }

    static Role roleOf(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        for (Role role : Role.values()) {
            if (role.label().equals(label) || role.name().equals(label.toUpperCase(Locale.ROOT))) {
                return role;
            }
        }
        return null;
    }

    private static Map<String, Object> tag(String code, String label, String tip, String tone) {
        return Dto.obj("code", code, "label", label, "tip", tip, "tone", tone);
    }

    private record Candidate(int key, double score, LolalyticsFullTierList.Row row, int honeyRank, double affinity,
                             List<String> tags, String primaryTag, Role role, boolean subRole) {
    }
}
