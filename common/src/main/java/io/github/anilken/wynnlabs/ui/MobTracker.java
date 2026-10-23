package io.github.anilken.wynnlabs.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.search.MobName;
import io.github.anilken.wynnlabs.search.MobSight;
import io.github.anilken.wynnlabs.search.TrackedMobs;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;

/**
 * Tracked mobs ("Track" on a mob row of the search card): when one is loaded nearby, a short
 * sound and a notice ("Reggie nearby · 85 m"); while the player can actually see it, a thin
 * beam to the sky, an outline and its distance. Wynncraft writes a mob's name on a floating
 * text display riding or hovering just above it; that name is matched ({@link MobName}) and the
 * mob is the display's vehicle or the nearest creature right under it.
 *
 * <p>Read-only: it only looks at entities the client already has. The world is scanned four
 * times a second; each scan also casts rays from the camera, and a mob behind blocks gets no
 * marker at all ({@link MobSight#shown}). Everything drawn is depth-tested, so terrain hides
 * it too. Settings: mobTrack (all of it) and mobTrackSound.
 */
public final class MobTracker {

    private static final int MAX = 8;
    private static final int COLOUR = 0xFFFFC83C;
    private static final int BEAM = 0x55FFC83C;
    private static final long NOTICE_MS = 3500;

    private static int tick;
    /** The scan's targets, read by the render path without allocating. */
    private static final Entity[] TARGET = new Entity[MAX];
    private static final VoxelShape[] SHAPE = new VoxelShape[MAX];
    private static final double[] TOP = new double[MAX];
    private static final boolean[] SEEN = new boolean[MAX];
    private static final String[] LABEL = new String[MAX];
    private static final int[] LABEL_W = new int[MAX];
    private static int count;
    private static final Int2LongOpenHashMap LAST_SEEN = new Int2LongOpenHashMap();
    private static final Object2LongOpenHashMap<String> LAST_TOLD = new Object2LongOpenHashMap<>();
    private static long lastAny;
    private static String notice;
    private static long noticeUntil;

    private MobTracker() {}

    // ---- the scan (4 Hz) ----

    public static void onTick(Minecraft mc) {
        if (++tick % 5 != 0) {
            return;
        }
        count = 0;
        if (mc.player == null || mc.level == null || !Settings.on("mobTrack")) {
            return;
        }
        Set<String> keys = TrackedMobs.get().keys();
        if (keys.isEmpty()) {
            return;
        }
        try {
            scan(mc, keys);
        } catch (Throwable ignored) {
            count = 0;                          // a marker must never break the game
        }
    }

