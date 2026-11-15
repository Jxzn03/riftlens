package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.lcu.ChampSelect;
import com.leaguetool.data.lcu.LcuSync;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/lcu} liefert den Stand des Abgleichs mit dem League-Client, {@code POST /api/lcu} mit
 * {@code {"enabled": true|false}} schaltet ihn um. Änderungen kommen zusätzlich als Ereignis {@code lcu}.
 */
final class ClientSyncEndpoint {

    private ClientSyncEndpoint() {
    }

    static Object get(WebContext ctx) {
        return json(ctx.lcu().state());
    }

    /** Schaltet um und speichert den Stand; der nächste Start der App übernimmt ihn. */
    static Object set(WebContext ctx, JsonNode body) throws IOException {
        JsonNode enabled = body.path("enabled");
        if (!enabled.isBoolean()) {
            throw new ApiException(400, "Feld enabled (true/false) fehlt");
        }
        ctx.settings().setLcuSyncEnabled(enabled.asBoolean());
        ctx.settings().save();
        ctx.lcu().setEnabled(enabled.asBoolean());
        return json(ctx.lcu().state());
    }

    /** Antwortform; enthält nur Champion-IDs, Rollen und Zustände, nie Namen oder Konto-Kennungen. */
    static Map<String, Object> json(LcuSync.State state) {
        ChampSelect cs = state.champSelect();
        return Dto.obj(
                "status", state.status().name(),
                "message", state.message(),
                "champSelect", cs == null ? null : Dto.obj(
                        "gameId", cs.gameId(),
                        "phase", cs.phase(),
                        "ally", members(cs.ally()),
                        "enemy", members(cs.enemy()),
                        "bans", cs.bans()),
                "pickable", state.pickable());
    }

    private static List<Map<String, Object>> members(List<ChampSelect.Member> members) {
        return members.stream().map(m -> Dto.obj(
                "cellId", m.cellId(),
                "champion", m.champion(),
                "intent", m.intent(),
                "role", m.role() == null ? null : m.role().name(),
                "local", m.local(),
                "picking", m.picking())).toList();
    }
}
