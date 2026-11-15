package com.leaguetool.data.riot;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.GameData;
import com.leaguetool.model.StatShard;
import com.leaguetool.model.Tier;
import com.leaguetool.util.Patches;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

/**
 * Verifiziert die statischen Daten (Data Dragon) mit einem echten, aktuellen Ranked-Match: Key-Prüfung,
 * Patch-Vergleich und ob alle Champion-, Item-, Summoner-Spell- und Runen-IDs des Matches bekannt sind.
 * Bei einem Auth-Problem (401/403) oder ohne erreichbares Match werden die weiteren Prüfungen übersprungen.
 */
public final class RiotVerifier {

    public static final String CHECK_KEY = "API-Key";
    public static final String CHECK_MATCH = "Aktuelles Match";
    public static final String CHECK_PATCH = "Patch";
    public static final String CHECK_CHAMPIONS = "Champions";
    public static final String CHECK_ITEMS = "Items";
    public static final String CHECK_SPELLS = "Summoner Spells";
    public static final String CHECK_RUNES = "Runen";

    /** Höchstens so viele Spieler werden nach einem aktuellen Match gefragt. */
    private static final int MAX_PLAYERS = 3;
    private static final String[] ITEM_FIELDS = {"item0", "item1", "item2", "item3", "item4", "item5", "item6"};
    private static final String[] STAT_PERK_FIELDS = {"offense", "flex", "defense"};

    /** Führt alle Prüfungen aus. Wirft keine Exceptions; Fehler erscheinen als fehlgeschlagene Prüfungen. */
    public VerificationReport verify(RiotApi api, GameData data) {
        List<VerificationReport.Check> checks = new ArrayList<>();
        try {
            api.platformStatus();
            checks.add(ok(CHECK_KEY, "Key gültig (Plattform " + api.platform().name() + ")"));
        } catch (IOException e) {
            checks.add(fail(CHECK_KEY, describe(e)));
            return new VerificationReport(checks);
        }

        JsonNode match;
        try {
            match = findRecentMatch(api);
        } catch (IOException e) {
            checks.add(fail(CHECK_MATCH, "Kein Match abrufbar: " + describe(e)));
            return new VerificationReport(checks);
        }
        if (match == null) {
            checks.add(fail(CHECK_MATCH, "Kein aktuelles Ranked-Match gefunden (Challenger-Liste leer oder ohne Matches)"));
            return new VerificationReport(checks);
        }
        String matchId = match.path("metadata").path("matchId").asText("?");
        checks.add(ok(CHECK_MATCH, "Match " + matchId + " mit " + match.path("info").path("participants").size()
                + " Teilnehmern"));
        checks.addAll(verifyMatch(match, data));
        return new VerificationReport(checks);
    }

    /** Prüft ein MatchDto (match-v5) gegen die statischen Daten: Patch, Champions, Items, Spells, Runen. */
    public List<VerificationReport.Check> verifyMatch(JsonNode match, GameData data) {
        List<VerificationReport.Check> checks = new ArrayList<>();
        JsonNode info = match.path("info");

        String gameVersion = info.path("gameVersion").asText("");
        String dataPatch = Patches.shortPatch(data.patch());
        if (gameVersion.isBlank()) {
            checks.add(fail(CHECK_PATCH, "Match enthält keine gameVersion"));
        } else {
            String matchPatch = Patches.shortPatch(gameVersion);
            String detail = "Match " + matchPatch + " (" + gameVersion + "), Daten " + dataPatch + " (" + data.patch() + ")";
            checks.add(matchPatch.equals(dataPatch) ? ok(CHECK_PATCH, detail) : fail(CHECK_PATCH, detail));
        }

        JsonNode participants = info.path("participants");
        if (!participants.isArray() || participants.isEmpty()) {
            String detail = "Match enthält keine Teilnehmer";
            checks.add(fail(CHECK_CHAMPIONS, detail));
            checks.add(fail(CHECK_ITEMS, detail));
            checks.add(fail(CHECK_SPELLS, detail));
            checks.add(fail(CHECK_RUNES, detail));
            return checks;
        }

        List<Integer> champions = new ArrayList<>();
        List<Integer> items = new ArrayList<>();
        List<Integer> spells = new ArrayList<>();
        List<Integer> trees = new ArrayList<>();
        List<Integer> runes = new ArrayList<>();
        List<Integer> shards = new ArrayList<>();
        for (JsonNode p : participants) {
            champions.add(p.path("championId").asInt(0));
            for (String field : ITEM_FIELDS) {
                int id = p.path(field).asInt(0);
                if (id != 0) {
                    items.add(id);
                }
            }
            spells.add(p.path("summoner1Id").asInt(0));
            spells.add(p.path("summoner2Id").asInt(0));
            JsonNode perks = p.path("perks");
            for (JsonNode style : perks.path("styles")) {
                trees.add(style.path("style").asInt(0));
                for (JsonNode sel : style.path("selections")) {
                    runes.add(sel.path("perk").asInt(0));
                }
            }
            for (String field : STAT_PERK_FIELDS) {
                if (perks.path("statPerks").has(field)) {
                    shards.add(perks.path("statPerks").path(field).asInt(0));
                }
            }
        }

        checks.add(idCheck(CHECK_CHAMPIONS, "Champions", champions, id -> data.championByKey(id).isPresent()));
        checks.add(idCheck(CHECK_ITEMS, "Items", items, id -> data.item(id).isPresent()));
        checks.add(idCheck(CHECK_SPELLS, "Summoner Spells", spells, id -> data.summonerSpell(id).isPresent()));

        Set<Integer> unknownTrees = unknown(trees, id -> data.runeTree(id).isPresent());
        Set<Integer> unknownRunes = unknown(runes, id -> data.rune(id).isPresent());
        Set<Integer> unknownShards = unknown(shards, id -> StatShard.byId(id).isPresent());
        if (unknownTrees.isEmpty() && unknownRunes.isEmpty() && unknownShards.isEmpty()) {
            checks.add(ok(CHECK_RUNES, "Alle " + (trees.size() + runes.size() + shards.size())
                    + " Runen-IDs bekannt (Bäume, Runen, Shards)"));
        } else {
            List<String> parts = new ArrayList<>();
            if (!unknownTrees.isEmpty()) {
                parts.add("Bäume " + join(unknownTrees));
            }
            if (!unknownRunes.isEmpty()) {
                parts.add("Runen " + join(unknownRunes));
            }
            if (!unknownShards.isEmpty()) {
                parts.add("Shards " + join(unknownShards));
            }
            checks.add(fail(CHECK_RUNES, "Unbekannte IDs: " + String.join("; ", parts)));
        }
        return checks;
    }

