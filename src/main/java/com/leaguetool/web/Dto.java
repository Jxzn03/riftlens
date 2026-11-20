package com.leaguetool.web;

import com.leaguetool.calc.BuildTotals;
import com.leaguetool.calc.CooldownCalculator;
import com.leaguetool.calc.DamageInput;
import com.leaguetool.calc.FinalStats;
import com.leaguetool.calc.ItemPassives;
import com.leaguetool.calc.ItemStatParser;
import com.leaguetool.calc.RuneEffects;
import com.leaguetool.data.PatchTracker;
import com.leaguetool.data.runeeffects.RuneEffectUpdater;
import com.leaguetool.calc.Scaling;
import com.leaguetool.calc.ScalingStat;
import com.leaguetool.calc.StatBlock;
import com.leaguetool.calc.StatCalculator;
import com.leaguetool.calc.StatLabels;
import com.leaguetool.calc.StatType;
import com.leaguetool.data.GameData;
import com.leaguetool.data.UpdateReport;
import com.leaguetool.data.itempassives.ItemPassiveUpdater;
import com.leaguetool.data.lolalytics.LolalyticsBuild;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.lolalytics.LolalyticsCounters;
import com.leaguetool.data.lolalytics.LolalyticsFullTierList;
import com.leaguetool.data.lolalytics.LolalyticsLateItems;
import com.leaguetool.data.wiki.WikiAbility;
import com.leaguetool.data.wiki.WikiChampionData;
import com.leaguetool.data.wiki.WikiLeveling;
import com.leaguetool.data.wiki.WikiPatchNotes;
import com.leaguetool.data.wiki.WikiRatio;
import com.leaguetool.model.Ability;
import com.leaguetool.model.AbilitySlot;
import com.leaguetool.model.BaseStats;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionDetail;
import com.leaguetool.model.Item;
import com.leaguetool.model.Role;
import com.leaguetool.model.Rune;
import com.leaguetool.model.RuneTree;
import com.leaguetool.model.StatShard;
import com.leaguetool.model.SummonerSpell;
import com.leaguetool.stats.ChampionReport;
import com.leaguetool.stats.RunePageKey;
import com.leaguetool.stats.SkillPath;
import com.leaguetool.stats.StatLine;
import com.leaguetool.stats.StatRanking;
import com.leaguetool.stats.WinStats;
import com.leaguetool.util.AppVersion;
import com.leaguetool.util.Text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Wandelt Modelle in die JSON-Struktur der Oberfläche um. */
final class Dto {

    /** So viele Einträge zeigt eine Rangliste. */
    private static final int TOP = 3;
    /** Boots stehen in zwei Spalten, daher doppelt so viele Einträge im selben Platz. */
    private static final int TOP_BOOTS = 6;
    /** Core-Build: drei Builds plus Meistgespielt; steht dieser unter den dreien, rückt der vierte nach. */
    private static final int TOP_CORE = 4;
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

    private static Map<String, Object> updateReport(UpdateReport r) {
        return obj("patch", r.patch(), "checkedAt", r.checkedAt(), "count", r.count(),
                "changes", r.changes(), "review", r.review(), "notes", r.notes());
    }

    /** Ein Papierkorb-Eintrag: {@code item} plus Lösch- und Ablaufzeitpunkt (Draft-Archiv und Fearless-Serien). */
    static Map<String, Object> trashed(Map<String, Object> item, Instant deletedAt, Instant expiresAt) {
        item.put("deletedAt", deletedAt.toString());
        item.put("expiresAt", expiresAt.toString());
        return item;
    }

    // ------------------------------------------------------------------ Bilder

    static String patchIcon(String patch, String group, String file) {
        return "/img/" + patch + "/" + group + "/" + file;
    }

    private static String runeIcon(String icon) {
        return "/img/rune/" + icon;
    }

    // ------------------------------------------------------------------ Bootstrap

