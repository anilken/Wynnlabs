package io.github.anilken.wynnlabs.game;

import com.wynntils.core.components.Managers;
import com.wynntils.features.tooltips.TooltipFittingFeature;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import com.wynntils.mc.event.SlotRenderEvent;
import com.wynntils.mc.event.TooltipRenderEvent;
import com.wynntils.models.activities.event.ActivityUpdatedEvent;
import com.wynntils.models.activities.type.ActivityType;
import com.wynntils.models.characterstats.event.CombatXpGainEvent;
import com.wynntils.models.combat.type.DamageDealtEvent;
import com.wynntils.models.containers.event.ValuableFoundEvent;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.AspectItem;
import com.wynntils.models.items.properties.NamedItemProperty;
import com.wynntils.models.lootrun.event.LootrunBeaconSelectedEvent;
import com.wynntils.models.lootrun.event.LootrunFinishedEvent;
import com.wynntils.models.profession.event.ProfessionXpGainEvent;
import com.wynntils.models.raid.event.RaidEndedEvent;
import com.wynntils.models.raid.event.RaidStartedEvent;
import com.wynntils.models.raid.raids.RaidKind;
import com.wynntils.models.raid.type.RaidInfo;
import com.wynntils.models.spells.event.SpellEvent;
import com.wynntils.models.spells.type.SpellFailureReason;
import io.github.anilken.wynnlabs.advice.TooltipListener;
import io.github.anilken.wynnlabs.coach.Coach;
import io.github.anilken.wynnlabs.core.Perf;
import io.github.anilken.wynnlabs.crafting.ProfessionXp;
import io.github.anilken.wynnlabs.endgame.LootrunLog;
import io.github.anilken.wynnlabs.endgame.RaidLog;
import io.github.anilken.wynnlabs.model.RaidRun;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.ui.MenuTooltips;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The Wynntils events the mod listens to, registered on Wynntils' bus by {@link Wyn#listen}.
 * Each handler only unpacks the event and hands plain values to the code that acts on them
 * ({@link TooltipListener}, {@link Coach}, and for the Endgame tab {@link RaidLog} and
 * {@link LootrunLog} through the nested listeners), so Wynntils' event types stay in this file.
 */
public final class WynntilsEvents {

    /** Slot glows and the red "cannot wear" frame. */
    @SubscribeEvent
    public void onSlotPre(SlotRenderEvent.Pre event) {
        long t = Perf.start();
        try {
            TooltipListener.onSlot(event.getGuiGraphics(), event.getSlot());
        } catch (Throwable ignored) {
            // drawing a glow must never break the menu
        } finally {
            Perf.end("slot.pre", t);
        }
    }

    /**
     * The plan's step number on the ability tree's nodes and the advice word on items, over the
     * drawn item: last of all, after Wynntils' own slot overlays (the box icon on unidentified
     * gear), in a layer above them.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSlotPost(SlotRenderEvent.Post event) {
        long t = Perf.start();
        try {
            TooltipListener.onSlotAfter(event.getGuiGraphics(), event.getSlot());
        } catch (Throwable ignored) {
            // drawing a number must never break the menu
        } finally {
            Perf.end("slot.post", t);
        }
    }

    /** Advice and prices under the final tooltip: lowest priority, so they go under everything else. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTooltip(ItemTooltipRenderEvent.Pre event) {
        long t = Perf.start();
        try {
            List<Component> out = TooltipListener.onTooltip(event.getItemStack(), event.getTooltips());
            if (out != null) {
                event.setTooltips(out);
            }
        } catch (Throwable ignored) {
            // a broken advice line must never break the tooltip
        } finally {
            Perf.end("tooltip.wynntils", t);
        }
    }

    @SubscribeEvent
    public void onCast(SpellEvent.Cast e) {
        int spell = 0;
        String name = "";
        try {
            spell = e.getSpellType().getSpellNumber();
            name = e.getSpellType().getName();
        } catch (Throwable ignored) {
            // no spell type: the cast still opens a window, booked under no spell
        }
        Coach.onCast(spell, name, e.getManaCost());
    }

    @SubscribeEvent
    public void onDamage(DamageDealtEvent e) {
        Coach.onDamage(e.getDamages().values());
    }

    @SubscribeEvent
    public void onSpellFailed(SpellEvent.Failed e) {
        if (e.getFailureReason() == SpellFailureReason.NOT_ENOUGH_MANA) {
            Coach.onManaFail();
        }
    }

    @SubscribeEvent
    public void onXp(CombatXpGainEvent e) {
        Coach.onXp();
    }

    /**
     * Wynntils read a fresh quest or mini-quest list from the content book. Registered under
     * QUESTS (Wyn.listen), so a change to this event turns off only this. {@link QuestBook} takes
     * the list in only after the player pressed "read quests with Wynntils".
     */
    public static final class Quests {

