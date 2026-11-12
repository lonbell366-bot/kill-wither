package com.example.witheraura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
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
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
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
    private static KeyBinding keyZ, keyX;
    private final Random rng = new Random();
    private boolean enabled = false, zWasDown = false, xWasDown = false, wasAlive = false, holdingSprint = false;
    private LivingEntity target = null;
    private Vec3d aimOffset = Vec3d.ZERO;
    private long acquireTime, reactDelay, rotPauseUntil, nextShift, nextAtk, postKillUntil, breakUntil, switchTime, lockAtkUntil, sprintResume;
    private long nextF12Time = 0; // Bộ đếm thời gian bấm phím F12 ngẫu nhiên từ 5p - 5p20s
    private int pendingSlot = -1, kills = 0, killsGoal = 16;
    private double curRange = 2.95;

    // Biến lưu góc nhìn ảo (Silent Rotation) để màn hình game của bạn không bị giật/di chuyển
    private float clientYaw = 0f, clientPitch = 0f;

    @Override
    public void onInitializeClient() {
        cfg = ModConfig.load();
        keyZ = KeyBindingHelper.registerKeyBinding(new KeyBinding("Bật/Tắt WitherAura (Z)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "Wither Utility"));
        keyX = KeyBindingHelper.registerKeyBinding(new KeyBinding("Đổi Chế Độ Mục Tiêu (X)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "Wither Utility"));
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        ClientPlayerEntity p = mc.player;
        long win = mc.getWindow().getHandle(), now = System.currentTimeMillis();

        // Khởi tạo góc nhìn ảo ban đầu khớp với nhân vật
        if (clientYaw == 0f && clientPitch == 0f) {
            clientYaw = p.getYaw();
            clientPitch = p.getPitch();
        }

        // --- TÍNH NĂNG TỰ ĐỘNG BẤM PHÍM F12 NGẪU NHYÊN TỪ 5 PHÚT ĐẾN 5 PHÚT 20 GIÂY ---
        if (nextF12Time == 0) {
            scheduleNextF12(now);
        } else if (now >= nextF12Time) {
            // Giả lập sự kiện nhấn phím F12 tự động trong game
            long windowHandle = mc.getWindow().getHandle();
            // Gửi tín hiệu phím F12 (GLFW_KEY_F12)
            if (mc.currentScreen == null) {
                // Thực hiện hành động F12 (ví dụ chụp ảnh màn hình hoặc lệnh tùy chỉnh gắn với F12)
                mc.keyboard.onKey(windowHandle, GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_PRESS, 0);
                mc.keyboard.onKey(windowHandle, GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_RELEASE, 0);
            }
            scheduleNextF12(now);
        }

        boolean zDown = mc.currentScreen == null && InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_Z);
        boolean xDown = mc.currentScreen == null && InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_X);
        boolean toggleTrig = (zDown && !zWasDown), modeTrig = (xDown && !xWasDown);
        while (keyZ.wasPressed()) toggleTrig = true;
        while (keyX.wasPressed()) modeTrig = true;
        zWasDown = zDown;
        xWasDown = xDown;

        if (toggleTrig) {
            enabled = !enabled;
            target = null;
            msg(p, enabled ? "[WitherAura] ĐÃ BẬT (Silent - Màn hình cố định)" : "[WitherAura] ĐÃ TẮT", enabled ? Formatting.GREEN : Formatting.RED);
        }
        if (modeTrig) {
            boolean prev = cfg.onlyWither;
            cfg = ModConfig.load();
            cfg.onlyWither = !prev;
            cfg.save();
            target = null;
            msg(p, "[WitherAura] Chế độ: " + (cfg.onlyWither ? "CHỈ WITHER SKELETON" : "MỌI QUÁI VẬT (TEST)"), Formatting.GOLD);
        }

        if (!enabled) return;
        if (holdingSprint && now >= sprintResume) {
            holdingSprint = false;
            if (mc.options.forwardKey.isPressed()) p.setSprinting(true);
        }
        if (p.getHealth() <= cfg.minHealth || (cfg.panicPlayer && hasNearPlayer(mc, p))) {
            enabled = false;
            target = null;
            msg(p, "[WitherAura] Tự tắt an toàn do máu thấp hoặc có người!", Formatting.RED);
            return;
        }
        if (mc.currentScreen != null) return;
        if (now < breakUntil || now < postKillUntil) return;

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

        // Xoay góc nhìn ẢO (Silent Rotation) - Màn hình của bạn KHÔNG HỀ BỊ DI CHUYỂN
        updateSilentRotation(p, visPt.add(aimOffset));

        if (now - acquireTime < reactDelay || now < lockAtkUntil || now < nextAtk) return;
        if (p.getAttackCooldownProgress(0.5f) < (rng.nextDouble() < 0.7 ? 1.0f : 0.96f)) return;

        // Kiểm tra tia nhìn ảo cắt qua Hitbox trước khi chém
        Vec3d eye = p.getEyePos();
        Vec3d lookDir = Vec3d.fromPolar(clientPitch, clientYaw);
        Vec3d lookEnd = eye.add(lookDir.multiply(curRange + 0.3));
        if (target.getBoundingBox().expand(0.12).raycast(eye, lookEnd).isEmpty()) return;

        if (rng.nextDouble() < cfg.missChance) {
            p.swingHand(Hand.MAIN_HAND);
            nextAtk = now + gauss(210, 580);
            return;
        }

        // Gửi gói tin đổi hướng ảo lên server ngay trước khi tấn công để vượt qua Anti-Cheat
        p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(clientYaw, clientPitch, p.isOnGround(), p.horizontalCollision));

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);

        if (cfg.sprintReset && p.isSprinting() && rng.nextDouble() < 0.65) {
            p.setSprinting(false);
            holdingSprint = true;
            sprintResume = now + gauss(55, 135);
        }

        long delay = Math.max(25, Math.round(cfg.meanDelayMs + rng.nextGaussian() * cfg.stdDelayMs));
        if (rng.nextDouble() < 0.09) delay += gauss(200, 600);
        nextAtk = now + delay;
    }

    // Lên lịch ngẫu nhiên bấm F12 từ 5 phút (300,000 ms) đến 5 phút 20 giây (320,000 ms)
    private void scheduleNextF12(long now) {
        long minMs = 5 * 60 * 1000L;       // 5 phút
        long maxMs = (5 * 60 + 20) * 1000L; // 5 phút 20 giây
        long randomMs = minMs + (long)(rng.nextDouble() * (maxMs - minMs));
        nextF12Time = now + randomMs;
    }

    private LivingEntity findTarget(MinecraftClient mc, ClientPlayerEntity p) {
        double rSq = (curRange + 0.4) * (curRange + 0.4);
        return StreamSupport.stream(mc.world.getEntities().spliterator(), false)
                .filter(e -> e != p && e instanceof LivingEntity le && le.isAlive() && !e.isRemoved() && validMob(le))
                .map(e -> (LivingEntity) e)
                .filter(e -> getClosestPoint(p.getEyePos(), e.getBoundingBox()).squaredDistanceTo(p.getEyePos()) <= rSq)
                .filter(e -> angleTo(p.getEyePos(), p.getYaw(), p.getPitch(), e.getBoundingBox().getCenter())[0] <= cfg.fov * 0.5f)
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
        Vec3d closest = getClosestPoint(eye, b);
        for (double f : new double[]{0.55, 0.30, 0.75, 0.15, 0.85}) {
            Vec3d pt = new Vec3d(MathHelper.lerp(0.35, closest.x, b.getCenter().x), b.minY + b.getLengthY() * f, MathHelper.lerp(0.35, closest.z, b.getCenter().z));
            if (eye.squaredDistanceTo(pt) <= (curRange + 0.35) * (curRange + 0.35)) {
                if (mc.world.raycast(new RaycastContext(eye, pt, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.MISS)
                    return pt;
            }
        }
        return p.canSee(e) ? b.getCenter() : null;
    }

    private Vec3d getClosestPoint(Vec3d pos, Box box) {
        return new Vec3d(MathHelper.clamp(pos.x, box.minX, box.maxX), MathHelper.clamp(pos.y, box.minY, box.maxY), MathHelper.clamp(pos.z, box.minZ, box.maxZ));
    }

    // Xoay góc nhìn ảo mượt mà từng bước mà không làm dịch chuyển camera game của bạn
    private void updateSilentRotation(ClientPlayerEntity p, Vec3d pt) {
        float[] ang = angleTo(p.getEyePos(), clientYaw, clientPitch, pt);
        if (ang[0] < 2.0f && ang[1] < 2.0f) return;
        float yStep = cfg.minYaw + rng.nextFloat() * (cfg.maxYaw - cfg.minYaw);
        float pStep = cfg.minPitch + rng.nextFloat() * (cfg.maxPitch - cfg.minPitch);
        float dYaw = MathHelper.clamp(ang[2] * 0.6f, -yStep, yStep) + (float)(rng.nextGaussian() * 0.12f);
        float dPitch = MathHelper.clamp(ang[3] * 0.6f, -pStep, pStep) + (float)(rng.nextGaussian() * 0.1f);
        
        clientYaw += dYaw;
        clientPitch = MathHelper.clamp(clientPitch + dPitch, -89.9f, 89.9f);
    }

    private float[] angleTo(Vec3d eye, float curYaw, float curPitch, Vec3d pt) {
        Vec3d d = pt.subtract(eye);
        float tYaw = (float) (MathHelper.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90.0f;
        float tPitch = (float) (-(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * (180.0 / Math.PI)));
        float dy = MathHelper.wrapDegrees(tYaw - curYaw), dp = MathHelper.wrapDegrees(tPitch - curPitch);
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
        aimOffset = new Vec3d(rng.nextGaussian() * r * 0.4, rng.nextGaussian() * r * 0.3, rng.nextGaussian() * r * 0.4);
        nextShift = now + gauss(160, 400);
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
        public double minRange = 2.90, maxRange = 3.05, aimOffset = 0.14, missChance = 0.03, panicRadius = 15.0;
        public float fov = 170.0f, minYaw = 18.0f, maxYaw = 36.0f, minPitch = 12.0f, maxPitch = 25.0f, minHealth = 6.0f;
        public int meanDelayMs = 80, stdDelayMs = 42, minReactMs = 100, maxReactMs = 240, minPostKillMs = 85, maxPostKillMs = 240, minBreakMs = 1500, maxBreakMs = 3800, minDurability = 10;

        public static ModConfig load() {
            if (FILE.exists()) try (FileReader r = new FileReader(FILE)) { return GSON.fromJson(r, ModConfig.class); } catch (Exception ignored) {}
            ModConfig c = new ModConfig(); c.save(); return c;
        }
        public void save() { try (FileWriter w = new FileWriter(FILE)) { GSON.toJson(this, w); } catch (Exception ignored) {} }
    }
            }
