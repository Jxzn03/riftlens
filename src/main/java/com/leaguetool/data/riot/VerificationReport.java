package com.leaguetool.data.riot;

import java.util.List;
import java.util.Optional;

/**
 * Ergebnis von {@link RiotVerifier#verify(RiotApi, com.leaguetool.data.GameData)}.
 *
 * @param checks einzelne Prüfungen in Ausführungsreihenfolge
 */
public record VerificationReport(List<Check> checks) {

    public VerificationReport {
        checks = List.copyOf(checks);
    }

    /**
     * Eine Prüfung.
     *
     * @param name   kurze Bezeichnung, z. B. "Items"
     * @param ok     bestanden?
     * @param detail Erläuterung, bei Fehlschlag z. B. die unbekannten IDs
     */
    public record Check(String name, boolean ok, String detail) {
    }

    /** {@code true}, wenn es mindestens eine Prüfung gibt und alle bestanden sind. */
    public boolean allOk() {
        return !checks.isEmpty() && checks.stream().allMatch(Check::ok);
    }

    /** Prüfung mit diesem Namen, falls vorhanden. */
    public Optional<Check> check(String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst();
    }
}