    static Map<String, Object> bootstrap(GameData data, String locale, int version, UpdateReport passives,
                                         UpdateReport runes, PatchTracker.Change patchChange) {
        String patch = data.patch();
        List<Item> riftItems = Item.summonersRiftItems(data.items());
        return obj(
                "version", version,
                "appVersion", AppVersion.get(),
                "patch", patch,
                "offline", data.offline(),
                "patchChange", patchChange == null ? null : obj("from", patchChange.previous(), "to", patchChange.current(),
                        "at", patchChange.changedAt().toString()),
                "itemPassives", updateReport(passives),
                "runeEffectStatus", updateReport(runes),
                "champions", data.champions().stream().map(c -> champion(c, patch)).toList(),
                "items", data.items().stream().map(i -> item(i, patch, riftItems.contains(i))).toList(),
                "statNames", statNames(locale),
                "runeTrees", data.runeTrees().stream().map(Dto::runeTree).toList(),
                "spells", data.summonerSpells().stream().map(s -> spell(s, patch)).toList(),
                "shards", StatShard.ALL.stream().map(s -> obj("id", s.id(), "name", s.name())).toList(),
                "shardRows", RuneEffects.shardRows(),
                "runeEffects", RuneEffects.all().stream().map(e -> obj("id", e.id(), "kind", e.kind().name().toLowerCase(Locale.ROOT),
                        "maxStacks", e.maxStacks(), "stackLabel", e.stackLabel(), "summary", e.summary())).toList());
    }

    /** Stat-Namen in der Data-Dragon-Sprache je {@link StatType}-Name: {@code label} (voll) und {@code short}. */
    static Map<String, Object> statNames(String locale) {
        Map<String, Object> names = new LinkedHashMap<>();
        for (StatType type : StatType.values()) {
            names.put(type.name(), obj("label", StatLabels.label(type, locale), "short", StatLabels.shortLabel(type, locale)));
        }
        return names;
    }

    // ------------------------------------------------------------------ Patch-Notes (Wiki)

    /**
     * Summoner's-Rift-Abschnitte eines Patches; {@code patch} ist der Wiki-Titel ({@code V26.23}).
     *
     * @param abilityIcon Icon-URL zu „Champion|Fähigkeit“ oder {@code null}
     */
    static Map<String, Object> patchNotes(String patch, String version, List<WikiPatchNotes.Section> sections, boolean stale,
                                          Function<String, String> abilityIcon) {
        return obj("patch", patch, "version", version, "stale", stale,
                "sections", sections.stream().map(s -> obj("title", s.title(), "kind", s.kind(), "groups", s.groups().stream()
                        .map(g -> obj("heading", g.heading(), "kind", g.kind(), "ref", g.ref(),
                                "lines", patchLines(g.lines(), abilityIcon)))
                        .toList())).toList());
    }

    /** Patch-Historie eines Champions, neuester Patch zuerst; {@code current} = aktueller Patch ({@code V26.23}). */
    static Map<String, Object> patchHistory(List<WikiPatchNotes.Entry> entries, String current, boolean stale,
                                            Function<String, String> abilityIcon) {
        return obj("current", current, "stale", stale,
                "entries", entries.stream().map(e -> obj("patch", e.patch(), "hotfix", e.hotfix(),
                        "lines", patchLines(e.lines(), abilityIcon))).toList());
    }

    /** Zeilen mit Teilen ({@code style}, {@code ref}, bei Fähigkeiten {@code icon}) und Trend ({@code buff|nerf}). */
    private static List<Map<String, Object>> patchLines(List<WikiPatchNotes.Line> lines, Function<String, String> abilityIcon) {
        return lines.stream().map(l -> obj("depth", l.depth(), "text", l.text(), "trend", l.trend(),
                "parts", l.parts().stream().map(p -> {
                    Map<String, Object> part = obj("text", p.text(), "style", p.style(), "ref", p.ref());
                    if ("ability".equals(p.style()) && p.ref() != null) {
                        part.put("icon", abilityIcon.apply(p.ref()));
                    }
                    return part;
                }).toList())).toList();
    }

    /** Icon-URL einer Fähigkeit (Data Dragon), wie in den Champion-Details. */
    static String abilityIcon(Ability a, String patch) {
        return patchIcon(patch, a.isPassive() ? "passive" : "spell", a.imageFile());
    }