        @SubscribeEvent
        public void onActivityUpdated(ActivityUpdatedEvent e) {
            ActivityType type = Wyn.guard(Wyn.Feature.QUESTS, e::getActivityType, null);
            if (type == ActivityType.QUEST || type == ActivityType.MINI_QUEST) {
                try {
                    QuestBook.fromWynntils();
                } catch (Throwable t) {
                    System.out.println("[WynnLabs] quests from Wynntils failed: " + t);
                }
            }
        }
    }

    /**
     * Tooltip size in menus ({@link MenuTooltips}). Wynntils asks for a positioner before it
     * places each tooltip; its own fitting answers last (lowest priority) and only when nobody
     * else did, so ours, a step earlier, takes its place in menus instead of shrinking on top of
     * it, and draws the smaller of the two scales. Wynntils' features that place a tooltip
     * themselves (item compare, item screenshots) answer first: then ours stands aside too.
     * Registered on its own (Wyn.listen), so a change to these events costs only this.
     */
    public static final class Tooltips {

        /** Wynntils' tooltip fitting, looked up once; null when this Wynntils has none. */
        private TooltipFittingFeature fitting;
        private boolean looked;

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onPosition(TooltipRenderEvent.Position e) {
            try {
                if (e.getPositioner() == null) {
                    var p = MenuTooltips.positioner(e.getGuiGraphics(), universalScale());
                    if (p != null) {
                        e.setPositioner(p);
                    }
                }
            } catch (Throwable ignored) {
                // the tooltip is then drawn as Wynntils and the game would
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onPost(TooltipRenderEvent.Post e) {
            try {
                MenuTooltips.end(e.getGuiGraphics());
            } catch (Throwable ignored) {
                // nothing was scaled
            }
        }

        /** Wynntils' universal tooltip scale while its fitting is on; 1 otherwise. */
        private float universalScale() {
            if (!looked) {
                looked = true;
                try {
                    fitting = Managers.Feature.getFeatureInstance(TooltipFittingFeature.class);
                } catch (Throwable t) {
                    System.out.println("[WynnLabs] Wynntils tooltip fitting not found, menu tooltips ignore it: " + t);
                }
            }
            if (fitting == null || !fitting.isEnabled()) {
                return 1f;
            }
            Float s = fitting.universalScale.get();
            return s == null ? 1f : s;
        }
    }

    // ---- the Endgame tab: raids, lootruns, reward chest mythics ----
    //
    // Each group is a listener of its own, registered under its feature (Wyn.listen), so a
    // Wynntils change to one of these events turns off that tracking only, never the tooltips
    // above. Every read of an event runs in Wyn.guard; the logs get plain values.

    /** Raid starts and ends, for {@link RaidLog}. */
    public static final class Raids {

        @SubscribeEvent
        public void onRaidStarted(RaidStartedEvent e) {
            long now = System.currentTimeMillis();
            String raid = Wyn.guard(Wyn.Feature.RAIDS, () -> e.getRaidKind() == null ? null : e.getRaidKind().getRaidName(), null);
            if (raid != null) {
                hand("raid start", () -> RaidLog.started(raid, now));
            }
        }

        @SubscribeEvent
        public void onRaidCompleted(RaidEndedEvent.Completed e) {
            ended(e, true);
        }

        @SubscribeEvent
        public void onRaidFailed(RaidEndedEvent.Failed e) {
            ended(e, false);
        }

        private static void ended(RaidEndedEvent e, boolean completed) {
            long now = System.currentTimeMillis();
            RaidRun run = Wyn.guard(Wyn.Feature.RAIDS, () -> raidRun(e.getRaid(), completed, now), null);
            if (run != null) {
                System.out.println("[WynnLabs] raid " + (completed ? "completed" : "failed") + ": " + run.raid());
                hand("raid end", () -> RaidLog.record(run));
            }
        }

        private static RaidRun raidRun(RaidInfo info, boolean completed, long now) {
            if (info == null || info.getRaidKind() == null) {
                return null;
            }
            RaidKind kind = info.getRaidKind();
            List<RaidRun.Room> rooms = new ArrayList<>();
            if (info.getChallenges() != null) {
                new TreeMap<>(info.getChallenges()).values().forEach(r -> {
                    if (r != null) {
                        rooms.add(new RaidRun.Room(r.getRoomName(), r.getRoomTotalTime()));
                    }
                });
            }
            return new RaidRun(kind.getRaidName(), kind.getAbbreviation(), completed, now, info.getTimeInRaid(), rooms);
        }
    }

    /** Profession XP gains, for the crafting tab's planner ({@link ProfessionXp}). */
    public static final class ProfessionGains {

        @SubscribeEvent
        public void onProfessionXp(ProfessionXpGainEvent e) {
            String profession = Wyn.guard(Wyn.Feature.PROFESSIONS, () -> e.getProfession().name(), null);
            double xp = Wyn.guard(Wyn.Feature.PROFESSIONS, () -> (double) e.getGainedXpRaw(), 0.0);
            if (profession != null) {
                hand("profession xp", () -> ProfessionXp.gained(profession, xp));
            }
        }
    }

    /** Lootrun ends and beacon picks, for {@link LootrunLog}. */
    public static final class Lootruns {

        @SubscribeEvent
        public void onLootrunCompleted(LootrunFinishedEvent.Completed e) {
            long now = System.currentTimeMillis();
            int[] v = Wyn.guard(Wyn.Feature.LOOTRUNS, () -> new int[] {e.getChallengesCompleted(), e.getTimeElapsed(),
                    e.getRewardPulls(), e.getRewardRerolls(), e.getRewardSacrifices(), e.getExperienceGained()}, null);
            if (v != null) {
                System.out.println("[WynnLabs] lootrun completed: " + v[0] + " challenges, " + v[2] + " pulls");
                hand("lootrun end", () -> LootrunLog.finished(true, v[0], v[1], v[2], v[3], v[4], v[5], now));
            }
        }

        @SubscribeEvent
        public void onLootrunFailed(LootrunFinishedEvent.Failed e) {
            long now = System.currentTimeMillis();
            int[] v = Wyn.guard(Wyn.Feature.LOOTRUNS, () -> new int[] {e.getChallengesCompleted(), e.getTimeElapsed()}, null);
            if (v != null) {
                System.out.println("[WynnLabs] lootrun failed: " + v[0] + " challenges");
                hand("lootrun end", () -> LootrunLog.finished(false, v[0], v[1], 0, 0, 0, 0, now));
            }
        }

        @SubscribeEvent
        public void onBeaconSelected(LootrunBeaconSelectedEvent e) {
            String colour = Wyn.guard(Wyn.Feature.LOOTRUNS, () -> {
                Object kind = e.getBeacon() == null ? null : e.getBeacon().beaconKind();
                return kind instanceof Enum<?> k ? colourName(k.name()) : null;
            }, null);
            if (colour != null) {
                System.out.println("[WynnLabs] lootrun beacon: " + colour);
                hand("lootrun beacon", () -> LootrunLog.beacon(colour));
            }
        }

        /** "DARK_GRAY" → "Dark Gray", as the game names the colour. */
        private static String colourName(String constant) {
            StringBuilder b = new StringBuilder();
            for (String part : constant.toLowerCase(Locale.ROOT).split("_")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return b.toString();
        }
    }

    /** Mythics found in lootrun and raid reward chests (other sources are ignored). */
    public static final class Valuables {

        private static final String LOOTRUN = "lootrun";
        private static final String RAID_ASPECT = "raid aspect";
        private static final String RAID_OTHER = "raid";

        @SubscribeEvent
        public void onValuableFound(ValuableFoundEvent e) {
            long now = System.currentTimeMillis();
            // source first, under the feature it feeds
            String source = Wyn.guard(Wyn.Feature.LOOTRUNS, () -> {
                ValuableFoundEvent.ItemSource s = e.getItemSource();
                if (s == ValuableFoundEvent.ItemSource.LOOTRUN_REWARD_CHEST) {
                    return LOOTRUN;
                }
                return s == ValuableFoundEvent.ItemSource.RAID_REWARD_CHEST ? RAID_OTHER : null;
            }, null);
            if (source == null) {
                return;
            }
            Wyn.Feature f = LOOTRUN.equals(source) ? Wyn.Feature.LOOTRUNS : Wyn.Feature.RAIDS;
            String[] found = Wyn.guard(f, () -> {
                ItemStack stack = e.getItem();
                if (stack == null || stack.isEmpty()) {
                    return null;
                }
                WynnItem item = Wyn.item(stack).orElse(null);
                String name = item instanceof NamedItemProperty n ? n.getName() : stack.getHoverName().getString();
                String plain = ChatFormatting.stripFormatting(name);
                String kind = LOOTRUN.equals(source) ? LOOTRUN : item instanceof AspectItem ? RAID_ASPECT : RAID_OTHER;
                return plain == null || plain.isBlank() ? null : new String[] {plain.trim(), kind};
            }, null);
            if (found == null) {
                return;
            }
            System.out.println("[WynnLabs] mythic from a " + (LOOTRUN.equals(found[1]) ? "lootrun" : "raid") + " reward chest: " + found[0]);
            if (LOOTRUN.equals(found[1])) {
                hand("lootrun mythic", () -> LootrunLog.mythic(found[0], now));
            } else {
                hand("raid mythic", () -> RaidLog.mythic(found[0], RAID_ASPECT.equals(found[1]), now));
            }
        }
    }

    /** Hands plain values to a log; its failure is logged and never reaches Wynntils' bus. */
    private static void hand(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            System.out.println("[WynnLabs] " + what + " failed: " + ex);
        }
    }
}
