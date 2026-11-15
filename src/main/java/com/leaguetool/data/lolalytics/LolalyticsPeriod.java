package com.leaguetool.data.lolalytics;

/** Zeitraum einer Lolalytics-Abfrage. */
public enum LolalyticsPeriod {

    /** Die letzten 30 Tage (größte Stichprobe, mehrere Patches). */
    LAST_30_DAYS("30", "30d"),
    /** Nur der aktuelle Patch (Standard der Seite ohne {@code patch}-Parameter). */
    CURRENT_PATCH(null, "patch");

    private final String patchParam;
    private final String cacheSuffix;

    LolalyticsPeriod(String patchParam, String cacheSuffix) {
        this.patchParam = patchParam;
        this.cacheSuffix = cacheSuffix;
    }

    /** Wert des URL-Parameters {@code patch} oder {@code null}, wenn er entfällt. */
    String patchParam() {
        return patchParam;
    }

    String cacheSuffix() {
        return cacheSuffix;
    }
}
