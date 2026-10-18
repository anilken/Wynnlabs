package io.github.anilken.wynnlabs.model;

/**
 * Wynntils' raid dry counters, which count the whole Minecraft account (all characters). Each
 * is -1 when the installed Wynntils does not provide it; -1 is shown as unknown, never as 0.
 *
 * @param aspectRaids raids since the last mythic aspect
 * @param aspectPulls aspect pulls since the last mythic aspect
 * @param tomeRaids   raids since the last mythic tome
 * @param tomePulls   reward pulls since the last mythic tome
 */
public record RaidDry(int aspectRaids, int aspectPulls, int tomeRaids, int tomePulls) {

    /** Every counter unknown. */
    public static final RaidDry UNKNOWN = new RaidDry(-1, -1, -1, -1);
}
