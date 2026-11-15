package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Fähigkeit aus einer Wiki-Seite {@code Template:Data_<Champion>/<Fähigkeit>}. Alle Texte sind Klartext;
 * fehlende Angaben sind leere Strings.
 *
 * @param champion       Wiki-Name des Champions (Parameter {@code champion})
 * @param slot           Slot aus {@code skill} (I → P, Q, W, E, R)
 * @param name           Name der Fähigkeit
 * @param description    {@code description}, {@code description2}, … zusammengefügt (Absätze durch Leerzeile)
 * @param levelings      alle Werte aus {@code leveling}, {@code leveling2}, …
 * @param cooldownText   Abklingzeit als Text (bei Passiven ersatzweise {@code static})
 * @param cooldownValues Abklingzeit je Rang (leer, wenn nicht parsebar; eine einzelne Zahl gilt für alle Ränge)
 * @param costText       Kosten als Text, z. B. "55 – 95"
 * @param costType       Kostenart, z. B. "mana"
 * @param rangeText      Reichweite ({@code range}, ersatzweise {@code target range})
 * @param castTime       Zauberzeit ({@code cast time})
 * @param damageType     Schadensart ({@code damagetype})
 * @param targeting      Zielerfassung, z. B. "Direction"
 * @param affects        betroffene Einheiten, z. B. "Enemies"
 * @param spellshield    Interaktion mit Zauberschilden
 * @param projectile     Projektil ("True"/"False" bzw. leer)
 * @param staticCooldown {@code true}, wenn das Wiki nur eine statische Abklingzeit ({@code static}) angibt; Haste
 *                       wirkt darauf laut Wiki nicht
 */
public record WikiAbility(String champion, AbilitySlot slot, String name, String description,
                          List<WikiLeveling> levelings, String cooldownText, List<Double> cooldownValues,
                          String costText, String costType, String rangeText, String castTime, String damageType,
                          String targeting, String affects, String spellshield, String projectile,
                          boolean staticCooldown) {

    /** Immobilisierende Kontrolleffekte laut Wiki (Airborne, Forced Action, Root, Sleep, Stasis, Stun, Suppression, Suspension). */
    private static final Pattern IMMOBILIZING = Pattern.compile("\\b(airborne|knock(s|ing|ed)?\\s+(\\w+\\s+)?(up|back|aside)"
            + "|pull(s|ing|ed)?|root(s|ing|ed)?|sleep|stasis|stun(s|ning|ned)?|suppress(es|ing|ed)?|suspen(sion|ds?|ding|ded)"
            + "|charm(s|ing|ed)?|flee(s|ing)?|fear(s|ing|ed)?|taunt(s|ing|ed)?|berserk|immobiliz\\w+)\\b", Pattern.CASE_INSENSITIVE);

    public WikiAbility {
        levelings = List.copyOf(levelings);
        cooldownValues = List.copyOf(cooldownValues);
    }

    /** {@code true}, wenn die Beschreibung einen immobilisierenden Kontrolleffekt nennt. */
    public boolean immobilizes() {
        return IMMOBILIZING.matcher(description).find();
    }
}
