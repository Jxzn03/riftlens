package com.leaguetool.data;

import java.util.List;

/**
 * Ergebnis eines Abgleichs der Item-Passiven bzw. Runen-Effekte.
 *
 * @param patch     geprüfter Patch, leer vor der ersten Prüfung
 * @param checkedAt Datum der Prüfung (ISO)
 * @param count     Anzahl der aktiven Effekte
 * @param changes   automatisch übernommene Änderungen dieses Patches
 * @param review    Texte, die nicht ausgewertet werden konnten (letzter Wert bleibt)
 * @param notes     Hinweise zur Prüfung (Quellen, Abweichungen, Wiki nicht verfügbar)
 */
public record UpdateReport(String patch, String checkedAt, int count, List<String> changes, List<String> review,
                           List<String> notes) {

    /** Stand vor der ersten Prüfung. */
    public static UpdateReport none(int count) {
        return new UpdateReport("", "", count, List.of(), List.of(), List.of());
    }
}
