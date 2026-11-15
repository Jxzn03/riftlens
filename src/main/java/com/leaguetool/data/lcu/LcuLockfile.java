package com.leaguetool.data.lcu;

import java.util.Optional;

/**
 * Zugang zum laufenden League-Client aus seiner Datei {@code lockfile} ({@code LeagueClient:pid:port:passwort:https}).
 * Das Passwort gilt nur für diese Client-Sitzung und verlässt das Backend nie.
 */
public record LcuLockfile(int port, String password) {

    @Override
    public String toString() {
        return "LcuLockfile[port=" + port + "]";
    }

    /** Liest den Inhalt einer {@code lockfile}; leer, wenn er nicht dem bekannten Format entspricht. */
    public static Optional<LcuLockfile> parse(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String[] parts = content.trim().split(":");
        if (parts.length < 5 || !"https".equals(parts[4])) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(parts[2]);
            String password = parts[3];
            if (port <= 0 || port > 65_535 || password.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new LcuLockfile(port, password));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
