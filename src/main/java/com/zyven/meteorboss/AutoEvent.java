package com.zyven.meteorboss;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Otomatik etkinlik: saatinde (ya da aralıkla) meteoru düşürür, öncesinde sohbete ve boss barına
 * geri sayım duyurusu yapar. Yer /meteor etkinlik kaydet ile kayıtlıysa orası, değilse rastgele bir yer seçilir.
 */
final class AutoEvent {

    private final MeteorBossPlugin plugin;
    private final EventManager events;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final long bootTime = System.currentTimeMillis();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Set<String> announced = new HashSet<>();
    private BossBar countdownBar;

    AutoEvent(MeteorBossPlugin plugin, EventManager events) {
        this.plugin = plugin;
        this.events = events;
    }

    /** Her 10 saniyede bir çağrılır (global scheduler). */
    void tick() {
        try {
            FileConfiguration cfg = plugin.getConfig();
            if (!cfg.getBoolean("auto-event.enabled", false) || events.isBusy()) {
                hideCountdown();
                return;
            }
            List<String> times = cfg.getStringList("auto-event.times");
            ZoneId zone = zone(cfg);
            ZonedDateTime now = ZonedDateTime.now(zone);
            boolean enoughPlayers = Bukkit.getOnlinePlayers().size() >= Math.max(1, cfg.getInt("auto-event.min-players", 3));

            // Başlama zamanı ve geri sayım
            long remainingMs;
            String targetKey;
            String clockKey = null;
            if (!times.isEmpty()) {
                ZonedDateTime next = nextTime(times, now);
                if (next == null) {
                    hideCountdown();
                    return;
                }
                remainingMs = java.time.Duration.between(now, next).toMillis();
                targetKey = next.toLocalDate() + " " + next.toLocalTime();
                String hm = String.format("%02d:%02d", now.getHour(), now.getMinute());
                if (times.contains(hm)) {
                    clockKey = now.toLocalDate() + " " + hm;
                    if (!clockKey.equals(plugin.getLastAutoKey()) && enoughPlayers && busy.compareAndSet(false, true)) {
                        plugin.setLastAutoKey(clockKey);
                        hideCountdown();
                        begin();
                        return;
                    }
                }
            } else {
                long interval = Math.max(1L, cfg.getLong("auto-event.interval-minutes", 120L)) * 60_000L;
                long since = Math.max(plugin.getLastStart(), bootTime);
                remainingMs = interval - (System.currentTimeMillis() - since);
                targetKey = "aralik " + since;
                if (remainingMs <= 0) {
                    if (enoughPlayers && busy.compareAndSet(false, true)) {
                        hideCountdown();
                        begin();
                    }
                    return;
                }
            }
            if (enoughPlayers) {
                announce(cfg, remainingMs, targetKey);
            } else {
                hideCountdown();
            }
        } catch (Throwable e) {
            busy.set(false);
            plugin.logError("Otomatik etkinlik hatası", e);
        }
    }

