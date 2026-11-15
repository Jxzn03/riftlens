package com.leaguetool.model;

/**
 * Basiswerte eines Champions (Data-Dragon-Felder). {@code attackSpeedPerLevel} ist ein Prozentwert
 * (z. B. 2.2 = +2,2 % pro Level), alle anderen {@code *PerLevel}-Werte sind absolute Zuwächse.
 */
public record BaseStats(
        double hp, double hpPerLevel,
        double mp, double mpPerLevel,
        double moveSpeed,
        double armor, double armorPerLevel,
        double spellBlock, double spellBlockPerLevel,
        double attackRange,
        double hpRegen, double hpRegenPerLevel,
        double mpRegen, double mpRegenPerLevel,
        double crit, double critPerLevel,
        double attackDamage, double attackDamagePerLevel,
        double attackSpeed, double attackSpeedPerLevel) {
}
