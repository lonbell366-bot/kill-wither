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

    private boolean enabled = false, zWasDown = false, xWasDown = false, wasAlive = false;
    private boolean holdingSprint = false, holdingSneak = false;
    private LivingEntity target = null;
    private Vec3d aimOffset = Vec3d.ZERO;

    private long acquireTime, reactDelay, rotPauseUntil, nextShift, nextAtk, postKillUntil, breakUntil;
    private long switchTime, lockAtkUntil, sprintResume, sneakResume, nextF12Time = 0, nextFatigueShift = 0;
    private int pendingSlot = -1, kills = 0, killsGoal = 14, attackStreak = 0;
    private double curRange = 2.92, fatigueMultiplier = 1.0, swayPhase = 0.0;

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

        // Tự động bấm F12 ngẫu nhiên từ 5p00s đến 5p20s kèm nhiễu mili-giây
        if (nextF12Time == 0) {
            scheduleNextF12(now);
        } else if (now >= nextF12Time) {
            if (mc.currentScreen == null) {
                mc.keyboard.onKey(win, GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_PRESS, 0);
                mc.keyboard.onKey(win, GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_RELEASE, 0);
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
            releaseTempKeys(mc, p);
            msg(p, enabled ? "[WitherAura] ĐÃ BẬT (Anti-Bot v5)" : "[WitherAura] ĐÃ TẮT", enabled ? Formatting.GREEN : Formatting.RED);
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

        // Khôi phục phím chạy và phím ngồi giả lập
        if (holdingSprint && now >= sprintResume) {
            holdingSprint = false;
            if (mc.options.forwardKey.isPressed()) p.setSprinting(true);
        }
        if (holdingSneak && now >= sneakResume) {
            holdingSneak = false;
            mc.options.sneakKey.setPressed( false );
        }

        if (p.getHealth() <= cfg.minHealth || (cfg.panicPlayer && hasNearPlayer(mc, p))) {
            enabled = false;
            target = null;
            releaseTempKeys(mc, p);
            msg(p, "[WitherAura] Tự ngắt an toàn!", Formatting.RED);
            return;
        }
        if (mc.currentScreen != null) return;

        // Cập nhật chu kỳ mỏi tay (Fatigue Wave) mỗi 25 - 75 giây để không bao giờ giữ nhịp CPS cố định
        if (now >= nextFatigueShift) {
            fatigueMultiplier = 0.82 + rng.nextDouble() * 0.55; // Dao động từ 82% đến 137% tốc độ gốc
            nextFatigueShift = now + gauss(25000, 75000);
        }

        // Nhịp thở chuột liên tục (Chống cờ đỏ Yaw/Pitch cố định tuyệt đối)
        applyHumanBreathingSway(mc, p);

        if (now < breakUntil || now < postKillUntil) {
            // Thỉnh thoảng nhấp Shift nhẹ hoặc lia mắt khi đang nghỉ giữa các đợt quái
            if (rng.nextDouble() < 0.012 && !holdingSneak) {
                holdingSneak = true;
                mc.options.sneakKey.setPressed(true);
                sneakResume = now + gauss(140, 420);
            }
            return;
        }

        if (target != null && wasAlive && !target.isAlive()) {
            wasAlive = false;
            target = null;
            postKillUntil = now + gauss(cfg.minPostKillMs, cfg.maxPostKillMs);
            if (++kills >= killsGoal) {
                kills = 0;
                killsGoal = gauss(9, 22);
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
            reactDelay = (long) (gauss(cfg.minReactMs, cfg.maxReactMs) * fatigueMultiplier);
            curRange = cfg.minRange + rng.nextDouble() * (cfg.maxRange - cfg.minRange);
            shiftAim(now);
        }
        if (now >= nextShift) shiftAim(now);

        Vec3d visPt = getVisiblePoint(mc, p, target);
        if (visPt == null) { target = null; return; }

        if (cfg.autoSword) autoSwitchSword(p, now);
        if (isBroken(p.getMainHandStack())) return;

        // Xoay tâm siêu nhẹ và chỉ xoay khi tia nhìn sắp lệch khỏi vùng an toàn của Hitbox
        if (now >= rotPauseUntil) {
            if (rng.nextDouble() < 0.07) rotPauseUntil = now + gauss(35, 110);
            else rotateMinimalHuman(mc, p, target, visPt.add(aimOffset));
        }

        if (now - acquireTime < reactDelay || now < lockAtkUntil || now < nextAtk) return;

        // Ngưỡng chờ hồi chiêu biến thiên tự nhiên (không luôn cứng nhắc ở 100.0%)
        float cdReq = rng.nextDouble() < 0.62 ? 1.0f : (0.92f + rng.nextFloat() * 0.08f);
        if (p.getAttackCooldownProgress(0.5f) < cdReq) return;

        // Kiểm tra tia nhìn cắt qua Hitbox thật trước khi đánh (Hợp lệ 100% với GrimAC)
        Vec3d eye = p.getEyePos();
        Vec3d lookEnd = eye.add(p.getRotationVec(1.0f).multiply(curRange + 0.25));
        if (target.getBoundingBox().expand(0.10).raycast(eye, lookEnd).isEmpty()) return;

        // Mô phỏng người thật thỉnh thoảng bấm nhầm/vung kiếm hụt hoặc khựng tay vài giây
        if (rng.nextDouble() < cfg.missChance) {
            p.swingHand(Hand.MAIN_HAND);
            nextAtk = now + gauss(280, 850);
            return;
        }

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        attackStreak++;

        if (cfg.sprintReset && p.isSprinting() && rng.nextDouble() < 0.58) {
            p.setSprinting(false);
            holdingSprint = true;
            sprintResume = now + gauss(50, 155);
        }

        // Tính toán độ trễ đòn tiếp theo bằng Phân phối Hỗn hợp (Bimodal Gaussian + Gián đoạn tự nhiên)
        nextAtk = now + computeAntiMacroDelay();
    }

    /**
     * Thuật toán sinh độ trễ đa tầng chống phát hiện Macro / Auto-Clicker:
     * Kết hợp phân phối chuẩn lệch + chu kỳ mỏi tay + gián đoạn bất quy tắc
     */
    private long computeAntiMacroDelay() {
        double base = cfg.meanDelayMs * fatigueMultiplier;
        double spread = cfg.stdDelayMs * (0.85 + rng.nextDouble() * 0.45);
        long delay = Math.round(base + rng.nextGaussian() * spread);

        // 15% cơ hội rơi vào nhịp bấm chậm hơn bình thường (mô phỏng phân phối 2 đỉnh của tay người)
        if (rng.nextDouble() < 0.15) {
            delay += gauss(110, 340);
        }

        // 5% cơ hội hoặc sau mỗi chuỗi đánh dài sẽ có nhịp khựng lớn (0.7s - 2.4s)
        if (rng.nextDouble() < 0.05 || attackStreak > gauss(18, 35)) {
            delay += gauss(700, 2400);
            attackStreak = 0;
        }

        return Math.max(28, delay + rng.nextInt(17));
    }

    private void scheduleNextF12(long now) {
        // Ngẫu nhiên từ 5 phút (300,000ms) đến 5 phút 20 giây (320,000ms) bằng phân phối Gaussian + Jitter
        long baseMs = gauss(300000, 320000);
        long subMsJitter = rng.nextInt(997) - 498;
        nextF12Time = now + Math.clamp(baseMs + subMsJitter, 300000L, 320000L);
    }

    /**
     * Chỉ xoay màn hình một góc cực nhỏ khi thật sự cần thiết.
     * Nếu tâm ngắm đã nằm sẵn trong khung người quái thì giữ nguyên màn hình, chỉ thêm độ rung tay tự nhiên.
     */
    private void rotateMinimalHuman(MinecraftClient mc, ClientPlayerEntity p, LivingEntity e, Vec3d pt) {
        Vec3d eye = p.getEyePos();
        Vec3d lookEnd = eye.add(p.getRotationVec(1.0f).multiply(curRange + 0.2));
        // Nếu tâm hiện tại đã chĩa trúng bên trong vùng lõi Hitbox -> Không kéo màn hình của người chơi
        if (e.getBoundingBox().contract(0.08).raycast(eye, lookEnd).isPresent()) {
            return;
        }

        float[] ang = angleTo(p, pt);
        float yStep = cfg.minYaw + rng.nextFloat() * (cfg.maxYaw - cfg.minYaw);
        float pStep = cfg.minPitch + rng.nextFloat() * (cfg.maxPitch - cfg.minPitch);
        float dYaw = MathHelper.clamp(ang[2] * (0.35f + rng.nextFloat() * 0.25f), -yStep, yStep) + (float) (rng.nextGaussian() * 0.18f);
        float dPitch = MathHelper.clamp(ang[3] * (0.30f + rng.nextFloat() * 0.22f), -pStep, pStep) + (float) (rng.nextGaussian() * 0.14f);

        applyGcdRotation(mc, p, dYaw, dPitch);
    }

    /**
     * Tạo dao động tay cầm chuột siêu nhỏ liên tục để tọa độ Yaw/Pitch không bao giờ đứng yên tuyệt đối
     */
    private void applyHumanBreathingSway(MinecraftClient mc, ClientPlayerEntity p) {
        swayPhase += 0.09 + rng.nextDouble() * 0.07;
        if (rng.nextDouble() < 0.42) {
            float swayYaw = (float) (Math.sin(swayPhase) * 0.08 + rng.nextGaussian() * 0.11);
            float swayPitch = (float) (Math.cos(swayPhase * 0.8) * 0.06 + rng.nextGaussian() * 0.08);
            applyGcdRotation(mc, p, swayYaw, swayPitch);
        }
    }

    private void applyGcdRotation(MinecraftClient mc, ClientPlayerEntity p, float dYaw, float dPitch) {
        float gcd = getGcd(mc);
        float fixedYaw = dYaw - (dYaw % gcd);
        float fixedPitch = dPitch - (dPitch % gcd);
        p.setYaw(p.getYaw() + fixedYaw);
        p.setPitch(MathHelper.clamp(p.getPitch() + fixedPitch, -89.9f, 89.9f));
    }

    private LivingEntity findTarget(MinecraftClient mc, ClientPlayerEntity p) {
        double rSq = (curRange + 0.35) * (curRange + 0.35);
        return StreamSupport.stream(mc.world.getEntities().spliterator(), false)
                .filter(e -> e != p && e instanceof LivingEntity le && le.isAlive() && !e.isRemoved() && validMob(le))
                .map(e -> (LivingEntity) e)
                .filter(e -> getClosestPoint(p.getEyePos(), e.getBoundingBox()).squaredDistanceTo(p.getEyePos()) <= rSq)
                .filter(e -> angleTo(p, e.getBoundingBox().getCenter())[0] <= cfg.fov * 0.5f)
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
        for (double f : new double[]{0.52, 0.32, 0.72, 0.18, 0.84}) {
            Vec3d pt = new Vec3d(MathHelper.lerp(0.35, closest.x, b.getCenter().x), b.minY + b.getLengthY() * f, MathHelper.lerp(0.35, closest.z, b.getCenter().z));
            if (eye.squaredDistanceTo(pt) <= (curRange + 0.3) * (curRange + 0.3)) {
                if (mc.world.raycast(new RaycastContext(eye, pt, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.MISS)
                    return pt;
            }
        }
        return p.canSee(e) ? b.getCenter() : null;
    }

    private Vec3d getClosestPoint(Vec3d pos, Box box) {
        return new Vec3d(MathHelper.clamp(pos.x, box.minX, box.maxX), MathHelper.clamp(pos.y, box.minY, box.maxY), MathHelper.clamp(pos.z, box.minZ, box.maxZ));
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
        if (pendingSlot != best) { pendingSlot = best; switchTime = now + gauss(95, 245); return; }
        if (now >= switchTime) {
            p.getInventory().selectedSlot = pendingSlot;
            pendingSlot = -1;
            lockAtkUntil = now + gauss(95, 240);
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
        nextShift = now + gauss(130, 440);
    }

    private void releaseTempKeys(MinecraftClient mc, ClientPlayerEntity p) {
        holdingSprint = false;
        if (holdingSneak) {
            holdingSneak = false;
            mc.options.sneakKey.setPressed(false);
        }
    }

    private float getGcd(MinecraftClient mc) {
        double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    private int gauss(int min, int max) {
        return min >= max ? min : MathHelper.clamp((int) Math.round((min + max) / 2.0 + rng.nextGaussian() * ((max - min) / 3.8)), min, max);
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
        public double minRange = 2.82, maxRange = 2.98, aimOffset = 0.16, missChance = 0.04, panicRadius = 15.0;
        public float fov = 150.0f, minYaw = 11.0f, maxYaw = 26.0f, minPitch = 8.0f, maxPitch = 19.0f, minHealth = 6.0f;
        public int meanDelayMs = 95, stdDelayMs = 52, minReactMs = 120, maxReactMs = 290, minPostKillMs = 110, maxPostKillMs = 310, minBreakMs = 1800, maxBreakMs = 4800, minDurability = 12;

        public static ModConfig load() {
            if (FILE.exists()) try (FileReader r = new FileReader(FILE)) { return GSON.fromJson(r, ModConfig.class); } catch (Exception ignored) {}
            ModConfig c = new ModConfig(); c.save(); return c;
        }
        public void save() { try (FileWriter w = new FileWriter(FILE)) { GSON.toJson(this, w); } catch (Exception ignored) {} }
    }
            }
    
