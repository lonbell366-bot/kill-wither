package com.example.witheraura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
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
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WitherAuraClient implements ClientModInitializer {
    private static ModConfig cfg;
    private static KeyBinding keyZ, keyX, keyF9;
    private final Random rng = new Random();

    private boolean enabled = false, zWasDown = false, xWasDown = false, f9WasDown = false, holdingSprint = false, noRenderApplied = false;
    private int savedViewDist = 8, savedBiomeBlend = 2, pendingSlot = -1, tickCount = 0, hudWidth = 180;
    private double savedEntityScale = 1.0, pendingPayAmount = 0.0, curRange = 2.95, swayPhase = 0.0;
    private double totalMoney = 0.0, currentBalance = 0.0, totalPaidMoney = 0.0;
    private boolean savedShadows = true, savedBob = true, savedAo = true;
    private String hudL1 = "", hudL2 = "", hudL3 = "", hudL4 = "", hudL5 = "";

    private LivingEntity target = null;
    private Vec3d aimOffset = Vec3d.ZERO;
    private long sessionStartTime = 0, acquireTime, reactDelay, nextShift, nextAtk, switchTime, lockAtkUntil, sprintResume, nextF12Time = 0;
    private long scheduledPayTime = 0, lastPayExecTime = 0;

    private static final Pattern MONEY_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:[.,][0-9]+)?)\\s*([kKmMbB]?)");
    private static final Pattern SB_BALANCE_PATTERN = Pattern.compile("(?:^|Money:?\\s*|Balance:?\\s*|\\s)\\$\\s*([0-9]+(?:[.,][0-9]+)?)\\s*([kKmMbB]?)(?:\\b|$)", Pattern.CASE_INSENSITIVE);

    @Override
    public void onInitializeClient() {
        cfg = ModConfig.load();
        resetSessionStats();
        keyZ = KeyBindingHelper.registerKeyBinding(new KeyBinding("Bật/Tắt WitherAura (Z)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "Wither Utility"));
        keyX = KeyBindingHelper.registerKeyBinding(new KeyBinding("Đổi Chế Độ Mục Tiêu (X)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "Wither Utility"));
        keyF9 = KeyBindingHelper.registerKeyBinding(new KeyBinding("Mở Menu Cài Đặt (F9)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "Wither Utility"));

        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> resetSessionStats());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> resetSessionStats());
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> parseMoneyChat(msg.getString()));
        ClientReceiveMessageEvents.CHAT.register((msg, s, sender, p, t) -> parseMoneyChat(msg.getString()));
    }

    public void resetSessionStats() {
        sessionStartTime = System.currentTimeMillis();
        totalMoney = 0.0;
        currentBalance = 0.0;
        totalPaidMoney = 0.0;
        scheduledPayTime = 0;
        pendingPayAmount = 0.0;
    }

    public void syncNoRenderOptions(MinecraftClient mc) {
        if (mc.options == null) return;
        if (cfg.noRender && !noRenderApplied) {
            savedViewDist = mc.options.getViewDistance().getValue();
            savedEntityScale = mc.options.getEntityDistanceScaling().getValue();
            savedShadows = mc.options.getEntityShadows().getValue();
            savedBob = mc.options.getBobView().getValue();
            savedAo = mc.options.getAo().getValue();
            savedBiomeBlend = mc.options.getBiomeBlendRadius().getValue();

            mc.options.getViewDistance().setValue(2);
            mc.options.getEntityDistanceScaling().setValue(0.5);
            mc.options.getEntityShadows().setValue(false);
            mc.options.getBobView().setValue(false);
            mc.options.getAo().setValue(false);
            mc.options.getBiomeBlendRadius().setValue(0);
            noRenderApplied = true;
        } else if (!cfg.noRender && noRenderApplied) {
            mc.options.getViewDistance().setValue(savedViewDist);
            mc.options.getEntityDistanceScaling().setValue(savedEntityScale);
            mc.options.getEntityShadows().setValue(savedShadows);
            mc.options.getBobView().setValue(savedBob);
            mc.options.getAo().setValue(savedAo);
            mc.options.getBiomeBlendRadius().setValue(savedBiomeBlend);
            noRenderApplied = false;
        }
    }

    private double extractMoneyValue(String numStr, String unitStr) {
        double v = Double.parseDouble(numStr.replace(",", ""));
        String u = unitStr.toUpperCase(Locale.ROOT);
        if ("K".equals(u)) v *= 1e3;
        else if ("M".equals(u)) v *= 1e6;
        else if ("B".equals(u)) v *= 1e9;
        return v;
    }

    private void updateBalanceFromScoreboard(MinecraftClient mc) {
        if (mc.world == null || System.currentTimeMillis() - lastPayExecTime < 4000) return;
        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return;
        int checked = 0;
        for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
            if (++checked > 15) break;
            Team team = sb.getScoreHolderTeam(entry.owner());
            String line = Formatting.strip(Team.decorateName(team, entry.name()).getString());
            if (line != null && line.contains("$")) {
                Matcher m = SB_BALANCE_PATTERN.matcher(line.trim());
                if (m.find()) {
                    try {
                        currentBalance = extractMoneyValue(m.group(1), m.group(2));
                        return;
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void parseMoneyChat(String raw) {
        if (raw == null || !raw.contains("$") || raw.contains("[WitherAura]")) return;
        String clean = Formatting.strip(raw);
        if (clean == null) return;
        String low = clean.toLowerCase(Locale.ROOT);

        Matcher m = MONEY_PATTERN.matcher(clean);
        if (!m.find()) return;
        try {
            double v = extractMoneyValue(m.group(1), m.group(2));
            if (v <= 0) return;
            if (low.contains("balance") || low.contains("số dư") || low.contains("you have $")) {
                currentBalance = v;
                return;
            }
            if (low.contains("paid ") || low.contains("sent ") || low.contains("đã chuyển")) return;
            totalMoney += v;
            currentBalance += v;
        } catch (Exception ignored) {}
    }

    private void updateHudCache(MinecraftClient mc) {
        long ms = Math.max(1000L, System.currentTimeMillis() - sessionStartTime);
        double perHour = totalMoney / (ms / 3600000.0);
        hudL1 = "⏱ Thời gian: " + formatDuration(ms);
        hudL2 = "💰 Farm/giờ: $" + formatMoney(perHour) + "/h (Tổng: $" + formatMoney(totalMoney) + ")";
        hudL3 = "💸 Số dư Pay (" + (cfg.autoPay ? "BẬT" : "TẮT") + "): $" + formatMoney(currentBalance) + " / $" + formatMoney(cfg.payTrigger);
        hudL4 = "✅ Đã Pay: $" + formatMoney(totalPaidMoney) + " -> " + (cfg.payTarget.isBlank() ? "Chưa đặt" : cfg.payTarget);
        hudL5 = "⚔ KillAura: " + (enabled ? "BẬT" : "TẮT") + (cfg.onlyWither ? " [Wither]" : " [Mọi Quái]") + " | [F9] Menu";
        hudWidth = Math.max(mc.textRenderer.getWidth(hudL1), Math.max(mc.textRenderer.getWidth(hudL2),
                Math.max(mc.textRenderer.getWidth(hudL3), Math.max(mc.textRenderer.getWidth(hudL4), mc.textRenderer.getWidth(hudL5))))) + 14;
    }

    private void renderHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        if (cfg.noRender && mc.currentScreen == null) {
            int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
            ctx.fill(0, 0, sw, sh, 0xFF05070B);
            ctx.drawTextWithShadow(mc.textRenderer, "⚡ NO RENDER ULTRA FPS MODE (F9 để tắt) ⚡", sw / 2 - 110, sh / 2 - 6, 0x00E5FF);
        }

        if (!cfg.showHud || hudL1.isEmpty()) return;
        ctx.fill(5, 5, 5 + hudWidth, 67, 0xCC0D1017);
        ctx.fill(5, 5, 7, 67, 0xFF00E5FF);
        ctx.drawTextWithShadow(mc.textRenderer, hudL1, 11, 9, 0xE0F7FA);
        ctx.drawTextWithShadow(mc.textRenderer, hudL2, 11, 20, 0x69F0AE);
        ctx.drawTextWithShadow(mc.textRenderer, hudL3, 11, 31, 0xFFD740);
        ctx.drawTextWithShadow(mc.textRenderer, hudL4, 11, 42, 0xEA80FC);
        ctx.drawTextWithShadow(mc.textRenderer, hudL5, 11, 53, enabled ? 0x00E5FF : 0xFF5252);
    }
        private void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        ClientPlayerEntity p = mc.player;
        long win = mc.getWindow().getHandle(), now = System.currentTimeMillis();

        syncNoRenderOptions(mc);
        if (++tickCount % 10 == 0) updateHudCache(mc);
        if (tickCount % 40 == 0) updateBalanceFromScoreboard(mc);

        if (nextF12Time == 0) nextF12Time = now + gauss(300000, 320000);
        else if (now >= nextF12Time) {
            if (mc.currentScreen == null) {
                mc.keyboard.onKey(win, GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_PRESS, 0);
                mc.keyboard.onKey(win, GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_RELEASE, 0);
            }
            nextF12Time = now + gauss(300000, 320000);
        }

        boolean zDown = mc.currentScreen == null && InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_Z);
        boolean xDown = mc.currentScreen == null && InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_X);
        boolean f9Down = mc.currentScreen == null && InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_F9);
        boolean tZ = (zDown && !zWasDown), tX = (xDown && !xWasDown), tF9 = (f9Down && !f9WasDown);
        while (keyZ.wasPressed()) tZ = true;
        while (keyX.wasPressed()) tX = true;
        while (keyF9.wasPressed()) tF9 = true;
        zWasDown = zDown; xWasDown = xDown; f9WasDown = f9Down;

        if (tF9) { mc.setScreen(new WitherSettingsScreen(this)); return; }
        if (tZ) { enabled = !enabled; target = null; updateHudCache(mc); msg(p, enabled ? "[WitherAura] ĐÃ BẬT (Z)" : "[WitherAura] ĐÃ TẮT (Z)", enabled ? Formatting.GREEN : Formatting.RED); }
        if (tX) { cfg.onlyWither = !cfg.onlyWither; cfg.save(); target = null; updateHudCache(mc); msg(p, "[WitherAura] Chế độ: " + (cfg.onlyWither ? "CHỈ WITHER" : "MỌI QUÁI"), Formatting.GOLD); }

        handleAutoPay(mc, p, now);
        if (!enabled) return;
        if (holdingSprint && now >= sprintResume) { holdingSprint = false; if (mc.options.forwardKey.isPressed()) p.setSprinting(true); }
        if (p.getHealth() <= cfg.minHealth || (cfg.panicPlayer && hasNearPlayer(mc, p))) { enabled = false; target = null; msg(p, "[WitherAura] Tự ngắt an toàn!", Formatting.RED); return; }
        if (mc.currentScreen != null) return;
        if (isEatingOrHoldingFood(mc, p)) { pendingSlot = -1; return; }

        applyHumanBreathingSway(mc, p);
        if (cfg.autoSword && !isHoldingSword(p)) { autoSwitchSword(p, now); return; }

        if (target == null || !target.isAlive() || target.isRemoved() || p.squaredDistanceTo(target) > (curRange + 0.5) * (curRange + 0.5)) {
            LivingEntity best = findTargetFast(mc, p);
            if (best == null) { target = null; return; }
            target = best;
            acquireTime = now;
            reactDelay = gauss(cfg.minReactMs, cfg.maxReactMs);
            curRange = cfg.minRange + rng.nextDouble() * (cfg.maxRange - cfg.minRange);
            shiftAim(now);
        }

        if (now >= nextShift) shiftAim(now);
        Vec3d visPt = getVisiblePointFast(mc, p, target);
        if (visPt == null) { target = null; return; }
        if (cfg.autoSword) autoSwitchSword(p, now);
        if (isBroken(p.getMainHandStack())) return;

        rotateMinimalHuman(mc, p, target, visPt.add(aimOffset));
        if (now - acquireTime < reactDelay || now < lockAtkUntil || now < nextAtk) return;
        if (p.getAttackCooldownProgress(0.5f) < 1.0f) return;

        Vec3d eye = p.getEyePos();
        if (target.getBoundingBox().expand(0.12).raycast(eye, eye.add(p.getRotationVec(1.0f).multiply(curRange + 0.25))).isEmpty()) return;

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        if (cfg.sprintReset && p.isSprinting() && rng.nextDouble() < 0.60) { p.setSprinting(false); holdingSprint = true; sprintResume = now + gauss(45, 115); }
        nextAtk = now + gauss(cfg.minAttackJitterMs, cfg.maxAttackJitterMs);
    }

    private LivingEntity findTargetFast(MinecraftClient mc, ClientPlayerEntity p) {
        Box box = p.getBoundingBox().expand(curRange + 0.4);
        List<LivingEntity> list = mc.world.getEntitiesByClass(LivingEntity.class, box,
                le -> le != p && le.isAlive() && !le.isRemoved() && (cfg.onlyWither ? le instanceof WitherSkeletonEntity : (le instanceof HostileEntity && !(le instanceof PlayerEntity))));
        LivingEntity best = null;
        double bestVal = Double.MAX_VALUE;
        boolean byHealth = "HEALTH".equalsIgnoreCase(cfg.priority);
        for (LivingEntity le : list) {
            if (angleTo(p, le.getBoundingBox().getCenter())[0] > cfg.fov * 0.5f) continue;
            if (getVisiblePointFast(mc, p, le) == null) continue;
            double val = byHealth ? le.getHealth() : p.squaredDistanceTo(le);
            if (val < bestVal) { bestVal = val; best = le; }
        }
        return best;
    }

    private Vec3d getVisiblePointFast(MinecraftClient mc, ClientPlayerEntity p, Entity e) {
        Box b = e.getBoundingBox();
        Vec3d center = b.getCenter();
        if (p.canSee(e)) return center;
        Vec3d eye = p.getEyePos();
        Vec3d feet = new Vec3d(center.x, b.minY + b.getLengthY() * 0.3, center.z);
        if (mc.world.raycast(new RaycastContext(eye, feet, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.MISS) return feet;
        return null;
    }

    private void handleAutoPay(MinecraftClient mc, ClientPlayerEntity p, long now) {
        if (!cfg.autoPay || cfg.payTarget == null || cfg.payTarget.trim().isEmpty() || now - lastPayExecTime < 8000) return;
        if (scheduledPayTime == 0 && currentBalance >= cfg.payTrigger) {
            double minP = Math.min(cfg.payMin, cfg.payMax), maxP = Math.max(cfg.payMin, cfg.payMax);
            pendingPayAmount = Math.min(minP + rng.nextDouble() * (maxP - minP), currentBalance);
            scheduledPayTime = now + gauss(1800, 4500);
        }
        if (scheduledPayTime > 0 && now >= scheduledPayTime && mc.currentScreen == null) {
            executePayCommand(p, pendingPayAmount);
            lastPayExecTime = now; scheduledPayTime = 0; pendingPayAmount = 0.0;
            lockAtkUntil = now + gauss(350, 750);
        }
    }

    public void executePayCommand(ClientPlayerEntity p, double amount) {
        if (cfg.payTarget == null || cfg.payTarget.trim().isEmpty()) { msg(p, "[WitherAura] Chưa nhập tên người nhận (F9)!", Formatting.RED); return; }
        double actualPaid; String amtStr;
        if (cfg.useShortPayFormat && amount >= 1e6) {
            double m = Math.round((amount / 1e6) * 10.0) / 10.0;
            actualPaid = m * 1e6;
            amtStr = (m == (long) m) ? String.format(Locale.US, "%dM", (long) m) : String.format(Locale.US, "%.1fM", m);
        } else {
            long rounded = Math.round(amount); actualPaid = rounded; amtStr = String.valueOf(rounded);
        }
        totalPaidMoney += actualPaid;
        currentBalance = Math.max(0.0, currentBalance - actualPaid);
        String cmd = "pay " + cfg.payTarget.trim() + " " + amtStr;
        p.networkHandler.sendChatCommand(cmd);
        msg(p, "[WitherAura] Đã chuyển: /" + cmd + " | Còn lại: $" + formatMoney(currentBalance), Formatting.AQUA);
    }

    private boolean isEatingOrHoldingFood(MinecraftClient mc, ClientPlayerEntity p) {
        boolean mainFood = p.getMainHandStack().contains(DataComponentTypes.FOOD);
        boolean offFood = p.getOffHandStack().contains(DataComponentTypes.FOOD);
        if ((p.isUsingItem() || mc.options.useKey.isPressed()) && (mainFood || offFood)) return true;
        return mainFood && p.getHungerManager().isNotFull();
    }

    private boolean isHoldingSword(ClientPlayerEntity p) {
        ItemStack s = p.getMainHandStack();
        return s.getItem() instanceof SwordItem || s.isIn(ItemTags.SWORDS);
    }

    private void rotateMinimalHuman(MinecraftClient mc, ClientPlayerEntity p, LivingEntity e, Vec3d pt) {
        Vec3d eye = p.getEyePos();
        if (e.getBoundingBox().contract(0.06).raycast(eye, eye.add(p.getRotationVec(1.0f).multiply(curRange + 0.2))).isPresent()) return;
        float[] ang = angleTo(p, pt);
        float yStep = cfg.minYaw + rng.nextFloat() * (cfg.maxYaw - cfg.minYaw), pStep = cfg.minPitch + rng.nextFloat() * (cfg.maxPitch - cfg.minPitch);
        float dYaw = MathHelper.clamp(ang[2] * (0.45f + rng.nextFloat() * 0.25f), -yStep, yStep) + (float) (rng.nextGaussian() * 0.15f);
        float dPitch = MathHelper.clamp(ang[3] * (0.40f + rng.nextFloat() * 0.22f), -pStep, pStep) + (float) (rng.nextGaussian() * 0.12f);
        applyGcdRotation(mc, p, dYaw, dPitch);
    }

    private void applyHumanBreathingSway(MinecraftClient mc, ClientPlayerEntity p) {
        swayPhase += 0.09 + rng.nextDouble() * 0.07;
        if (rng.nextDouble() < 0.35) {
            applyGcdRotation(mc, p, (float) (Math.sin(swayPhase) * 0.07 + rng.nextGaussian() * 0.09), (float) (Math.cos(swayPhase * 0.8) * 0.05 + rng.nextGaussian() * 0.07));
        }
    }

    private void applyGcdRotation(MinecraftClient mc, ClientPlayerEntity p, float dYaw, float dPitch) {
        double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        float gcd = (float) (f * f * f * 1.2);
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
                var ench = s.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
                int loot = ench.getEnchantments().stream().filter(e -> e.matchesKey(Enchantments.LOOTING)).mapToInt(ench::getLevel).findFirst().orElse(0);
                double sc = loot * 100.0 + (s.getMaxDamage() - s.getDamage()) * 0.001;
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
        }
        if (best == -1 || p.getInventory().selectedSlot == best) { pendingSlot = -1; return; }
        if (pendingSlot != best) { pendingSlot = best; switchTime = now + gauss(50, 120); return; }
        if (now >= switchTime) { p.getInventory().selectedSlot = pendingSlot; pendingSlot = -1; lockAtkUntil = now + gauss(50, 120); }
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
    private int gauss(int min, int max) { return min >= max ? min : MathHelper.clamp((int) Math.round((min + max) / 2.0 + rng.nextGaussian() * ((max - min) / 3.8)), min, max); }
    private static String formatDuration(long ms) { long s = ms / 1000; return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60); }
    public static String formatMoney(double v) {
        if (v >= 1e9) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.US, "%.1fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.US, "%.1fK", v / 1e3);
        return String.format(Locale.US, "%.0f", v);
    }
    public static double parseMoneyInput(String in, double fb) {
        if (in == null || in.isBlank()) return fb;
        try {
            String s = in.trim().toUpperCase(Locale.ROOT).replace(",", "").replace("$", "");
            double m = 1.0;
            if (s.endsWith("B")) { m = 1e9; s = s.substring(0, s.length() - 1).trim(); }
            else if (s.endsWith("M")) { m = 1e6; s = s.substring(0, s.length() - 1).trim(); }
            else if (s.endsWith("K")) { m = 1e3; s = s.substring(0, s.length() - 1).trim(); }
            return Math.max(0.0, Double.parseDouble(s) * m);
        } catch (Exception e) { return fb; }
    }
    private void msg(ClientPlayerEntity p, String t, Formatting c) {
        if (!cfg.silentMode) { p.sendMessage(Text.literal(t).formatted(c), true); p.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f); }
    }

    public static class WitherSettingsScreen extends Screen {
        private final WitherAuraClient mod;
        private TextFieldWidget targetF, trigF, minF, maxF;
        protected WitherSettingsScreen(WitherAuraClient mod) { super(Text.literal("Settings")); this.mod = mod; }

        @Override
        protected void init() {
            int cx = this.width / 2, cy = this.height / 2, L = cx - 155, R = cx + 5;
            targetF = addField(L, cy - 58, cfg.payTarget);
            trigF = addField(R, cy - 58, formatMoney(cfg.payTrigger));
            minF = addField(L, cy - 18, formatMoney(cfg.payMin));
            maxF = addField(R, cy - 18, formatMoney(cfg.payMax));

            this.addDrawableChild(ButtonWidget.builder(txtPay(), b -> { cfg.autoPay = !cfg.autoPay; b.setMessage(txtPay()); }).dimensions(L, cy + 10, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(txtFmt(), b -> { cfg.useShortPayFormat = !cfg.useShortPayFormat; b.setMessage(txtFmt()); }).dimensions(R, cy + 10, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(txtAura(), b -> { mod.enabled = !mod.enabled; b.setMessage(txtAura()); }).dimensions(L, cy + 34, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(txtMode(), b -> { cfg.onlyWither = !cfg.onlyWither; b.setMessage(txtMode()); }).dimensions(R, cy + 34, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(txtHud(), b -> { cfg.showHud = !cfg.showHud; b.setMessage(txtHud()); }).dimensions(L, cy + 58, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(txtNoRender(), b -> {
                cfg.noRender = !cfg.noRender;
                cfg.save();
                mod.syncNoRenderOptions(MinecraftClient.getInstance());
                b.setMessage(txtNoRender());
            }).dimensions(R, cy + 58, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("💸 Test Pay Ngay").formatted(Formatting.AQUA), b -> {
                saveInputs();
                if (this.client != null && this.client.player != null) {
                    double mn = Math.min(cfg.payMin, cfg.payMax), mx = Math.max(cfg.payMin, cfg.payMax);
                    mod.executePayCommand(this.client.player, mn + mod.rng.nextDouble() * (mx - mn));
                }
            }).dimensions(L, cy + 82, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("🔄 Reset Toàn Bộ Thống Kê").formatted(Formatting.YELLOW), b -> mod.resetSessionStats()).dimensions(R, cy + 82, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("✔ LƯU & ĐÓNG (F9 / ESC)").formatted(Formatting.GREEN, Formatting.BOLD), b -> this.close()).dimensions(L, cy + 106, 310, 22).build());
        }

        private TextFieldWidget addField(int x, int y, String val) {
            TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, 150, 18, Text.empty());
            f.setMaxLength(32); f.setText(val); return this.addDrawableChild(f);
        }
        private Text txtPay() { return Text.literal("Auto Pay: " + (cfg.autoPay ? "BẬT ✔" : "TẮT ✖")).formatted(cfg.autoPay ? Formatting.GREEN : Formatting.RED); }
        private Text txtFmt() { return Text.literal("Kiểu lệnh: " + (cfg.useShortPayFormat ? "32.5M (Gọn)" : "Số nguyên")).formatted(Formatting.AQUA); }
        private Text txtAura() { return Text.literal("KillAura (Z): " + (mod.enabled ? "BẬT ✔" : "TẮT ✖")).formatted(mod.enabled ? Formatting.GREEN : Formatting.RED); }
        private Text txtMode() { return Text.literal("Mục tiêu: " + (cfg.onlyWither ? "Chỉ Wither" : "Mọi Quái")).formatted(Formatting.GOLD); }
        private Text txtHud() { return Text.literal("Bảng HUD: " + (cfg.showHud ? "HIỆN ✔" : "ẨN ✖")).formatted(cfg.showHud ? Formatting.GREEN : Formatting.GRAY); }
        private Text txtNoRender() { return Text.literal("No Render: " + (cfg.noRender ? "BẬT ✔" : "TẮT ✖")).formatted(cfg.noRender ? Formatting.LIGHT_PURPLE : Formatting.GRAY); }

        private void saveInputs() {
            if (targetF != null) cfg.payTarget = targetF.getText().trim();
            if (trigF != null) cfg.payTrigger = parseMoneyInput(trigF.getText(), cfg.payTrigger);
            if (minF != null) cfg.payMin = parseMoneyInput(minF.getText(), cfg.payMin);
            if (maxF != null) cfg.payMax = parseMoneyInput(maxF.getText(), cfg.payMax);
            cfg.save();
        }
        @Override public void close() { saveInputs(); super.close(); }
        @Override public void renderBackground(DrawContext ctx, int mx, int my, float d) {
            ctx.fill(0, 0, this.width, this.height, 0xA805070B);
            int cx = this.width / 2, cy = this.height / 2, x1 = cx - 170, y1 = cy - 120, x2 = cx + 170, y2 = cy + 136;
            ctx.fill(x1 - 2, y1 - 2, x2 + 2, y2 + 2, 0xFF00E5FF);
            ctx.fill(x1, y1, x2, y2, 0xF210141F);
            ctx.fill(x1, y1, x2, y1 + 28, 0xFF182032);
            ctx.fill(x1, y1 + 28, x2, y1 + 29, 0xFF00E5FF);
        }
        @Override public void render(DrawContext ctx, int mx, int my, float d) {
            super.render(ctx, mx, my, d);
            int cx = this.width / 2, cy = this.height / 2, L = cx - 155, R = cx + 5;
            String title = "⚡ WITHER AURA & AUTO PAY PANEL ⚡";
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(title).formatted(Formatting.AQUA, Formatting.BOLD), cx - this.textRenderer.getWidth(title) / 2, cy - 110, 0x00E5FF);
            String st = "Số dư: $" + formatMoney(mod.currentBalance) + "/$" + formatMoney(cfg.payTrigger) + " | Đã Pay: $" + formatMoney(mod.totalPaidMoney);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(st), cx - this.textRenderer.getWidth(st) / 2, cy - 86, 0x69F0AE);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("👤 Tên người nhận (/pay):"), L, cy - 70, 0xB0BEC5);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("🎯 Mốc Số Dư kích hoạt (VD: 35M):"), R, cy - 70, 0xB0BEC5);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("⬇ Pay Tối Thiểu (VD: 30M):"), L, cy - 30, 0xB0BEC5);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("⬆ Pay Tối Đa (VD: 35M):"), R, cy - 30, 0xB0BEC5);
        }
    }

    public static class ModConfig {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "wither_aura_v9.json");
        public boolean onlyWither = true, autoSword = true, sprintReset = true, panicPlayer = false, silentMode = false, showHud = true, autoPay = true, useShortPayFormat = true, noRender = false;
        public String payTarget = "", priority = "DISTANCE";
        public double payTrigger = 35e6, payMin = 30e6, payMax = 35e6, minRange = 2.85, maxRange = 3.00, aimOffset = 0.15, panicRadius = 15.0;
        public float fov = 160.0f, minYaw = 14.0f, maxYaw = 30.0f, minPitch = 10.0f, maxPitch = 22.0f, minHealth = 6.0f;
        public int minAttackJitterMs = 15, maxAttackJitterMs = 65, minReactMs = 30, maxReactMs = 85, minDurability = 12;

        public static ModConfig load() {
            if (FILE.exists()) try (FileReader r = new FileReader(FILE)) { return GSON.fromJson(r, ModConfig.class); } catch (Exception ignored) {}
            ModConfig c = new ModConfig(); c.save(); return c;
        }
        public void save() { try (FileWriter w = new FileWriter(FILE)) { GSON.toJson(this, w); } catch (Exception ignored) {} }
    }
        }
