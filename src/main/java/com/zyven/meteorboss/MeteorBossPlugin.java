package com.zyven.meteorboss;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;

public final class MeteorBossPlugin extends JavaPlugin {

    private static final int LOG_LIMIT = 200;

    private final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private EventManager events;
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        events = new EventManager(this, new BackupStore(getDataFolder()));

        BossManager bossManager = new BossManager(this);
        events.setBossManager(bossManager);
        events.addEmergeHook(bossManager::spawn);
        getServer().getPluginManager().registerEvents(new BossListener(bossManager), this);

        PluginCommand command = getCommand("meteor");
        if (command != null) {
            MeteorCommand handler = new MeteorCommand(this, events);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        // Sunucu etkinlik ortasında kapandıysa çukuru geri kur
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> events.recoverOnStartup(), 100L);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> events.autoTick(), 600L, 600L);
        if (getConfig().getString("boss.model-mode", "blocks").equalsIgnoreCase("pack")) {
            writePack();
        }
        log("MeteorBoss açıldı.");
    }

    /** Özel model paketini plugins/MeteorBoss/typhon-pack.zip olarak yazar, sha1 döndürür (hata olursa null). */
    public String writePack() {
        try {
            byte[] zip = PackBuilder.build();
            Path out = getDataFolder().toPath().resolve("typhon-pack.zip");
            Files.createDirectories(out.getParent());
            Files.write(out, zip);
            String sha1 = PackBuilder.sha1(zip);
            log("Paket yazıldı: " + out + " (sha1: " + sha1 + ")");
            return sha1;
        } catch (Exception e) {
            logError("Paket yazılamadı", e);
            return null;
        }
    }

    public EventManager getEvents() {
        return events;
    }

    public void log(String message) {
        getLogger().info(message);
        push(message);
    }

    public void logError(String message, Throwable error) {
        getLogger().log(Level.SEVERE, message, error);
        push(message + " -> " + error);
    }

    List<String> recentLog() {
        return new ArrayList<>(logBuffer);
    }

    private void push(String line) {
        logBuffer.addLast(java.time.LocalDateTime.now() + " " + line);
        while (logBuffer.size() > LOG_LIMIT) {
            logBuffer.pollFirst();
        }
    }

    public synchronized long getLastStart() {
        return data.getLong("last-start", 0L);
    }

    public synchronized String getLastAutoKey() {
        return data.getString("last-auto", "");
    }

    public synchronized void setLastAutoKey(String key) {
        data.set("last-auto", key);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            logError("data.yml kaydedilemedi", e);
        }
    }

    public synchronized void setLastStart(long time) {
        data.set("last-start", time);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            logError("data.yml kaydedilemedi", e);
        }
    }
}
