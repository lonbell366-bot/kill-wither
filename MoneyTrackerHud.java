package com.example.addon.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.MeteorClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MoneyTrackerHud extends HudElement {
    public static final HudElementInfo<MoneyTrackerHud> INFO = new HudElementInfo<>(
        "addon-hud", "Money Tracker", 
        "Hiển thị số tiền farm được trên giờ và thời gian treo máy.", 
        MoneyTrackerHud::new
    );

    private long startTime = 0;
    private double totalMoneyEarned = 0;
    private boolean isTracking = false;

    public MoneyTrackerHud() {
        super(INFO);
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (!isTracking) {
            startTime = System.currentTimeMillis();
            isTracking = true;
        }

        long elapsedMillis = System.currentTimeMillis() - startTime;
        double elapsedHours = elapsedMillis / 3600000.0;
        double moneyPerHour = elapsedHours > 0 ? (totalMoneyEarned / elapsedHours) : 0;

        String timeStr = formatTime(elapsedMillis);
        String moneyStr = formatMoney(totalMoneyEarned);
        String rateStr = formatMoney(moneyPerHour) + "/h";

        renderer.text("Treo: " + timeStr, x, y, Color.WHITE, true);
        renderer.text("Đã kiếm: " + moneyStr, x, y + 12, Color.GREEN, true);
        renderer.text("Tốc độ: " + rateStr, x, y + 24, Color.YELLOW, true);
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString();
        // Biểu thức chính quy quét các định dạng tiền thông báo trong chat (Ví dụ: +$300, 300K, 1.5M, v.v.)
        Pattern pattern = Pattern.compile("(?i)(?:\\+|kiếm được|nhận được)\\s*\\$?([0-9,.]+)([kKmMgG]?)");
        Matcher matcher = pattern.matcher(msg);
        
        if (matcher.find()) {
            try {
                double amount = Double.parseDouble(matcher.group(1).replace(",", ""));
                String unit = matcher.group(2).toUpperCase();
                if (unit.equals("K")) amount *= 1_000;
                else if (unit.equals("M")) amount *= 1_000_000;
                else if (unit.equals("B")) amount *= 1_000_000_000;

                totalMoneyEarned += amount;
            } catch (Exception ignored) {}
        }
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private String formatMoney(double amount) {
        if (amount >= 1_000_000_000) return String.format("%.2fB", amount / 1_000_000_000);
        if (amount >= 1_000_000) return String.format("%.2fM", amount / 1_000_000);
        if (amount >= 1_000) return String.format("%.2fK", amount / 1_000);
        return String.format("%.0f$", amount);
    }
}
