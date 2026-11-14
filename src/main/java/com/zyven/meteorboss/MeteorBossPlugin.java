package com.zyven.meteorboss;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MeteorBossPlugin extends JavaPlugin {

    private MeteorManager meteorManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        meteorManager = new MeteorManager(this);

        PluginCommand command = getCommand("meteor");
        if (command != null) {
            MeteorCommand handler = new MeteorCommand(this, meteorManager);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getLogger().info("MeteorBoss açıldı.");
    }

    public MeteorManager getMeteorManager() {
        return meteorManager;
    }
}
