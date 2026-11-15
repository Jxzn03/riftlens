package com.leaguetool.web;

import com.leaguetool.calc.BuildTotals;
import com.leaguetool.calc.CooldownCalculator;
import com.leaguetool.calc.DamageInput;
import com.leaguetool.calc.FinalStats;
import com.leaguetool.calc.ItemStatParser;
import com.leaguetool.calc.RuneEffects;
import com.leaguetool.data.PatchTracker;
import com.leaguetool.data.runeeffects.RuneEffectUpdater;
import com.leaguetool.calc.Scaling;
import com.leaguetool.calc.ScalingStat;
import com.leaguetool.calc.StatBlock;
import com.leaguetool.calc.StatCalculator;
import com.leaguetool.calc.StatType;
import com.leaguetool.data.GameData;
import com.leaguetool.data.ddragon.ImageRef;
import com.leaguetool.data.itempassives.ItemPassiveUpdater;
import com.leaguetool.data.lolalytics.LolalyticsBuild;
import com.leaguetool.data.lolalytics.LolalyticsCounters;
import com.leaguetool.data.lolalytics.LolalyticsFullTierList;
import com.leaguetool.data.lolalytics.LolalyticsLateItems;
import com.leaguetool.data.wiki.WikiAbility;
import com.leaguetool.data.wiki.WikiChampionData;
import com.leaguetool.data.wiki.WikiLeveling;
import com.leaguetool.data.wiki.WikiRatio;
import com.leaguetool.model.Ability;
import com.leaguetool.model.AbilitySlot;
import com.leaguetool.model.BaseStats;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionDetail;
import com.leaguetool.model.Item;
import com.leaguetool.model.Rune;
import com.leaguetool.model.RuneTree;
import com.leaguetool.model.StatShard;
import com.leaguetool.model.SummonerSpell;
import com.leaguetool.stats.ChampionReport;
import com.leaguetool.stats.ItemClassifier;
import com.leaguetool.stats.RunePageKey;
import com.leaguetool.stats.SkillPath;
import com.leaguetool.stats.StatLine;
import com.leaguetool.stats.StatRanking;
import com.leaguetool.stats.WinStats;
import com.leaguetool.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Wandelt Modelle in die JSON-Struktur der Oberfläche um. */
final class Dto {

    /** So viele Einträge zeigt eine Rangliste. */
    private static final int TOP = 3;
    private static final int MAX_LEVEL = StatCalculator.MAX_LEVEL;
    private static final List<String> PRIMARY_MODES = List.of("CLASSIC", "ARAM", "URF", "CHERRY");
    private static final int SHORT_MODES = 3;

    private Dto() {
    }

    /** Geordnetes JSON-Objekt aus abwechselnd Schlüssel und Wert. */
    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------ Bilder

    private static String championIcon(String patch, String file) {
        return "/img/" + patch + "/champion/" + file;
    }

    private static String runeIcon(String icon) {
        return "/img/rune/" + icon;
    }

    // ------------------------------------------------------------------ Bootstrap

    static Map<String, Object> bootstrap(GameData data, int version, ItemPassiveUpdater.Report passives,
                                         RuneEffectUpdater.Report runes, PatchTracker.Change patchChange) {
        String patch = data.patch();
        List<Item> riftItems = Item.summonersRiftItems(data.items());
        return obj(
                "version", version,
                "patch", patch,
                "offline", data.offline(),
                "patchChange", patchChange == null ? null : obj("from", patchChange.previous(), "to", patchChange.current(),
                        "at", patchChange.changedAt().toString()),
                "itemPassives", obj("patch", passives.patch(), "checkedAt", passives.checkedAt(), "count", passives.count(),
                        "changes", passives.changes(), "review", passives.review(), "notes", passives.notes()),
                "runeEffectStatus", obj("patch", runes.patch(), "checkedAt", runes.checkedAt(), "count", runes.count(),
                        "changes", runes.changes(), "review", runes.review(), "notes", runes.notes()),
                "champions", data.champions().stream().map(c -> champion(c, patch)).toList(),
                "items", data.items().stream().map(i -> item(i, patch, riftItems.contains(i))).toList(),
                "runeTrees", data.runeTrees().stream().map(Dto::runeTree).toList(),
                "spells", data.summonerSpells().stream().map(s -> spell(s, patch)).toList(),
                "shards", StatShard.ALL.stream().map(s -> obj("id", s.id(), "name", s.name())).toList(),
                "shardRows", RuneEffects.shardRows(),
                "runeEffects", RuneEffects.all().stream().map(e -> obj("id", e.id(), "kind", e.kind().name().toLowerCase(),
                        "maxStacks", e.maxStacks(), "stackLabel", e.stackLabel(), "summary", e.summary())).toList());
    }