    /**
     * Erster Challenger-Spieler (bis zu {@value #MAX_PLAYERS}) mit einem Ranked-Solo-Match. Schlägt die Abfrage
     * für einen Spieler fehl, wird der nächste versucht; ein Auth-Problem bricht sofort ab.
     *
     * @return Match oder {@code null}, wenn kein Spieler ein Match hat
     * @throws IOException wenn die Liga nicht abrufbar ist oder alle versuchten Spieler fehlschlagen
     */
    private static JsonNode findRecentMatch(RiotApi api) throws IOException {
        List<LeagueEntry> entries = api.apexLeague(RiotApi.RANKED_SOLO_QUEUE, Tier.CHALLENGER);
        int asked = 0;
        IOException lastError = null;
        for (LeagueEntry entry : entries) {
            if (asked >= MAX_PLAYERS) {
                break;
            }
            boolean hasPuuid = entry.puuid() != null && !entry.puuid().isBlank();
            if (!hasPuuid && (entry.summonerId() == null || entry.summonerId().isBlank())) {
                continue;
            }
            asked++;
            try {
                String puuid = hasPuuid ? entry.puuid() : api.puuidBySummonerId(entry.summonerId());
                List<String> ids = api.matchIdsByPuuid(puuid, RiotApi.RANKED_SOLO_QUEUE_ID, 0, 1, null);
                if (!ids.isEmpty()) {
                    return api.match(ids.get(0));
                }
            } catch (IOException e) {
                if (e instanceof RiotApiException r && r.isAuthProblem()) {
                    throw e;
                }
                lastError = e;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private static VerificationReport.Check idCheck(String name, String label, List<Integer> ids,
                                                    IntPredicate known) {
        Set<Integer> unknown = unknown(ids, known);
        if (unknown.isEmpty()) {
            return ok(name, "Alle " + ids.size() + " " + label + "-IDs bekannt");
        }
        return fail(name, "Unbekannte IDs: " + join(unknown));
    }

    private static Set<Integer> unknown(List<Integer> ids, IntPredicate known) {
        Set<Integer> out = new TreeSet<>();
        for (int id : ids) {
            if (!known.test(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static String join(Set<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String describe(IOException e) {
        if (e instanceof RiotApiException r && r.isAuthProblem()) {
            return r.getMessage();
        }
        if (e instanceof RiotApiException r) {
            return "Riot-API-Fehler: " + r.getMessage();
        }
        return "Riot-API nicht erreichbar: " + e.getMessage();
    }

    private static VerificationReport.Check ok(String name, String detail) {
        return new VerificationReport.Check(name, true, detail);
    }

    private static VerificationReport.Check fail(String name, String detail) {
        return new VerificationReport.Check(name, false, detail);
    }
}
