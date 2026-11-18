package com.leaguetool.model;

/** Fähigkeiten-Slot. */
public enum AbilitySlot {
    P("Passiv"), Q("Q"), W("W"), E("E"), R("R");

    private final String label;

    AbilitySlot(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Riot-Timeline {@code skillSlot} (1=Q, 2=W, 3=E, 4=R). */
    public static AbilitySlot fromSkillSlot(int skillSlot) {
        return switch (skillSlot) {
            case 1 -> Q;
            case 2 -> W;
            case 3 -> E;
            case 4 -> R;
            default -> throw new IllegalArgumentException("Unbekannter skillSlot: " + skillSlot);
        };
    }
}
