package com.zyven.meteorboss;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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
        log("MeteorBoss açıldı.");
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

    public synchronized void setLastStart(long time) {
        data.set("last-start", time);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            logError("data.yml kaydedilemedi", e);
        }
    }
}
