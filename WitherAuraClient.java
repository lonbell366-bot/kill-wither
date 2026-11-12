package com.example.witheraura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class WitherAuraClient implements ClientModInitializer {
    private static ModConfig cfg;
    private static KeyBinding keyZ, keyX;
    private final Random rng = new Random();

    private boolean enabled = false, zWasDown = false, xWasDown = false, holdingSprint = false;
    private LivingEntity target = null;
    private Vec3d aimOffset = Vec3d.ZERO;

    private long sessionStartTime = 0, acquireTime, reactDelay, nextShift, nextAtk, switchTime, lockAtkUntil, sprintResume, nextF12Time = 0;
    private int pendingSlot = -1;
    private double curRange = 2.95, swayPhase = 0.0, totalMoney = 0.0;

    // Bắt số tiền từ thông báo chat/Auto Sell (Ví dụ: "$ 300K", "+$1.5M", "$50,000")
    private static final Pattern MONEY_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:[.,][0-9]+)?)\\s*([kKmMbB]?)");

    @Override
    public void onInitializeClient() {
        cfg = ModConfig.load();
        sessionStartTime = System.currentTimeMillis();

        keyZ = KeyBindingHelper.registerKeyBinding(new KeyBinding("Bật/Tắt WitherAura (Z)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "Wither Utility"));
        keyX = KeyBindingHelper.registerKeyBinding(new KeyBinding("Đổi Chế Độ Mục Tiêu (X)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "Wither Utility"));

        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::renderHud);

        // Lắng nghe tin nhắn game để cộng tiền bán được
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> parseMoneyChat(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> parseMoneyChat(message.getString()));
    }

    private void parseMoneyChat(String rawText) {
        if (rawText == null || rawText.contains("[WitherAura]")) return;
        String clean = Formatting.strip(rawText);
        if (clean == null) return;

        Matcher m = MONEY_PATTERN.matcher(clean);
        if (m.find()) {
            try {
                double val = Double.parseDouble(m.group(1).replace(",", ""));
                String unit = m.group(2).toUpperCase();
                if ("K".equals(unit)) val *= 1_000.0;
                else if ("M".equals(unit)) val *= 1_000_000.0;
                else if ("B".equals(unit)) val *= 1_000_000_000.0;
                if (val > 0) totalMoney += val;
            } catch (Exception ignored) {}
        }
    }

    // 1. VẼ BẢNG HUD Ở GÓC TRÊN CÙNG BÊN TRÁI
    private void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || !cfg.showHud) return;

        long elapsedMs = Math.max(1000L, System.currentTimeMillis() - sessionStartTime);
        double hours = elapsedMs / 3600000.0;
        double perHour = totalMoney / hours;

        String line1 = "Thời gian: " + formatDuration(elapsedMs);
        String line2 = "Farm/giờ: $" + formatMoney(perHour) + "/h (Tổng: $" + formatMoney(totalMoney) + ")";
        String line3 = "KillAura: " + (enabled ? "BẬT" : "TẮT") + (cfg.onlyWither ? " [Wither]" : " [Mọi Quái]");

        int w = Math.max(mc.textRenderer.getWidth(line2), Math.max(mc.textRenderer.getWidth(line1), mc.textRenderer.getWidth(line3))) + 10;
        ctx.fill(4, 4, 4 + w, 40, 0x88000000);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(line1), 8, 7, 0xFFFFFF);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(line2), 8, 18, 0x55FF55);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(line3), 8, 29, enabled ? 0xFFFF55 : 0xFF5555);
    }

    private void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        ClientPlayerEntity p = mc.player;
        long win = mc.getWindow().getHandle(), now = System.currentTimeMillis();

        // Random nhấn F12 từ 5p00s đến 5p20s
        if (nextF12Time == 0) scheduleNextF12(now);
        else if (now >= nextF12Time) {
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
            msg(p, enabled ? "[WitherAura] ĐÃ BẬT (Phím Z)" : "[WitherAura] ĐÃ TẮT (Phím Z)", enabled ? Formatting.GREEN : Formatting.RED);
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
            msg(p, "[WitherAura] Tự ngắt an toàn!", Formatting.RED);
            return;
        }
        if (mc.currentScreen != null) return;

        // 2. NHƯỜNG QUYỀN CHO AUTO EAT: Nếu đang cầm đồ ăn khi đói hoặc đang nhai đồ ăn -> Dừng chém & không giành ô hotbar
        if (isEatingOrHoldingFood(mc, p)) {
            pendingSlot = -1;
            return;
        }

        // Nhịp thở chuột siêu nhẹ chống đứng im tọa độ Yaw/Pitch
        applyHumanBreathingSway(mc, p);

        // Ngay sau khi ăn xong -> Tự động chuyển lại về ô cầm kiếm tốt nhất rồi mới chém tiếp
        if (cfg.autoSword && !isHoldingSword(p)) {
            autoSwitchSword(p, now);
            return;
        }

        LivingEntity best = findTarget(mc, p);
        if (best == null) { target = null; return; }
        if (best != target) {
            target = best;
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

        rotateMinimalHuman(mc, p, target, visPt.add(aimOffset));

        // 3. TỐC ĐỘ CHÉM CHUẨN: Đánh ngay khi thanh hồi chiêu đầy 100% + độ lệch cực ngắn (15ms - 65ms)
        if (now - acquireTime < reactDelay || now < lockAtkUntil || now < nextAtk) return;
        if (p.getAttackCooldownProgress(0.5f) < 1.0f) return;

        Vec3d eye = p.getEyePos();
        Vec3d lookEnd = eye.add(p.getRotationVec(1.0f).multiply(curRange + 0.25));
        if (target.getBoundingBox().expand(0.12).raycast(eye, lookEnd).isEmpty()) return;

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);

        if (cfg.sprintReset && p.isSprinting() && rng.nextDouble() < 0.60) {
            p.setSprinting(false);
            holdingSprint = true;
            sprintResume = now + gauss(45, 115);
        }

        // Độ trễ ngẫu nhiên nhỏ sau khi hồi đầy thanh chém (trung bình ~35ms, không bao giờ khựng 2 giây)
        nextAtk = now + gauss(cfg.minAttackJitterMs, cfg.maxAttackJitterMs);
    }

    /**
     * Kiểm tra nhân vật đang cầm đồ ăn để ăn hoặc đang trong quá trình ăn của Auto Eat
     */
    private boolean isEatingOrHoldingFood(MinecraftClient mc, ClientPlayerEntity p) {
        ItemStack main = p.getMainHandStack();
        ItemStack off = p.getOffHandStack();
        boolean mainIsFood = main.contains(DataComponentTypes.FOOD);
        boolean offIsFood = off.contains(DataComponentTypes.FOOD);

        // Đang bấm chuột phải ăn đồ ăn ở tay chính hoặc tay phụ
        if (p.isUsingItem() && (mainIsFood || offIsFood)) return true;
        if (mc.options.useKey.isPressed() && (mainIsFood || offIsFood)) return true;

        // Auto Eat vừa chuyển tay chính sang đồ ăn vì thanh thức ăn chưa đầy
        return mainIsFood && p.getHungerManager().isNotFull();
    }

    private boolean isHoldingSword(ClientPlayerEntity p) {
        ItemStack s = p.getMainHandStack();
        return s.getItem() instanceof SwordItem || s.isIn(ItemTags.SWORDS);
    }

    private void scheduleNextF12(long now) {
        nextF12Time = now + gauss(300000, 320000);
    }

    private void rotateMinimalHuman(MinecraftClient mc, ClientPlayerEntity p, LivingEntity e, Vec3d pt) {
        Vec3d eye = p.getEyePos();
        Vec3d lookEnd = eye.add(p.getRotationVec(1.0f).multiply(curRange + 0.2));
        if (e.getBoundingBox().contract(0.06).raycast(eye, lookEnd).isPresent()) return;

        float[] ang = angleTo(p, pt);
        float yStep = cfg.minYaw + rng.nextFloat() * (cfg.maxYaw - cfg.minYaw);
        float pStep = cfg.minPitch + rng.nextFloat() * (cfg.maxPitch - cfg.minPitch);
        float dYaw = MathHelper.clamp(ang[2] * (0.45f + rng.nextFloat() * 0.25f), -yStep, yStep) + (float) (rng.nextGaussian() * 0.15f);
        float dPitch = MathHelper.clamp(ang[3] * (0.40f + rng.nextFloat() * 0.22f), -pStep, pStep) + (float) (rng.nextGaussian() * 0.12f);
        applyGcdRotation(mc, p, dYaw, dPitch);
    }

    private void applyHumanBreathingSway(MinecraftClient mc, ClientPlayerEntity p) {
        swayPhase += 0.09 + rng.nextDouble() * 0.07;
        if (rng.nextDouble() < 0.40) {
            float swayYaw = (float) (Math.sin(swayPhase) * 0.07 + rng.nextGaussian() * 0.09);
            float swayPitch = (float) (Math.cos(swayPhase * 0.8) * 0.05 + rng.nextGaussian() * 0.07);
            applyGcdRotation(mc, p, swayYaw, swayPitch);
        }
    }

    private void applyGcdRotation(MinecraftClient mc, ClientPlayerEntity p, float dYaw, float dPitch) {
        float gcd = getGcd(mc);
        p.setYaw(p.getYaw() + dYaw - (dYaw % gcd));
        p.setPitch(MathHelper.clamp(p.getPitch() + dPitch - (dPitch % gcd), -89.9f, 89.9f));
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
        if (pendingSlot != best) { pendingSlot = best; switchTime = now + gauss(50, 120); return; }
        if (now >= switchTime) {
            p.getInventory().selectedSlot = pendingSlot;
            pendingSlot = -1;
            lockAtkUntil = now + gauss(50, 120);
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
        nextShift = now + gauss(140, 420);
    }

    private float getGcd(MinecraftClient mc) {
        double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    private int gauss(int min, int max) {
        return min >= max ? min : MathHelper.clamp((int) Math.round((min + max) / 2.0 + rng.nextGaussian() * ((max - min) / 3.8)), min, max);
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private String formatMoney(double val) {
        if (val >= 1_000_000_000) return String.format("%.2fB", val / 1_000_000_000.0);
        if (val >= 1_000_000) return String.format("%.2fM", val / 1_000_000.0);
        if (val >= 1_000) return String.format("%.1fK", val / 1_000.0);
        return String.format("%.0f", val);
    }

    private void msg(ClientPlayerEntity p, String text, Formatting color) {
        if (!cfg.silentMode) {
            p.sendMessage(Text.literal(text).formatted(color), true);
            p.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
        }
    }

    public static class ModConfig {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "wither_aura_v6.json");
        public boolean onlyWither = true, autoSword = true, sprintReset = true, panicPlayer = false, silentMode = false, showHud = true;
        public String priority = "DISTANCE";
        public double minRange = 2.85, maxRange = 3.00, aimOffset = 0.15, panicRadius = 15.0;
        public float fov = 160.0f, minYaw = 14.0f, maxYaw = 30.0f, minPitch = 10.0f, maxPitch = 22.0f, minHealth = 6.0f;
        public int minAttackJitterMs = 15, maxAttackJitterMs = 65, minReactMs = 30, maxReactMs = 85, minDurability = 12;

        public static ModConfig load() {
            if (FILE.exists()) try (FileReader r = new FileReader(FILE)) { return GSON.fromJson(r, ModConfig.class); } catch (Exception ignored) {}
            ModConfig c = new ModConfig(); c.save(); return c;
        }
        public void save() { try (FileWriter w = new FileWriter(FILE)) { GSON.toJson(this, w); } catch (Exception ignored) {} }
    }
    }
                                