    private static ZoneId zone(FileConfiguration cfg) {
        try {
            return ZoneId.of(cfg.getString("auto-event.timezone", "Europe/Istanbul"));
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /** Listedeki saatlerden şimdiden sonraki en yakın olanı (gerekirse yarın). */
    private static ZonedDateTime nextTime(List<String> times, ZonedDateTime now) {
        ZonedDateTime best = null;
        for (String t : times) {
            try {
                java.time.LocalTime lt = java.time.LocalTime.parse(t.trim());
                ZonedDateTime c = now.toLocalDate().atTime(lt).atZone(now.getZone());
                if (!c.isAfter(now)) {
                    c = c.plusDays(1);
                }
                if (best == null || c.isBefore(best)) {
                    best = c;
                }
            } catch (Exception ignored) {
                // hatalı yazılmış saat atlanır
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ duyuru

    private void announce(FileConfiguration cfg, long remainingMs, String targetKey) {
        List<Integer> minutes = cfg.getIntegerList("auto-event.announce-minutes");
        int widest = 0;
        for (int m : minutes) {
            widest = Math.max(widest, m);
        }
        if (widest <= 0 || remainingMs > widest * 60_000L) {
            hideCountdown();
            return;
        }
        String time = formatRemaining(remainingMs);

        // Sohbet: eşiği yeni geçtiyse bir kez yaz
        boolean fresh = false;
        for (int m : minutes) {
            if (m > 0 && remainingMs <= m * 60_000L && announced.add(targetKey + "|" + m)) {
                fresh = true;
            }
        }
        if (announced.size() > 200) {
            announced.clear();
        }
        if (fresh) {
            var msg = mm.deserialize(cfg.getString("messages.auto-announce",
                    "<gold>☄ <yellow>Meteor etkinliği <white>%time%</white> sonra başlıyor!").replace("%time%", time));
            Bukkit.getServer().sendMessage(msg);
        }

        // Boss barı: son geri sayım penceresinde herkeste görünür
        var title = mm.deserialize(cfg.getString("messages.auto-bar",
                "<gradient:#ffb12b:#ff2a2a><bold>☄ METEOR ETKİNLİĞİ</bold></gradient> <gray>│ <white>%time% sonra").replace("%time%", time));
        if (countdownBar == null) {
            countdownBar = BossBar.bossBar(title, 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        }
        countdownBar.name(title);
        countdownBar.progress((float) Math.max(0.0, Math.min(1.0, remainingMs / (widest * 60_000.0))));
        BossBar bar = countdownBar;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> p.showBossBar(bar), null);
        }
    }

    private void hideCountdown() {
        if (countdownBar == null) {
            return;
        }
        BossBar bar = countdownBar;
        countdownBar = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> p.hideBossBar(bar), null);
        }
    }

    private static String formatRemaining(long ms) {
        long sec = (ms + 999) / 1000;
        if (sec >= 90) {
            return ((sec + 59) / 60) + " dakika";
        }
        return sec + " saniye";
    }

    // ------------------------------------------------------------------ yer ve başlatma

    private void begin() {
        MeteorBossPlugin.AutoSite site = plugin.getAutoSite();
        if (site != null) {
            World w = Bukkit.getWorld(site.world());
            if (w == null) {
                busy.set(false);
                plugin.log("Otomatik etkinlik: kayıtlı dünya bulunamadı (" + site.world() + ").");
                return;
            }
            startAt(w, site.x(), site.z());
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Player p = online.get(ThreadLocalRandom.current().nextInt(online.size()));
        p.getScheduler().run(plugin, t -> {
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) {
                busy.set(false);
                return;
            }
            tryRandomSite(w, 5);
        }, () -> busy.set(false));
    }

    private void startAt(World world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        world.getChunkAtAsync(cx, cz).thenAccept(chunk ->
                Bukkit.getRegionScheduler().run(plugin, world, cx, cz, task -> {
                    String err = events.start(world, x, z, false);
                    busy.set(false);
                    if (err != null) {
                        plugin.log("Otomatik etkinlik başlamadı: " + err);
                    }
                })).exceptionally(ex -> {
            busy.set(false);
            plugin.logError("Otomatik etkinlik alanı yüklenemedi", ex);
            return null;
        });
    }

    private void tryRandomSite(World world, int tries) {
        if (tries <= 0) {
            busy.set(false);
            plugin.log("Otomatik etkinlik: uygun yer bulunamadı, tekrar denenecek.");
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location spawn = world.getSpawnLocation();
        double min = cfg.getDouble("auto-event.min-distance", 80.0);
        double max = Math.max(min + 1, cfg.getDouble("auto-event.max-distance", 200.0));
        double avoid = cfg.getDouble("auto-event.avoid-spawn-radius", 300.0);

        List<Player> inWorld = new ArrayList<>(world.getPlayers());
        if (inWorld.isEmpty()) {
            busy.set(false);
            return;
        }
        Player p = inWorld.get(rnd.nextInt(inWorld.size()));
        p.getScheduler().run(plugin, t -> {
            Location pl = p.getLocation();
            double ang = rnd.nextDouble(Math.PI * 2);
            double dist = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.round(pl.getX() + Math.cos(ang) * dist);
            int z = (int) Math.round(pl.getZ() + Math.sin(ang) * dist);
            if (Math.hypot(x - spawn.getX(), z - spawn.getZ()) < avoid) {
                tryRandomSite(world, tries - 1);
                return;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            world.getChunkAtAsync(cx, cz).thenAccept(chunk ->
                    Bukkit.getRegionScheduler().run(plugin, world, cx, cz, task -> {
                        Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                        String name = top.getType().name();
                        if (top.isLiquid() || name.contains("ICE") || name.contains("LEAVES")) {
                            tryRandomSite(world, tries - 1);
                            return;
                        }
                        String err = events.start(world, x, z, false);
                        busy.set(false);
                        if (err != null) {
                            plugin.log("Otomatik etkinlik başlamadı: " + err);
                        }
                    })).exceptionally(ex -> {
                busy.set(false);
                plugin.logError("Otomatik etkinlik alanı yüklenemedi", ex);
                return null;
            });
        }, () -> busy.set(false));
    }
}
