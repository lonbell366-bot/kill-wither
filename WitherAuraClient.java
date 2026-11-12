package com.example.witheraura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.StreamSupport;

public class WitherAuraClient implements ClientModInitializer {
    private static ModConfig cfg;
    private final Random rng = new Random();
    private boolean enabled = false, zWasDown = false, hWasDown = false, wasAlive = false, holdingSprint = false;
    private LivingEntity target = null;
    private Vec3d aimOffset = Vec3d.ZERO;
    private long acquireTime, reactDelay, rotPauseUntil, nextShift, nextAtk, postKillUntil, breakUntil, switchTime, lockAtkUntil, sprintResume;
    private int pendingSlot = -1, kills = 0, killsGoal = 16;
    private double curRange = 2.95;

    @Override
    public void onInitializeClient() {
        cfg = ModConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        ClientPlayerEntity p = mc.player;
        long win = mc.getWindow().getHandle(), now = System.currentTimeMillis();

        boolean zDown = mc.currentScreen == null && InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_Z);
        boolean hDown = mc.currentScreen == null && InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_H);

        if (zDown && !zWasDown) {
            enabled = !enabled;
            target = null;
            msg(p, enabled ? "[WitherAura] ĐÃ BẬT (Z)" : "[WitherAura] ĐÃ TẮT (Z)", enabled ? Formatting.GREEN : Formatting.RED);
        }
        if (hDown && !hWasDown) {
            boolean prev = cfg.onlyWither;
            cfg = ModConfig.load();
            cfg.onlyWither = !prev;
            cfg.save();
            target = null;
            msg(p, "[WitherAura] Chế độ (H): " + (cfg.onlyWither ? "CHỈ WITHER SKELETON" : "MỌI QUÁI (TEST)"), Formatting.GOLD);
        }
        zWasDown = zDown;
        hWasDown = hDown;

        if (!enabled) return;
        if (holdingSprint && now >= sprintResume) {
            holdingSprint = false;
            if (mc.options.forwardKey.isPressed()) p.setSprinting(true);
        }
        if (p.getHealth() <= cfg.minHealth || (cfg.panicPlayer && hasNearPlayer(mc, p))) {
            enabled = false;
            target = null;
            msg(p, "[WitherAura] Tự tắt an toàn!", Formatting.RED);
            return;
        }
        if (mc.currentScreen != null) return;
        if (now < breakUntil || now < postKillUntil) {
            drift(mc, p);
            return;
        }

        if (target != null && wasAlive && !target.isAlive()) {
            wasAlive = false;
            target = null;
            postKillUntil = now + gauss(cfg.minPostKillMs, cfg.maxPostKillMs);
            if (++kills >= killsGoal) {
                kills = 0;
                killsGoal = gauss(12, 24);
                breakUntil = now + gauss(cfg.minBreakMs, cfg.maxBreakMs);
            }
            return;
        }

        LivingEntity best = findTarget(mc, p);
        if (best == null) { target = null; return; }
        if (best != target) {
            target = best;
            wasAlive = target.isAlive();
            acquireTime = now;
            reactDelay = gauss(cfg.minReactMs, cfg.maxReactMs);
            curRange = cfg.minRange + rng.nextDouble() * (cfg.maxRange - cfg.minRange);
            shiftAim(now);
        }
        if (now >= nextShift) shiftAim(now);

        Vec3d visPt = getVisiblePoint(mc, p, target);
        if (visPt == null) { target = null; return; }

        if (cfg.autoSword) autoSwitchSword(p, now);
        if (isBroken(p.getMainHandStack())) return;

        if (now >= rotPauseUntil) {
            if (rng.nextDouble() < 0.05) rotPauseUntil = now + gauss(25, 70);
            else rotate(mc, p, visPt.add(aimOffset));
        }

        if (now - acquireTime < reactDelay || now < lockAtkUntil || now < nextAtk) return;
        if (p.getAttackCooldownProgress(0.5f) < (rng.nextDouble() < 0.7 ? 1.0f : 0.96f)) return;
        if (angleTo(p, visPt)[0] > 25.0f) return;

        if (rng.nextDouble() < cfg.missChance) {
            p.swingHand(Hand.MAIN_HAND);
            nextAtk = now + gauss(220, 620);
            return;
        }

        if (cfg.sprintReset && p.isSprinting() && rng.nextDouble() < 0.65) {
            p.setSprinting(false);
            holdingSprint = true;
            sprintResume = now + gauss(55, 140);
        }

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        long delay = Math.max(20, Math.round(cfg.meanDelayMs + rng.nextGaussian() * cfg.stdDelayMs));
        if (rng.nextDouble() < 0.09) delay += gauss(220, 650);
        nextAtk = now + delay;
    }

    private LivingEntity findTarget(MinecraftClient mc, ClientPlayerEntity p) {
        double rSq = (curRange + 0.35) * (curRange + 0.35);
        return StreamSupport.stream(mc.world.getEntities().spliterator(), false)
                .filter(e -> e != p && e instanceof LivingEntity le && le.isAlive() && !e.isRemoved() && validMob(le))
                .map(e -> (LivingEntity) e)
                .filter(e -> p.squaredDistanceTo(e) <= rSq && angleTo(p, e.getBoundingBox().getCenter())[0] <= cfg.fov * 0.5f)
                .filter(e -> getVisiblePoint(mc, p, e) != null)
                .min(Comparator.comparingDouble(e -> "HEALTH".equalsIgnoreCase(cfg.priority) ? e.getHealth() : e.squaredDistanceTo(p)))
                .orElse(null);
    }

    private boolean validMob(LivingEntity e) {
        return cfg.onlyWither ? e instanceof WitherSkeletonEntity : (e instanceof HostileEntity && !(e instanceof PlayerEntity));
    }

    private Vec3d getVisiblePoint(MinecraftClient mc, ClientPlayerEntity p, Entity e) {
        Vec3d eye = p.getEyePos();
        Box b = e.getBoundingBox();
        for (double f : new double[]{0.55, 0.3, 0.75, 0.15, 0.88}) {
            Vec3d pt = new Vec3d(b.getCenter().x, b.minY + b.getLengthY() * f, b.getCenter().z);
            if (eye.squaredDistanceTo(pt) <= curRange * curRange + 0.5) {
                if (mc.world.raycast(new RaycastContext(eye, pt, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.MISS)
                    return pt;
            }
        }
        return null;
    }

    private void rotate(MinecraftClient mc, ClientPlayerEntity p, Vec3d pt) {
        float[] ang = angleTo(p, pt);
        if (ang[0] < 2.3f && ang[1] < 2.3f) { drift(mc, p); return; }
        float yStep = cfg.minYaw + rng.nextFloat() * (cfg.maxYaw - cfg.minYaw);
        float pStep = cfg.minPitch + rng.nextFloat() * (cfg.maxPitch - cfg.minPitch);
        float dYaw = MathHelper.clamp(ang[2] * (0.48f + rng.nextFloat() * 0.22f), -yStep, yStep) + (float) (rng.nextGaussian() * 0.14f);
        float dPitch = MathHelper.clamp(ang[3] * (0.42f + rng.nextFloat() * 0.22f), -pStep, pStep) + (float) (rng.nextGaussian() * 0.12f);
        float gcd = getGcd(mc);
        p.setYaw(p.getYaw() + dYaw - (dYaw % gcd));
        p.setPitch(MathHelper.clamp(p.getPitch() + dPitch - (dPitch % gcd), -89.9f, 89.9f));
    }

    private float[] angleTo(ClientPlayerEntity p, Vec3d pt) {
        Vec3d d = pt.subtract(p.getEyePos());
        float tYaw = (float) (MathHelper.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90.0f;
        float tPitch = (float) (-(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * (180.0 / Math.PI)));
        float dy = MathHelper.wrapDegrees(tYaw - p.getYaw()), dp = MathHelper.wrapDegrees(tPitch - p.getPitch());
        return new float[]{Math.abs(dy), Math.abs(dp), dy, dp};
    }

    private void autoSwitchSword(ClientPlayerEntity p, long now) {
        int best = -1; double bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if ((s.getItem() instanceof SwordItem || s.isIn(ItemTags.SWORDS)) && !isBroken(s)) {
                double sc = getLooting(s) * 100.0 + (s.getMaxDamage() - s.getDamage()) * 0.001;
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
        }
        if (best == -1 || p.getInventory().selectedSlot == best) { pendingSlot = -1; return; }
        if (pendingSlot != best) { pendingSlot = best; switchTime = now + gauss(80, 210); return; }
        if (now >= switchTime) {
            p.getInventory().selectedSlot = pendingSlot;
            pendingSlot = -1;
            lockAtkUntil = now + gauss(80, 210);
        }
    }

    private int getLooting(ItemStack s) {
        var ench = s.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        return ench.getEnchantments().stream().filter(e -> e.matchesKey(Enchantments.LOOTING)).mapToInt(ench::getLevel).findFirst().orElse(0);
    }

    private boolean isBroken(ItemStack s) { return s.isDamageable() && (s.getMaxDamage() - s.getDamage()) <= cfg.minDurability; }

    private boolean hasNearPlayer(MinecraftClient mc, ClientPlayerEntity self) {
        return mc.world.getPlayers().stream().anyMatch(o -> o != self && o.isAlive() && !o.isSpectator() && !o.isInvisible() && self.squaredDistanceTo(o) <= cfg.panicRadius * cfg.panicRadius);
    }

    private void shiftAim(long now) {
        double r = cfg.aimOffset;
        aimOffset = new Vec3d(rng.nextGaussian() * r * 0.45, rng.nextGaussian() * r * 0.35, rng.nextGaussian() * r * 0.45);
        nextShift = now + gauss(150, 390);
    }

    private void drift(MinecraftClient mc, ClientPlayerEntity p) {
        if (rng.nextDouble() < 0.25) {
            float gcd = getGcd(mc), dy = (float) (rng.nextGaussian() * 0.15f), dp = (float) (rng.nextGaussian() * 0.1f);
            p.setYaw(p.getYaw() + dy - (dy % gcd));
            p.setPitch(MathHelper.clamp(p.getPitch() + dp - (dp % gcd), -89.9f, 89.9f));
        }
    }

    private float getGcd(MinecraftClient mc) {
        double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    private int gauss(int min, int max) {
        return min >= max ? min : MathHelper.clamp((int) Math.round((min + max) / 2.0 + rng.nextGaussian() * ((max - min) / 4.0)), min, max);
    }

    private void msg(ClientPlayerEntity p, String text, Formatting color) {
        if (!cfg.silentMode) {
            p.sendMessage(Text.literal(text).formatted(color), true);
            p.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
        }
    }

    public static class ModConfig {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "wither_aura.json");
        public boolean onlyWither = true, autoSword = true, sprintReset = true, panicPlayer = false, silentMode = false;
        public String priority = "DISTANCE";
        public double minRange = 2.90, maxRange = 3.05, aimOffset = 0.16, missChance = 0.03, panicRadius = 15.0;
        public float fov = 160.0f, minYaw = 14.0f, maxYaw = 32.0f, minPitch = 10.0f, maxPitch = 22.0f, minHealth = 6.0f;
        public int meanDelayMs = 75, stdDelayMs = 40, minReactMs = 110, maxReactMs = 260, minPostKillMs = 95, maxPostKillMs = 270, minBreakMs = 1500, maxBreakMs = 4000, minDurability = 10;

        public static ModConfig load() {
            if (FILE.exists()) try (FileReader r = new FileReader(FILE)) { return GSON.fromJson(r, ModConfig.class); } catch (Exception ignored) {}
            ModConfig c = new ModConfig(); c.save(); return c;
        }
        public void save() { try (FileWriter w = new FileWriter(FILE)) { GSON.toJson(this, w); } catch (Exception ignored) {} }
    }
          }
                                    