    private static Map<String, Object> champion(Champion c, String patch) {
        return obj(
                "id", c.id(), "key", c.key(), "name", c.name(), "title", c.title(), "blurb", c.blurb(),
                "resource", c.resource(),
                "info", obj("attack", c.info().attack(), "defense", c.info().defense(), "magic", c.info().magic()),
                "icon", championIcon(patch, c.imageFile()),
                "splash", "/img/splash/" + c.id() + "_0.jpg");
    }

    private static Map<String, Object> item(Item i, String patch, boolean rift) {
        return obj(
                "id", i.id(), "name", i.name(), "plaintext", i.plaintext(),
                "description", Text.htmlToPlain(i.descriptionHtml()),
                "gold", obj("base", i.gold().base(), "total", i.gold().total(), "sell", i.gold().sell()),
                "tags", i.tags(), "from", i.from(), "into", i.into(),
                "rift", rift, "completed", i.isCompleted(),
                "icon", "/img/" + patch + "/item/" + i.imageFile(),
                "stats", statRows(ItemStatParser.parse(i).stats()));
    }

    private static Map<String, Object> runeTree(RuneTree t) {
        return obj("id", t.id(), "key", t.key(), "name", t.name(), "icon", runeIcon(t.icon()),
                "slots", t.slots().stream().map(row -> row.stream().map(Dto::rune).toList()).toList());
    }

    private static Map<String, Object> rune(Rune r) {
        String longText = Text.htmlToPlain(r.longDesc());
        String shortText = Text.htmlToPlain(r.shortDesc());
        return obj("id", r.id(), "key", r.key(), "name", r.name(), "short", shortText,
                "text", longText.isBlank() ? shortText : longText, "icon", runeIcon(r.icon()),
                "treeId", r.treeId(), "slot", r.slot());
    }

    private static Map<String, Object> spell(SummonerSpell s, String patch) {
        List<String> modes = new ArrayList<>(s.modes());
        modes.sort(Comparator.comparingInt((String m) -> {
            int i = PRIMARY_MODES.indexOf(m);
            return i < 0 ? PRIMARY_MODES.size() : i;
        }).thenComparing(Comparator.naturalOrder()));
        String head = modes.stream().limit(SHORT_MODES).collect(Collectors.joining(", "));
        return obj("id", s.id(), "key", s.key(), "name", s.name(), "description", s.description(),
                "cooldown", s.cooldown(), "cooldownText", s.cooldowns().isEmpty() ? "–" : Text.perRank(s.cooldowns()) + " s",
                "rangeText", Text.ranges(s.ranges()), "summonerLevel", s.summonerLevel(), "modes", modes,
                "modesShort", modes.size() > SHORT_MODES ? head + " +" + (modes.size() - SHORT_MODES) : head,
                "classic", s.isClassic(), "icon", "/img/" + patch + "/spell/" + s.imageFile());
    }

