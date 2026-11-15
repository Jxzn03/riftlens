package com.leaguetool.data.riot;

import com.leaguetool.model.Tier;

/**
 * Eintrag aus league-v4 (Spieler in einer Rangliste).
 *
 * @param puuid       PUUID (neuere API-Versionen liefern sie direkt; kann {@code null} sein)
 * @param summonerId  verschlüsselte Summoner-ID (veraltet; kann {@code null} sein)
 * @param queueType   z. B. {@code RANKED_SOLO_5x5}
 * @param rank        Division ("I" … "IV"); bei Apex-Tiers "I"
 */
public record LeagueEntry(String puuid, String summonerId, String queueType, Tier tier, String rank,
                          int leaguePoints, int wins, int losses) {
}
