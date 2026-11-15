package com.leaguetool.data.riot;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.model.Tier;

import java.io.IOException;
import java.util.List;

/**
 * Zugriff auf die benötigten Riot-API-Endpunkte. Implementierungen kümmern sich um API-Key,
 * Routing, Rate-Limits (20 req/s, 100 req/2 min) und Wiederholung bei HTTP 429.
 * Fehlerstatus wird als {@link RiotApiException} gemeldet.
 */
public interface RiotApi {

    /** league-v4 Queue-Name für Ranked Solo/Duo. */
    String RANKED_SOLO_QUEUE = "RANKED_SOLO_5x5";
    /** match-v5 Queue-ID für Ranked Solo/Duo. */
    int RANKED_SOLO_QUEUE_ID = 420;

    Platform platform();

    /**
     * league-v4 {@code /lol/league/v4/entries/{queue}/{tier}/{division}?page=N} (nur IRON … DIAMOND).
     *
     * @param division "I", "II", "III" oder "IV"
     * @param page     1-basiert; eine leere Liste bedeutet: keine weiteren Seiten
     */
    List<LeagueEntry> leagueEntries(String queue, Tier tier, String division, int page) throws IOException;

    /** league-v4 Apex-Ligen ({@code challengerleagues/grandmasterleagues/masterleagues/by-queue/{queue}}). */
    List<LeagueEntry> apexLeague(String queue, Tier tier) throws IOException;

    /** summoner-v4 {@code /lol/summoner/v4/summoners/{encryptedSummonerId}} → PUUID (Fallback für alte Einträge). */
    String puuidBySummonerId(String summonerId) throws IOException;

    /** account-v1 {@code /riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}}. */
    RiotAccount accountByRiotId(String gameName, String tagLine) throws IOException;

    /**
     * match-v5 {@code /lol/match/v5/matches/by-puuid/{puuid}/ids}.
     *
     * @param queueId           z. B. {@link #RANKED_SOLO_QUEUE_ID}
     * @param count             1..100
     * @param startTimeEpochSec nur Matches ab diesem Zeitpunkt (Epoch-Sekunden) oder {@code null}
     */
    List<String> matchIdsByPuuid(String puuid, int queueId, int start, int count, Long startTimeEpochSec)
            throws IOException;

    /** match-v5 {@code /lol/match/v5/matches/{matchId}} (rohes MatchDto). */
    JsonNode match(String matchId) throws IOException;

    /** match-v5 {@code /lol/match/v5/matches/{matchId}/timeline} (rohes TimelineDto). */
    JsonNode timeline(String matchId) throws IOException;

    /** lol-status-v4 {@code /lol/status/v4/platform-data} – günstiger Aufruf zur Key-Prüfung. */
    JsonNode platformStatus() throws IOException;
}