    private static void scan(Minecraft mc, Set<String> keys) {
        long now = System.currentTimeMillis();
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (count >= MAX) {
                break;
            }
            Entity mob;
            String name;
            if (e instanceof Display.TextDisplay t) {
                name = t.getText().getString();
                if (!MobName.matches(name, keys)) {
                    continue;
                }
                mob = mobUnder(mc, t);
            } else if (e instanceof LivingEntity && !(e instanceof Player) && e.getCustomName() != null) {
                name = e.getCustomName().getString();
                if (!MobName.matches(name, keys)) {
                    continue;
                }
                mob = e;
            } else {
                continue;
            }
            if (has(mob)) {
                continue;                       // two labels on one mob
            }
            add(mc, mob, name, cam, now);
        }
        if (LAST_SEEN.size() > 256) {
            LAST_SEEN.int2LongEntrySet().removeIf(en -> now - en.getLongValue() > MobSight.GONE_MS);
        }
    }

    /** The creature a name display belongs to: what it rides, else the nearest one just under it, else the display. */
    private static Entity mobUnder(Minecraft mc, Display.TextDisplay label) {
        for (Entity v = label.getVehicle(); v != null; v = v.getVehicle()) {
            if (v instanceof LivingEntity && !(v instanceof Player) && !(v instanceof ArmorStand)) {
                return v;
            }
        }
        AABB below = new AABB(label.getX() - 1.5, label.getY() - 3.5, label.getZ() - 1.5,
                label.getX() + 1.5, label.getY() + 0.5, label.getZ() + 1.5);
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity le : mc.level.getEntitiesOfClass(LivingEntity.class, below,
                le -> !(le instanceof Player) && !(le instanceof ArmorStand) && le.isAlive())) {
            double d = le.distanceToSqr(label);
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        return best == null ? label : best;
    }

    private static boolean has(Entity mob) {
        for (int i = 0; i < count; i++) {
            if (TARGET[i] == mob) {
                return true;
            }
        }
        return false;
    }

    private static void add(Minecraft mc, Entity mob, String name, Vec3 cam, long now) {
        int i = count++;
        AABB box = mob instanceof Display
                ? new AABB(mob.getX() - 0.4, mob.getY() - 2, mob.getZ() - 0.4, mob.getX() + 0.4, mob.getY(), mob.getZ() + 0.4)
                : mob.getBoundingBox();
        double dist = Math.sqrt(box.getCenter().distanceToSqr(cam));
        boolean seen = false;
        if (dist <= MobSight.MAX_DRAW) {
            Vec3 eye = mob instanceof Display ? new Vec3(mob.getX(), box.maxY - 0.2, mob.getZ()) : mob.getEyePosition();
            boolean eyeClear = clear(mc, cam, eye);
            boolean middleClear = !eyeClear && clear(mc, cam, box.getCenter());
            seen = MobSight.shown(true, dist, eyeClear, middleClear);
        }
        TARGET[i] = mob;
        SHAPE[i] = Shapes.create(box.move(-mob.getX(), -mob.getY(), -mob.getZ()).inflate(0.08));
        TOP[i] = box.maxY - mob.getY();
        SEEN[i] = seen;
        String shown = tracked(name);
        LABEL[i] = shown + " · " + RoadmapScreen.dist((int) Math.round(dist));
        LABEL_W[i] = mc.font.width(LABEL[i]);
        long last = LAST_SEEN.containsKey(mob.getId()) ? LAST_SEEN.get(mob.getId()) : 0;
        LAST_SEEN.put(mob.getId(), now);
        if (MobSight.appeared(now, last)) {
            String key = MobName.key(name);
            long told = LAST_TOLD.containsKey(key) ? LAST_TOLD.getLong(key) : 0;
            if (MobSight.announce(now, lastAny, told)) {
                lastAny = now;
                LAST_TOLD.put(key, now);
                tell(mc, L.t("wynnlabs.track.near", shown, RoadmapScreen.dist((int) Math.round(dist))), now);
            }
        }
    }

    /** Whether nothing solid lies between the camera and {@code to} (blocks only, water ignored). */
    private static boolean clear(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player))
                .getType() == HitResult.Type.MISS;
    }

    /** The tracked name as the player picked it (proper case), for a floating name that matched. */
    private static String tracked(String label) {
        String k = MobName.key(label);
        for (String n : TrackedMobs.get().names()) {
            if (MobName.key(n).equals(k)) {
                return n;
            }
        }
        return k;
    }

    private static void tell(Minecraft mc, String text, long now) {
        notice = text;
        noticeUntil = now + NOTICE_MS;
        if (Settings.on("mobTrackSound")) {
            try {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.4f));
            } catch (Throwable ignored) {
                // no sound rather than a broken tick
            }
        }
    }

    /** Forgets every target (leaving a server). */
    public static void reset() {
        count = 0;
        LAST_SEEN.clear();
        notice = null;
    }

    // ---- the notice (HUD) ----

    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        String text = notice;
        if (text == null || mc.player == null || mc.options.hideGui || System.currentTimeMillis() > noticeUntil) {
            return;
        }
        int w = mc.font.width(text);
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() / 2 - 64;
        g.fill(x - 6, y - 4, x + w + 6, y + 12, 0xB0101017);
        g.fill(x - 6, y - 4, x - 4, y + 12, COLOUR);
        g.drawString(mc.font, text, x, y, 0xFFFFFFFF);
    }

    // ---- the markers (world) ----

    /** Beam, outline and distance of each tracked mob in plain sight; nothing for a hidden one. */
    public static void renderWorld(PoseStack pose, MultiBufferSource buffers) {
        if (count == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double sky = mc.level.getMaxY() - cam.y;
        // one render type at a time: asking the buffers for another one ends the one before
        VertexConsumer quads = buffers.getBuffer(RenderTypes.debugQuads());
        for (int i = 0; i < count; i++) {
            Entity e = TARGET[i];
            if (!SEEN[i] || e.isRemoved()) {
                continue;
            }
            float x = (float) (Mth.lerp(pt, e.xo, e.getX()) - cam.x);
            float z = (float) (Mth.lerp(pt, e.zo, e.getZ()) - cam.z);
            float bottom = (float) (Mth.lerp(pt, e.yo, e.getY()) - cam.y + TOP[i] + 0.9);
            beam(pose, quads, x, bottom, (float) sky, z, 0.1f);
        }
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        for (int i = 0; i < count; i++) {
            Entity e = TARGET[i];
            if (!SEEN[i] || e.isRemoved()) {
                continue;
            }
            ShapeRenderer.renderShape(pose, lines, SHAPE[i], Mth.lerp(pt, e.xo, e.getX()) - cam.x,
                    Mth.lerp(pt, e.yo, e.getY()) - cam.y, Mth.lerp(pt, e.zo, e.getZ()) - cam.z, COLOUR, 2f);
        }
        Font font = mc.font;
        for (int i = 0; i < count; i++) {
            Entity e = TARGET[i];
            if (!SEEN[i] || e.isRemoved()) {
                continue;
            }
            double x = Mth.lerp(pt, e.xo, e.getX()) - cam.x;
            double y = Mth.lerp(pt, e.yo, e.getY()) - cam.y + TOP[i] + 0.5;
            double z = Mth.lerp(pt, e.zo, e.getZ()) - cam.z;
            double d = Math.sqrt(x * x + y * y + z * z);
            // about the same size on screen near or far
            float s = (float) (0.025 * Math.max(1, d / 10));
            pose.pushPose();
            pose.translate(x, y, z);
            pose.mulPose(camera.rotation());
            pose.scale(s, -s, s);
            font.drawInBatch(LABEL[i], -LABEL_W[i] / 2f, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0x60000000, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        if (buffers instanceof MultiBufferSource.BufferSource source) {
            source.endBatch();
        }
    }

    /** A thin square column from {@code y0} to {@code y1}, four see-through sides. */
    private static void beam(PoseStack pose, VertexConsumer c, float x, float y0, float y1, float z, float r) {
        if (y1 <= y0) {
            return;
        }
        var p = pose.last();
        side(p, c, x - r, z - r, x + r, z - r, y0, y1);
        side(p, c, x + r, z - r, x + r, z + r, y0, y1);
        side(p, c, x + r, z + r, x - r, z + r, y0, y1);
        side(p, c, x - r, z + r, x - r, z - r, y0, y1);
    }

    private static void side(PoseStack.Pose p, VertexConsumer c, float ax, float az, float bx, float bz, float y0, float y1) {
        c.addVertex(p, ax, y0, az).setColor(BEAM);
        c.addVertex(p, bx, y0, bz).setColor(BEAM);
        c.addVertex(p, bx, y1, bz).setColor(BEAM);
        c.addVertex(p, ax, y1, az).setColor(BEAM);
    }
}