    private static List<Map<String, Object>> statRows(StatBlock block) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<StatType, Double> e : block.entries()) {
            rows.add(obj("label", e.getKey().label(), "value", e.getKey().formatValue(e.getValue())));
        }
        return rows;
    }

    // ------------------------------------------------------------------ Champion-Details

    static Map<String, Object> championDetail(ChampionDetail detail, String patch) {
        List<Map<String, Object>> abilities = new ArrayList<>();
        for (AbilitySlot slot : AbilitySlot.values()) {
            detail.ability(slot).ifPresent(a -> abilities.add(ability(a, patch)));
        }
        return obj("abilities", abilities);
    }

    private static Map<String, Object> ability(Ability a, String patch) {
        ImageRef ref = a.isPassive() ? ImageRef.passive(patch, a.imageFile()) : ImageRef.spell(patch, a.imageFile());
        String group = a.isPassive() ? "passive" : "spell";
        int ranks = Math.max(1, Math.max(Math.max(a.maxRank(), a.cooldowns().size()),
                Math.max(a.costs().size(), a.ranges().size())));
        boolean free = a.costType().isBlank() && a.costs().stream().allMatch(v -> v == 0.0);
        List<String> costs = new ArrayList<>();
        List<String> ranges = new ArrayList<>();
        List<String> cooldownTexts = new ArrayList<>();
        for (int i = 0; i < ranks; i++) {
            costs.add(free || i >= a.costs().size() ? "–" : Text.num(a.costs().get(i)));
            ranges.add(i < a.ranges().size() ? Text.range(a.ranges().get(i)) : "–");
            cooldownTexts.add(i < a.cooldowns().size() ? Text.num(a.cooldowns().get(i)) : "–");
        }
        return obj("slot", a.slot().name(), "slotLabel", a.slot().label(), "name", a.name(),
                "description", a.description(), "passive", a.isPassive(), "maxRank", a.maxRank(),
                "costType", a.costType(), "icon", "/img/" + patch + "/" + group + "/" + fileName(ref),
                "ranks", ranks, "cooldowns", a.cooldowns(), "cooldownTexts", cooldownTexts,
                "cooldownSummary", a.cooldowns().isEmpty() ? "" : "Abklingzeit: " + Text.perRank(a.cooldowns()) + " s",
                "costs", costs, "ranges", ranges,
                "hasRankTable", !a.cooldowns().isEmpty() || !a.costs().isEmpty() || !a.ranges().isEmpty());
    }

    private static String fileName(ImageRef ref) {
        return ref.cacheKey().substring(ref.cacheKey().lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------------ Wiki-Vergleich

    static Map<String, Object> wiki(Champion c, WikiChampionData d) {
        BaseStats s = c.stats();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(compare("Leben / Level", s.hp(), s.hpPerLevel(), d.hpBase(), d.hpLvl()));
        rows.add(compare("Angriffsschaden / Level", s.attackDamage(), s.attackDamagePerLevel(), d.damBase(), d.damLvl()));
        rows.add(compare("Rüstung / Level", s.armor(), s.armorPerLevel(), d.armBase(), d.armLvl()));
        rows.add(compare("Magieresistenz / Level", s.spellBlock(), s.spellBlockPerLevel(), d.mrBase(), d.mrLvl()));
        rows.add(compare("Angriffstempo / % pro Level", s.attackSpeed(), s.attackSpeedPerLevel(), d.asBase(), d.asLvl()));
        rows.add(obj("label", "Angriffstempo-Ratio", "dd", "–", "wiki", Text.num(d.asRatio()), "differs", false));
        rows.add(obj("label", "Windup", "dd", "–", "wiki", d.windupPercent().map(Text::percent).orElse("–"), "differs", false));
        rows.add(obj("label", "Reichweite", "dd", Text.num(s.attackRange()), "wiki", Text.num(d.range()), "differs", false));
        List<String> info = new ArrayList<>();
        if (!d.positions().isEmpty()) {
            info.add("Positionen: " + String.join(", ", d.positions()));
        }
        if (!d.adaptiveType().isBlank()) {
            info.add("Adaptiv: " + d.adaptiveType());
        }
        if (!d.rangeType().isBlank()) {
            info.add(d.rangeType());
        }
        return obj("found", true, "rows", rows, "info", String.join(" · ", info), "asRatio", d.asRatio());
    }

    private static Map<String, Object> compare(String label, double ddBase, double ddLvl, double wikiBase, double wikiLvl) {
        boolean differs = Math.abs(ddBase - wikiBase) > 1e-6 || Math.abs(ddLvl - wikiLvl) > 1e-6;
        return obj("label", label, "dd", Text.num(ddBase) + " / +" + Text.num(ddLvl),
                "wiki", Text.num(wikiBase) + " / +" + Text.num(wikiLvl), "differs", differs);
    }

    // ------------------------------------------------------------------ Werte je Level

    /** Grundwerte als Zeilen; jede Zeile enthält die Werte aller 18 Level (der Regler braucht keine Anfrage). */
    static List<Map<String, Object>> baseRows(Champion c) {
        BaseStats s = c.stats();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(statRow("Leben", s.hp(), s.hpPerLevel()));
        rows.add(statRow("Lebensreg. (5 s)", s.hpRegen(), s.hpRegenPerLevel()));
        if (s.mp() > 0) {
            String res = c.resource().isBlank() ? "Ressource" : c.resource();
            rows.add(statRow(res, s.mp(), s.mpPerLevel()));
            rows.add(statRow(res + "-Reg. (5 s)", s.mpRegen(), s.mpRegenPerLevel()));
        }
        rows.add(statRow("Angriffsschaden", s.attackDamage(), s.attackDamagePerLevel()));
        List<String> attackSpeed = new ArrayList<>();
        for (int level = 1; level <= MAX_LEVEL; level++) {
            attackSpeed.add(Text.num(StatCalculator.attackSpeedAtLevel(s.attackSpeed(), s.attackSpeedPerLevel(), level), 3));
        }
        rows.add(obj("label", "Angriffstempo", "base", Text.num(s.attackSpeed()),
                "perLevel", "+" + Text.num(s.attackSpeedPerLevel()) + " %", "values", attackSpeed));
        rows.add(statRow("Rüstung", s.armor(), s.armorPerLevel()));
        rows.add(statRow("Magieresistenz", s.spellBlock(), s.spellBlockPerLevel()));
        rows.add(statRow("Reichweite", s.attackRange(), 0));
        rows.add(statRow("Lauftempo", s.moveSpeed(), 0));
        return rows;
    }

    private static Map<String, Object> statRow(String label, double base, double perLevel) {
        List<String> values = new ArrayList<>();
        for (int level = 1; level <= MAX_LEVEL; level++) {
            values.add(Text.num(StatCalculator.statAtLevel(base, perLevel, level), 1));
        }
        return obj("label", label, "base", Text.num(base), "perLevel", perLevel == 0 ? "–" : "+" + Text.num(perLevel),
                "values", values);
    }

    /** Endwerte eines Levels mit dem Build. */
    static List<Map<String, Object>> finalRows(FinalStats f) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(finalRow("Leben", f.maxHealth(), f.bonusHealth(), 0));
        if (f.maxMana() > 0) {
            rows.add(finalRow("Mana", f.maxMana(), f.bonusMana(), 0));
        }
        rows.add(finalRow("Angriffsschaden", f.totalAd(), f.bonusAd(), 1));
        rows.add(finalRow("Fähigkeitsstärke", f.abilityPower(), f.abilityPower(), 0));
        rows.add(finalRow("Rüstung", f.armor(), f.bonusArmor(), 1));
        rows.add(finalRow("Magieresistenz", f.magicResist(), f.bonusMagicResist(), 1));
        String as = Text.num(f.attackSpeed(), 3) + (f.attackSpeedRaw() > f.attackSpeed() + 1e-9
                ? " (roh " + Text.num(f.attackSpeedRaw(), 3) + ", gekappt)" : "");
        rows.add(row("Angriffstempo", as, "+" + Text.num(f.bonusAttackSpeed(), 1) + " %"));
        rows.add(row("Lauftempo", Text.num(f.moveSpeed(), 1),
                f.moveSpeedRaw() != f.moveSpeed() ? "roh " + Text.num(f.moveSpeedRaw(), 1) : ""));
        rows.add(row("Ability Haste", hasteText(f.abilityHaste()), Text.percent(f.cooldownReduction()) + " CDR"));
        addSpecificHaste(rows, "Haste Grundfähigkeiten", f, StatType.BASIC_ABILITY_HASTE);
        addSpecificHaste(rows, "Haste Ultimate", f, StatType.ULTIMATE_HASTE);
        addSpecificHaste(rows, "Haste Immobilisierungen", f, StatType.IMMOBILIZE_ABILITY_HASTE);
        if (f.summonerSpellHaste() > 0) {
            double h = f.summonerSpellHaste();
            rows.add(row("Haste Beschwörerzauber", Text.num(h), Text.percent(h / (100 + h)) + " kürzer"));
        }
        rows.add(row("Krit-Chance", Text.num(f.critChance()) + " %", ""));
        if (f.healShieldPower() > 0) {
            rows.add(row("Heil- und Schildstärke", Text.num(f.healShieldPower()) + " %", ""));
        }
        addPercentRow(rows, "Lebensraub", f, StatType.LIFE_STEAL);
        addPercentRow(rows, "Zähigkeit", f, StatType.TENACITY);
        addPercentRow(rows, "Verlangsamungsresistenz", f, StatType.SLOW_RESIST);
        rows.add(row("Eff. Leben (physisch)", Text.num(f.effectiveHealthPhysical(), 0), ""));
        rows.add(row("Eff. Leben (magisch)", Text.num(f.effectiveHealthMagic(), 0), ""));
        return rows;
    }

    private static void addSpecificHaste(List<Map<String, Object>> rows, String label, FinalStats f, StatType type) {
        if (f.bonus().has(type)) {
            double total = f.abilityHaste() + f.bonus().get(type);
            rows.add(row(label, hasteText(total), Text.percent(CooldownCalculator.cooldownReduction(total)) + " CDR"));
        }
    }

    /** Prozentwert aus Items und Runen, nur wenn vorhanden. */
    private static void addPercentRow(List<Map<String, Object>> rows, String label, FinalStats f, StatType type) {
        if (f.bonus().has(type)) {
            rows.add(row(label, Text.num(f.bonus().get(type)) + " %", ""));
        }
    }

    /** Haste mit Hinweis, wenn der Cap von 500 greift. */
    private static String hasteText(double haste) {
        return haste > CooldownCalculator.HASTE_CAP
                ? Text.num(haste) + " (gekappt auf " + Text.num(CooldownCalculator.HASTE_CAP) + ")" : Text.num(haste);
    }

    /** Fähigkeitstempo des Builds je Slot, inklusive der Boni nur für bestimmte Fähigkeiten (gekappt auf 500). */
    static Map<String, Double> hasteBySlot(List<WikiAbility> abilities, FinalStats stats) {
        Map<String, Double> haste = new LinkedHashMap<>();
        for (AbilitySlot slot : AbilitySlot.values()) {
            boolean immobilizes = abilities.stream().anyMatch(a -> a.slot() == slot && a.immobilizes());
            haste.put(slot.name(), stats.abilityHaste(slot, immobilizes));
        }
        return haste;
    }

    /**
     * Slots, deren Abklingzeit laut Wiki statisch ist (alle Wiki-Fähigkeiten des Slots nur mit {@code static}). Haste
     * verringert sie nicht.
     */
    static List<String> staticCooldownSlots(List<WikiAbility> abilities) {
        List<String> slots = new ArrayList<>();
        for (AbilitySlot slot : AbilitySlot.values()) {
            List<WikiAbility> inSlot = abilities.stream().filter(a -> a.slot() == slot).toList();
            if (!inSlot.isEmpty() && inSlot.stream().allMatch(WikiAbility::staticCooldown)) {
                slots.add(slot.name());
            }
        }
        return slots;
    }

    private static Map<String, Object> finalRow(String label, double total, double bonus, int decimals) {
        return row(label, Text.num(total, decimals), bonus == 0 ? "–" : "+" + Text.num(bonus, decimals));
    }

    private static Map<String, Object> row(String label, String total, String bonus) {
        return obj("label", label, "total", total, "bonus", bonus);
    }

    static Map<String, Object> buildTotals(BuildTotals totals) {
        return obj("itemCount", totals.itemCount(), "gold", totals.totalGold(),
                "stats", statRows(totals.stats()), "unparsed", totals.unparsed());
    }

    // ------------------------------------------------------------------ Wiki-Fähigkeiten und Schadensvorschau

    static Map<String, List<Map<String, Object>>> wikiAbilities(List<WikiAbility> abilities, FinalStats stats) {
        Map<String, List<Map<String, Object>>> bySlot = new LinkedHashMap<>();
        for (WikiAbility a : abilities) {
            bySlot.computeIfAbsent(a.slot().name(), s -> new ArrayList<>()).add(wikiAbility(a, stats));
        }
        return bySlot;
    }

    private static Map<String, Object> wikiAbility(WikiAbility a, FinalStats stats) {
        List<String> facts = new ArrayList<>();
        addFact(facts, "Abklingzeit", a.cooldownText());
        addFact(facts, "Kosten", (a.costText() + " " + a.costType()).strip());
        addFact(facts, "Reichweite", a.rangeText());
        addFact(facts, "Zauberzeit", a.castTime());
        addFact(facts, "Schadensart", a.damageType());
        int ranks = a.levelings().stream().mapToInt(l -> l.valuesPerRank().size()).max().orElse(0);
        return obj("name", a.name(), "facts", String.join(" · ", facts), "ranks", ranks,
                "levelings", a.levelings().stream().map(l -> leveling(l, stats)).toList());
    }

    private static void addFact(List<String> facts, String label, String value) {
        if (value != null && !value.isBlank()) {
            facts.add(label + ": " + value);
        }
    }

    private static Map<String, Object> leveling(WikiLeveling l, FinalStats stats) {
        if (l.valuesPerRank().isEmpty()) {
            return obj("label", l.label(), "text", l.text());
        }
        List<String> values = l.valuesPerRank().stream().map(Text::num).toList();
        List<String> ratioParts = new ArrayList<>();
        for (WikiRatio r : l.ratios()) {
            ratioParts.add("+ " + Text.perRank(r.percentsPerRank()) + " % " + r.stat());
        }
        List<String> previews = new ArrayList<>();
        boolean complete = true;
        if (!l.ratios().isEmpty()) {
            for (int rank = 0; rank < l.valuesPerRank().size(); rank++) {
                DamageInput input = DamageInput.of(stats, rank + 1);
                double value = l.valuesPerRank().get(rank);
                for (WikiRatio ratio : l.ratios()) {
                    Optional<ScalingStat> stat = ScalingStat.parse(ratio.stat());
                    if (stat.isEmpty() || ratio.percentsPerRank().isEmpty()) {
                        complete = false;
                        continue;
                    }
                    double pct = ratio.percentsPerRank().get(Math.min(rank, ratio.percentsPerRank().size() - 1));
                    value += new Scaling(pct / 100.0, stat.get()).contribution(input);
                }
                previews.add(Text.num(value, 1));
            }
        }
        return obj("label", l.label(), "values", values, "ratios", String.join(", ", ratioParts),
                "previews", previews, "complete", complete);
    }

    // ------------------------------------------------------------------ Statistik

    private static Map<String, Object> win(WinStats s) {
        return obj("games", s.games(), "wins", s.wins(), "winRate", s.winRate(),
                "lower", s.wilsonLower(), "upper", s.wilsonUpper());
    }

    static Map<String, Object> statLine(StatLine<?> l) {
        Map<String, Object> m = win(l);
        m.put("pickRate", l.pickRate());
        return m;
    }

    static Map<String, Object> report(ChampionReport r) {
        Map<String, Object> m = win(r);
        m.put("pickRate", r.pickRate());
        m.put("banRate", r.banRate());
        m.put("coreBuilds", ranking(r.coreBuilds(), ids -> ids));
        m.put("boots", ranking(r.boots(), List::of));
        m.put("starters", ranking(r.starters(), ids -> ids));
        m.put("keystones", ranking(r.keystones(), List::of));
        m.put("runePages", ranking(r.runePages(), Dto::runePage));
        m.put("skillOrders", ranking(r.skillOrders(), Dto::slots));
        m.put("skillStarts", ranking(r.skillStarts(), Dto::slots));
        m.put("spellCombos", ranking(r.spellCombos(), ids -> ids));
        return m;
    }

    private static List<String> slots(List<AbilitySlot> order) {
        return order.stream().map(AbilitySlot::label).toList();
    }

    private static Map<String, Object> runePage(RunePageKey key) {
        return obj("primary", key.primaryPerks(), "sub", key.subPerks(), "shardIds", key.shards(),
                "shards", key.shards().stream().map(StatShard::nameOf).collect(Collectors.joining(" / ")));
    }

    /** Bevorzugt die besten Einträge (Wilson-Untergrenze, Mindest-Stichprobe), sonst die häufigsten. */
    private static <K> Map<String, Object> ranking(StatRanking<K> ranking, Function<K, Object> keyView) {
        boolean best = !ranking.best().isEmpty();
        List<StatLine<K>> lines = best ? ranking.best() : ranking.mostFrequent();
        Function<StatLine<K>, Map<String, Object>> view = line -> {
            Map<String, Object> m = statLine(line);
            m.put("key", keyView.apply(line.key()));
            return m;
        };
        // Meistgespielter Eintrag unabhängig von der Winrate; der Core-Build zeigt ihn unter den besten.
        return obj("best", best, "minGames", ranking.minGames(),
                "lines", lines.stream().limit(TOP).map(view).toList(),
                "mostPlayed", ranking.top().map(view).orElse(null));
    }

    // ------------------------------------------------------------------ Lolalytics

    /**
     * Mindestanteil an den Spielen des Champions für die "besten" Einträge. Die Wilson-Untergrenze allein reicht bei
     * Millionen Spielen nicht: Sonst stehen Nischen-Builds oben, die nur in ohnehin gewonnenen Spielen entstehen.
     */
    private static final double LOLALYTICS_MIN_SHARE = 0.01;
    private static final int LOLALYTICS_MIN_GAMES = 100;
    private static final int LATE_ITEMS = 8;

    private static final Map<String, String> LANE_LABELS = Map.of("top", "Top", "jungle", "Jungle", "middle", "Mid",
            "bottom", "ADC", "support", "Support");

    /** Zeile der Champions-Tabelle. */
    static Map<String, Object> lolalyticsChampionStat(LolalyticsFullTierList.Row row, Double strength) {
        Map<String, Object> m = win(line(row.lane(), row.winRate(), row.games(), row.pickRate()));
        m.put("winRate", row.winRate());   // exakt wie geliefert, nicht aus gerundeten Siegen zurückgerechnet
        m.put("pickRate", row.pickRate());
        m.put("banRate", row.banRate());
        m.put("pbi", row.tierRank() == 0 ? null : row.pbi());   // ohne Tier liefert die Seite keinen PBI, nur 0
        m.put("strength", strength);
        m.put("tier", row.tier());
        m.put("tierRank", row.tierRank() == 0 ? null : row.tierRank());
        m.put("lane", LANE_LABELS.getOrDefault(row.lane(), row.lane()));
        return m;
    }

    private static <K> StatLine<K> line(K key, double winRate, int games, double pickRate) {
        return new StatLine<>(key, games, (int) Math.min(games, Math.round(games * winRate)), pickRate);
    }

    /** Gleiche Form wie {@link #report(ChampionReport)}, damit die Oberfläche beide Quellen gleich anzeigt. */
    static Map<String, Object> lolalyticsReport(LolalyticsBuild b, GameData data, ItemClassifier classifier, int championKey,
                                                String opponentLane) {
        List<StatLine<Integer>> boots = new ArrayList<>();
        for (LolalyticsBuild.Entry e : b.boots()) {
            if (e.key().matches("\\d+") && classifier.isFinalBoots(Integer.parseInt(e.key()))) {
                boots.add(line(Integer.valueOf(e.key()), e.winRate(), e.games(), e.pickRate()));
            }
        }
        List<StatLine<Integer>> keystones = new ArrayList<>();
        b.runes().forEach((id, e) -> {
            if (data.rune(id).filter(Rune::isKeystone).isPresent()) {
                keystones.add(line(id, e.winRate(), e.games(), e.pickRate()));
            }
        });
        Map<RunePageKey, StatLine<RunePageKey>> pages = new LinkedHashMap<>();
        for (LolalyticsBuild.RunePage p : b.runePages()) {
            int primary = p.primary().isEmpty() ? 0 : data.rune(p.primary().get(0)).map(Rune::treeId).orElse(0);
            int sub = p.sub().isEmpty() ? 0 : data.rune(p.sub().get(0)).map(Rune::treeId).orElse(0);
            RunePageKey key = new RunePageKey(primary, sub, p.primary(), p.sub(), p.shards());
            pages.putIfAbsent(key, line(key, p.winRate(), p.games(), b.games() == 0 ? 0 : (double) p.games() / b.games()));
        }
        int min = Math.max(LOLALYTICS_MIN_GAMES, (int) Math.ceil(b.games() * LOLALYTICS_MIN_SHARE));
        ChampionReport report = new ChampionReport(championKey, min, b.games(),
                (int) Math.min(b.games(), Math.round(b.games() * b.winRate())), b.pickRate(), b.banRate(),
                ranking(b.starters(), Dto::idList, min),
                StatRanking.of(boots, min),
                ranking(b.coreBuilds(), Dto::idList, min),
                StatRanking.of(List.of(), min), StatRanking.of(List.of(), min), StatRanking.of(List.of(), min),
                StatRanking.of(keystones, min),
                StatRanking.of(pages.values(), min),
                StatRanking.of(List.of(), min), StatRanking.of(List.of(), min), StatRanking.of(List.of(), min),
                ranking(b.skillOrders(), Dto::maxOrder, min),
                ranking(b.skillStarts(), Dto::skillDigits, min),
                ranking(b.spells(), Dto::idList, min));
        Map<String, Object> json = report(report);
        json.put("winRate", b.winRate());   // exakt wie geliefert, nicht aus gerundeten Siegen zurückgerechnet
        json.put("counters", counters(LolalyticsCounters.of(b.matchups(), opponentLane, min, b.winRate())));
        json.put("skillPath", slots(bestSkillPath(report)));
        json.put("lateItems", lateItems(b, report));
        return json;
    }

    /** Häufigste Items auf den Itemplätzen nach dem Core-Build; was schon im angezeigten Core-Build steckt, fällt weg. */
    private static List<Map<String, Object>> lateItems(LolalyticsBuild b, ChampionReport r) {
        List<Integer> core = r.coreBuilds().topBest().or(r.coreBuilds()::top).map(StatLine::key).orElse(List.of());
        return LolalyticsLateItems.of(b.itemSlots(), core, LOLALYTICS_MIN_GAMES, LATE_ITEMS, b.games()).stream()
                .map(l -> obj("item", l.item(), "winRate", l.winRate(), "pickRate", l.pickRate(), "games", l.games(),
                        "slots", l.slots().stream().map(s -> obj("slot", s.slot(), "games", s.games())).toList()))
                .toList();
    }

    /** Dieselben Zeilen, die auch die Ranglisten oben anzeigen. */
    private static List<AbilitySlot> bestSkillPath(ChampionReport r) {
        Optional<StatLine<List<AbilitySlot>>> start = r.skillStarts().topBest().or(r.skillStarts()::top);
        Optional<StatLine<List<AbilitySlot>>> order = r.skillOrders().topBest().or(r.skillOrders()::top);
        return start.isEmpty() || order.isEmpty() ? List.of() : SkillPath.firstLevels(start.get().key(), order.get().key());
    }

    /** Counters aus Sicht des Gegners: beste und schlechteste Picks gegen den Champion (siehe {@link LolalyticsCounters}). */
    private static Map<String, Object> counters(LolalyticsCounters c) {
        return obj("lane", LANE_LABELS.getOrDefault(c.lane(), c.lane()), "minGames", c.minGames(),
                "best", counterLines(c.best()), "worst", counterLines(c.worst()), "delta", counterLines(c.delta()));
    }

    private static List<Map<String, Object>> counterLines(List<LolalyticsCounters.Line> lines) {
        return lines.stream().map(l -> obj("champion", l.champion(), "winRate", l.winRate(), "games", l.games(),
                "delta1", l.delta1(), "delta2", l.delta2(), "rating", l.rating(), "pick", l.pick())).toList();
    }

    private static <K> StatRanking<K> ranking(List<LolalyticsBuild.Entry> entries, Function<String, K> key, int minGames) {
        List<StatLine<K>> lines = new ArrayList<>();
        for (LolalyticsBuild.Entry e : entries) {
            try {
                lines.add(line(key.apply(e.key()), e.winRate(), e.games(), e.pickRate()));
            } catch (IllegalArgumentException unknownFormat) {
                // Einzelne Zeile in unbekanntem Format auslassen, statt die ganze Statistik zu verlieren.
            }
        }
        return StatRanking.of(lines, minGames);
    }

    /** {@code 1056_2003_2003} → Liste der IDs. */
    private static List<Integer> idList(String key) {
        return Arrays.stream(key.split("_")).map(Integer::valueOf).toList();
    }

    /** {@code QWE} → Fähigkeiten in Max-Reihenfolge. */
    private static List<AbilitySlot> maxOrder(String key) {
        return key.chars().mapToObj(c -> AbilitySlot.valueOf(String.valueOf((char) c))).toList();
    }

    /** {@code 213114} → Fähigkeiten in Skill-Reihenfolge (1 = Q … 4 = R). */
    private static List<AbilitySlot> skillDigits(String key) {
        return key.chars().mapToObj(c -> AbilitySlot.fromSkillSlot(c - '0')).toList();
    }
}
