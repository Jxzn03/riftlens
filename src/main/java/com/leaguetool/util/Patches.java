package com.leaguetool.util;

import java.util.Comparator;

/** Hilfen für Patch-Versionen ("16.22.1", Match-{@code gameVersion} "16.22.712.5432"). */
public final class Patches {

    /** Neueste Version zuerst. */
    public static final Comparator<String> NEWEST_FIRST = Patches::compareDesc;

    private Patches() {
    }

    /** Kurzform "Major.Minor": {@code "16.22.1"} → {@code "16.22"}; ungültig → Eingabe unverändert. */
    public static String shortPatch(String version) {
        if (version == null) {
            return "";
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2) {
            return version.trim();
        }
        return parts[0] + "." + parts[1];
    }

    /** {@code true} für Data-Dragon-Versionen wie "16.22.1" (nicht "lolpatch_7.20"). */
    public static boolean isRegularVersion(String version) {
        return version != null && version.matches("\\d+\\.\\d+\\.\\d+");
    }

    static int compareDesc(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int ia = i < pa.length ? parse(pa[i]) : 0;
            int ib = i < pb.length ? parse(pb[i]) : 0;
            if (ia != ib) {
                return Integer.compare(ib, ia);
            }
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
