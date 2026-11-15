package com.leaguetool.calc;

/** Schadensart einer Fähigkeit. */
public enum DamageType {
    /** Normaler (physischer) Schaden, reduziert durch Rüstung. */
    PHYSICAL("Normaler Schaden"),
    /** Magischer Schaden, reduziert durch Magieresistenz. */
    MAGIC("Magischer Schaden"),
    /** Absoluter Schaden, wird nicht reduziert. */
    TRUE("Absoluter Schaden"),
    /** Gemischter Schaden; die Aufteilung ist nicht bekannt, daher keine Reduktion berechenbar. */
    MIXED("Gemischter Schaden");

    private final String label;

    DamageType(String label) {
        this.label = label;
    }

    /** Deutsche Bezeichnung für die Oberfläche. */
    public String label() {
        return label;
    }
}