    private static Map<String, Object> champion(Champion c, String patch) {
        return obj(
                "id", c.id(), "key", c.key(), "name", c.name(), "title", c.title(), "blurb", c.blurb(),
                "resource", c.resource(),
                "info", obj("attack", c.info().attack(), "defense", c.info().defense(), "magic", c.info().magic()),
                "icon", patchIcon(patch, "champion", c.imageFile()),
                "splash", "/img/splash/" + c.id() + "_0.jpg");
    }

    private static Map<String, Object> item(Item i, String patch, boolean rift) {
        return obj(
                "id", i.id(), "name", i.name(), "plaintext", i.plaintext(),
                "description", Text.htmlToPlain(i.descriptionHtml()),
                "gold", obj("base", i.gold().base(), "total", i.gold().total(), "sell", i.gold().sell()),
                "tags", i.tags(), "from", i.from(), "into", i.into(),
                "rift", rift, "completed", i.isCompleted(),
                "icon", patchIcon(patch, "item", i.imageFile()),
                "stats", statRows(ItemStatParser.parse(i).stats()),
                // Immer aktive Item-Passiven aus dem Build-Rechner (Wert in Prozent bzw. flach wie {@link StatType}).
                "passives", ItemPassives.of(i.id()).stream()
                        .map(p -> obj("name", p.passive(), "type", p.type().name(), "value", p.value())).toList());
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
                "classic", s.isClassic(), "icon", patchIcon(patch, "spell", s.imageFile()));
    }

    /**
     * Wertezeilen in Enum-Reihenfolge: {@code key} (Name des {@link StatType}, eindeutig auch bei gleichem Label),
     * {@code label}, {@code value} (formatiert), {@code amount} (Zahl zum Sortieren), {@code percent}.
     */
    static List<Map<String, Object>> statRows(StatBlock block) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<StatType, Double> e : block.entries()) {
            StatType type = e.getKey();
            rows.add(obj("key", type.name(), "label", type.label(), "value", type.formatValue(e.getValue()),
                    "amount", e.getValue(), "percent", type.percent()));
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
                "costType", a.costType(), "icon", patchIcon(patch, group, a.imageFile()),
                "ranks", ranks, "cooldowns", a.cooldowns(), "cooldownTexts", cooldownTexts,
                "cooldownSummary", a.cooldowns().isEmpty() ? "" : "Abklingzeit: " + Text.perRank(a.cooldowns()) + " s",
                "costs", costs, "ranges", ranges,
                "hasRankTable", !a.cooldowns().isEmpty() || !a.costs().isEmpty() || !a.ranges().isEmpty());
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

    /** Grundwerte nur aus Data Dragon (ohne Wiki). */
    static List<Map<String, Object>> baseRows(Champion c) {
        return baseRows(c, null);
    }

    /**
     * Grundwerte als Zeilen; jede Zeile enthält die Werte aller 18 Level (der Regler braucht keine Anfrage). Mit
     * Wiki-Daten gelten deren Werte (Data Dragon ist bei einzelnen Werten ungenau); weicht Data Dragon ab, nennt
     * {@code dd} dessen Wert. Danach folgen die Zusatzdaten aus dem Wiki (Angriffstempo-Ratio, Windup).
     */
    static List<Map<String, Object>> baseRows(Champion c, WikiChampionData w) {
        BaseStats s = c.stats();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(statRow("Leben", s.hp(), s.hpPerLevel(), w == null ? null : new double[] {w.hpBase(), w.hpLvl()}));
        rows.add(statRow("Lebensreg. (5 s)", s.hpRegen(), s.hpRegenPerLevel(), w == null ? null : new double[] {w.hp5Base(), w.hp5Lvl()}));
        if (s.mp() > 0) {
            String res = c.resource().isBlank() ? "Ressource" : c.resource();
            rows.add(statRow(res, s.mp(), s.mpPerLevel(), w == null ? null : new double[] {w.mpBase(), w.mpLvl()}));
            rows.add(statRow(res + "-Reg. (5 s)", s.mpRegen(), s.mpRegenPerLevel(), w == null ? null : new double[] {w.mp5Base(), w.mp5Lvl()}));
        }
        rows.add(statRow("Angriffsschaden", s.attackDamage(), s.attackDamagePerLevel(), w == null ? null : new double[] {w.damBase(), w.damLvl()}));
        boolean wikiAs = w != null && w.asBase() > 0;
        double asBase = wikiAs ? w.asBase() : s.attackSpeed();
        double asLvl = wikiAs ? w.asLvl() : s.attackSpeedPerLevel();
        List<String> attackSpeed = new ArrayList<>();
        for (int level = StatCalculator.MIN_LEVEL; level <= StatCalculator.MAX_LEVEL; level++) {
            attackSpeed.add(Text.num(StatCalculator.attackSpeedAtLevel(asBase, asLvl, level), 3));
        }
        Map<String, Object> as = obj("label", "Angriffstempo", "base", Text.num(asBase),
                "perLevel", "+" + Text.num(asLvl) + " %", "values", attackSpeed);
        if (differs(s.attackSpeed(), s.attackSpeedPerLevel(), asBase, asLvl)) {
            as.put("dd", Text.num(s.attackSpeed()) + " / +" + Text.num(s.attackSpeedPerLevel()) + " %");
        }
        rows.add(as);
        rows.add(statRow("Rüstung", s.armor(), s.armorPerLevel(), w == null ? null : new double[] {w.armBase(), w.armLvl()}));
        rows.add(statRow("Magieresistenz", s.spellBlock(), s.spellBlockPerLevel(), w == null ? null : new double[] {w.mrBase(), w.mrLvl()}));
        rows.add(statRow("Reichweite", s.attackRange(), 0, w == null ? null : new double[] {w.range(), 0}));
        rows.add(statRow("Lauftempo", s.moveSpeed(), 0, w == null ? null : new double[] {w.ms(), 0}));
        if (w != null) {
            rows.add(fixedRow("Angriffstempo-Ratio", Text.num(w.asRatio())));
            w.windupPercent().ifPresent(p -> rows.add(fixedRow("Windup", Text.percent(p))));
        }
        return rows;
    }

    /** Zusatzzeilen für „Werte mit Build“ (Angriffstempo-Ratio, Windup); leer ohne Wiki. */
    static List<Map<String, Object>> wikiExtraRows(WikiChampionData d) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (d != null) {
            rows.add(row("Angriffstempo-Ratio", Text.num(d.asRatio()), ""));
            d.windupPercent().ifPresent(p -> rows.add(row("Windup", Text.percent(p), "")));
        }
        return rows;
    }

    /** Zusatzangaben aus dem Wiki unter der Tabelle (Positionen, adaptiver Typ, Nah-/Fernkampf), leer ohne. */
    static String wikiInfo(WikiChampionData d) {
        if (d == null) {
            return "";
        }
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
        return String.join(" · ", info);
    }

    private static Map<String, Object> fixedRow(String label, String value) {
        return obj("label", label, "base", value, "perLevel", "–",
                "values", java.util.Collections.nCopies(StatCalculator.MAX_LEVEL - StatCalculator.MIN_LEVEL + 1, value));
    }

    private static boolean differs(double ddBase, double ddLvl, double base, double lvl) {
        return Math.abs(ddBase - base) > 1e-6 || Math.abs(ddLvl - lvl) > 1e-6;
    }

    /** @param wiki Basis und Wert pro Level laut Wiki oder {@code null}; gilt vor Data Dragon */
    private static Map<String, Object> statRow(String label, double ddBase, double ddLvl, double[] wiki) {
        // Fehlt der Wert im Wiki (0 bei vorhandenem Data-Dragon-Wert), gilt Data Dragon.
        boolean useWiki = wiki != null && !(wiki[0] == 0 && ddBase != 0);
        double base = useWiki ? wiki[0] : ddBase;
        double perLevel = useWiki ? wiki[1] : ddLvl;
        Map<String, Object> row = statRow(label, base, perLevel);
        if (differs(ddBase, ddLvl, base, perLevel)) {
            row.put("dd", Text.num(ddBase) + (ddLvl == 0 && perLevel == 0 ? "" : " / +" + Text.num(ddLvl)));
        }
        return row;
    }

    private static Map<String, Object> statRow(String label, double base, double perLevel) {
        List<String> values = new ArrayList<>();
        for (int level = StatCalculator.MIN_LEVEL; level <= StatCalculator.MAX_LEVEL; level++) {
            values.add(Text.num(StatCalculator.statAtLevel(base, perLevel, level), 1));
        }
        return obj("label", label, "base", Text.num(base), "perLevel", perLevel == 0 ? "–" : "+" + Text.num(perLevel),
                "values", values);
    }

    /** Endwerte eines Levels mit dem Build. */
    static List<Map<String, Object>> finalRows(FinalStats f) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(finalRow("Leben", f.maxHealth(), f.bonusHealth(), 0));
        rows.add(row("Lebensreg. (5 s)", Text.num(f.healthRegen(), 1),
                regenBonus(f, StatType.HEALTH_REGEN, StatType.HEALTH_REGEN_FLAT)));
        if (f.maxMana() > 0) {
            rows.add(finalRow("Mana", f.maxMana(), f.bonusMana(), 0));
            rows.add(row("Mana-Reg. (5 s)", Text.num(f.manaRegen(), 1),
                    regenBonus(f, StatType.MANA_REGEN, StatType.MANA_REGEN_FLAT)));
        }
        rows.add(finalRow("Angriffsschaden", f.totalAd(), f.bonusAd(), 1));
        rows.add(finalRow("Fähigkeitsstärke", f.abilityPower(), f.abilityPower(), 0));
        rows.add(finalRow("Rüstung", f.armor(), f.bonusArmor(), 1));
        rows.add(finalRow("Magieresistenz", f.magicResist(), f.bonusMagicResist(), 1));
        String as = Text.num(f.attackSpeed(), 3) + (f.attackSpeedRaw() > f.attackSpeed() + 1e-9
                ? " (roh " + Text.num(f.attackSpeedRaw(), 3) + ", gekappt)" : "");
        rows.add(row("Angriffstempo", as, "+" + Text.num(f.bonusAttackSpeed(), 1) + " %"));
        rows.add(row("Reichweite", Text.num(f.attackRange()), ""));
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

    /** Bonus der Regeneration aus dem Build: Prozent der Grundregeneration und flache Werte, sonst „–“. */
    private static String regenBonus(FinalStats f, StatType percent, StatType flat) {
        List<String> parts = new ArrayList<>();
        if (f.bonus().get(percent) != 0) {
            parts.add("+" + Text.num(f.bonus().get(percent)) + " % Grund");
        }
        if (f.bonus().get(flat) != 0) {
            parts.add("+" + Text.num(f.bonus().get(flat), 1));
        }
        return parts.isEmpty() ? "–" : String.join(", ", parts);
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

    private static Map<String, Object> statLine(StatLine<?> l) {
        Map<String, Object> m = win(l);
        m.put("pickRate", l.pickRate());
        return m;
    }

    private static Map<String, Object> report(ChampionReport r) {
        Map<String, Object> m = win(r);
        m.put("pickRate", r.pickRate());
        m.put("banRate", r.banRate());
        m.put("coreBuilds", ranking(r.coreBuilds(), ids -> ids, TOP_CORE));
        m.put("boots", ranking(r.boots(), List::of, TOP_BOOTS, true));
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

    /** Kurznamen der Shards für die Runenseiten der Match-Statistik; die übrigen behalten ihren Namen. */
    private static final Map<Integer, String> SHARD_LABELS = Map.of(5008, "Adaptive", 5001, "HP-Scaling",
            5013, "Tenacity", 5011, "HP-Flat");

    static String shardLabel(int id) {
        return SHARD_LABELS.getOrDefault(id, StatShard.nameOf(id));
    }

    private static Map<String, Object> runePage(RunePageKey key) {
        return obj("primary", key.primaryPerks(), "sub", key.subPerks(), "shardIds", key.shards(),
                "shards", key.shards().stream().map(Dto::shardLabel).collect(Collectors.joining(" / ")));
    }

    /** Bevorzugt die besten Einträge (Bayes-Untergrenze, Mindest-Stichprobe), sonst die häufigsten. */
    private static <K> Map<String, Object> ranking(StatRanking<K> ranking, Function<K, Object> keyView) {
        return ranking(ranking, keyView, TOP);
    }

    private static <K> Map<String, Object> ranking(StatRanking<K> ranking, Function<K, Object> keyView, int limit) {
        return ranking(ranking, keyView, limit, false);
    }

    /**
     * @param fill füllt freie Plätze hinter den besten mit den häufigsten Einträgen unter der Mindest-Stichprobe auf;
     *             sie tragen {@code belowMin} und werden ausgegraut angezeigt
     */
    static <K> Map<String, Object> ranking(StatRanking<K> ranking, Function<K, Object> keyView, int limit,
                                           boolean fill) {
        boolean best = !ranking.best().isEmpty();
        List<StatLine<K>> lines = best ? ranking.best() : ranking.mostFrequent();
        Function<StatLine<K>, Map<String, Object>> view = line -> {
            Map<String, Object> m = statLine(line);
            m.put("key", keyView.apply(line.key()));
            return m;
        };
        List<Map<String, Object>> shown = new ArrayList<>(lines.stream().limit(limit).map(view).toList());
        if (fill && best) {
            Set<K> listed = lines.stream().map(StatLine::key).collect(Collectors.toSet());
            ranking.mostFrequent().stream()
                    .filter(line -> line.games() > 0 && !listed.contains(line.key()))
                    .limit(Math.max(0, limit - shown.size()))
                    .forEach(line -> {
                        Map<String, Object> m = view.apply(line);
                        m.put("belowMin", true);
                        shown.add(m);
                    });
        }
        // Meistgespielter Eintrag unabhängig von der Winrate; der Core-Build zeigt ihn unter den besten.
        return obj("best", best, "minGames", ranking.minGames(), "lines", shown,
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

    /** Lolalytics-Lane → Beschriftung der Rolle (z. B. {@code middle} → „Mid“). */
    private static final Map<String, String> LANE_LABELS = Arrays.stream(Role.values())
            .collect(Collectors.toUnmodifiableMap(LolalyticsClient::lane, Role::label));

    /** Beschriftung einer Lolalytics-Lane oder {@code null}, wenn sie keiner Rolle entspricht. */
    static String laneLabel(String lane) {
        return lane == null ? null : LANE_LABELS.get(lane);
    }

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
    static Map<String, Object> lolalyticsReport(LolalyticsBuild b, GameData data,
                                                String opponentLane) {
        List<StatLine<Integer>> boots = new ArrayList<>();
        for (LolalyticsBuild.Entry e : b.boots()) {
            if (e.key().matches("\\d+") && data.item(Integer.parseInt(e.key())).filter(Item::isFinalBoots).isPresent()) {
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
        ChampionReport report = new ChampionReport(b.games(),
                (int) Math.min(b.games(), Math.round(b.games() * b.winRate())), b.pickRate(), b.banRate(),
                ranking(b.starters(), Dto::idList, min),
                StatRanking.of(boots, min),
                ranking(b.coreBuilds(), Dto::idList, min),
                StatRanking.of(keystones, min),
                StatRanking.of(pages.values(), min),
                ranking(b.skillOrders(), Dto::maxOrder, min),
                ranking(b.skillStarts(), Dto::skillDigits, min),
                ranking(b.spells(), Dto::idList, min));
        Map<String, Object> json = report(report);
        json.put("winRate", b.winRate());   // exakt wie geliefert, nicht aus gerundeten Siegen zurückgerechnet
        json.put("counters", counters(LolalyticsCounters.of(b.matchups(), opponentLane, min, b.winRate())));
        json.put("skillPath", slots(bestSkillPath(report)));
        json.put("lateItems", lateItems(b, report));
        json.put("minGames", min);   // Mindest-Stichprobe aller Ranglisten, Anzeige im Kartenkopf
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
