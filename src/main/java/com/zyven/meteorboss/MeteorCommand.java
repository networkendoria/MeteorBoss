package com.zyven.meteorboss;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public final class MeteorCommand implements CommandExecutor, TabCompleter {

    private final MeteorBossPlugin plugin;
    private final MeteorManager manager;

    public MeteorCommand(MeteorBossPlugin plugin, MeteorManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage("MeteorBoss ayarları yenilendi.");
            return true;
        }

        World world;
        int x;
        int z;

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Konsoldan kullanım: /meteor <x> <z> <dünya>");
                return true;
            }
            // Oyuncunun baktığı yönde 25 blok ileri
            Location loc = player.getLocation();
            Vector dir = loc.getDirection().setY(0);
            if (dir.lengthSquared() < 0.0001) {
                dir = new Vector(0, 0, 1);
            }
            dir.normalize().multiply(25);
            world = loc.getWorld();
            x = loc.getBlockX() + dir.getBlockX();
            z = loc.getBlockZ() + dir.getBlockZ();
        } else {
            if (args.length < 2) {
                sender.sendMessage("Kullanım: /meteor [x z [dünya]]");
                return true;
            }
            try {
                x = Integer.parseInt(args[0]);
                z = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("x ve z sayı olmalı.");
                return true;
            }
            if (args.length >= 3) {
                world = Bukkit.getWorld(args[2]);
            } else if (sender instanceof Player player) {
                world = player.getWorld();
            } else {
                world = null;
            }
            if (world == null) {
                sender.sendMessage("Dünya bulunamadı. Dünya adını da yaz: /meteor <x> <z> <dünya>");
                return true;
            }
        }

        if (world.getEnvironment() != World.Environment.NORMAL) {
            sender.sendMessage("Meteor şimdilik sadece normal dünyada (overworld) düşer.");
            return true;
        }

        manager.launch(world, x, z);
        sender.sendMessage("Meteor yola çıktı: " + x + " " + z);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) {
            out.add("reload");
        }
        if (args.length == 3) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getName().toLowerCase().startsWith(args[2].toLowerCase())) {
                    out.add(w.getName());
                }
            }
        }
        return out;
    }
}
