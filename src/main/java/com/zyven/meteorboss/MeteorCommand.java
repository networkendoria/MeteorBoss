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
    private final EventManager events;

    public MeteorCommand(MeteorBossPlugin plugin, EventManager events) {
        this.plugin = plugin;
        this.events = events;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return callAhead(sender, false);
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(events.text("<green>MeteorBoss ayarları yenilendi."));
                return true;
            }
            case "pack" -> {
                String sha1 = plugin.writePack();
                if (sha1 == null) {
                    sender.sendMessage(events.text("<red>Paket yazılamadı. Konsola bak."));
                } else {
                    sender.sendMessage(events.text("<green>Paket yazıldı: plugins/MeteorBoss/typhon-pack.zip"));
                    sender.sendMessage(events.text("<gray>sha1: <white>" + sha1));
                }
                return true;
            }
            case "debug" -> {
                events.debug(sender);
                return true;
            }
            case "etkinlik" -> {
                String flag = args.length > 1 ? args[1].toLowerCase() : "";
                switch (flag) {
                    case "-k" -> {
                        if (events.forceEnd()) {
                            sender.sendMessage(events.text("<green>Etkinlik sona erdirildi, çukur 5 saniyede kapanıyor."));
                        } else {
                            sender.sendMessage(events.text("<yellow>Sona erdirilecek bir etkinlik yok."));
                        }
                        return true;
                    }
                    case "-z" -> {
                        return callAhead(sender, true);
                    }
                    case "" -> {
                        return callAhead(sender, false);
                    }
                    default -> {
                        sender.sendMessage(events.text("<red>Kullanım: /meteor etkinlik [-z | -k]"));
                        return true;
                    }
                }
            }
            default -> {
                return callAtCoords(sender, args);
            }
        }
    }

    /** Baktığın yönde ileriye (config: default-distance, varsayılan 10 blok). */
    private boolean callAhead(CommandSender sender, boolean force) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Konsoldan kullanım: /meteor <x> <z> <dünya>");
            return true;
        }
        int distance = Math.max(1, plugin.getConfig().getInt("default-distance", 10));
        Location loc = player.getLocation();
        Vector dir = loc.getDirection().setY(0);
        if (dir.lengthSquared() < 0.0001) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize().multiply(distance);
        return launch(sender, loc.getWorld(), loc.getBlockX() + dir.getBlockX(), loc.getBlockZ() + dir.getBlockZ(), force);
    }

    private boolean callAtCoords(CommandSender sender, String[] args) {
        // /meteor <dünya> <x> <y> <z>   ya da   /meteor <dünya> <x> <z>
        if (args.length >= 3 && Bukkit.getWorld(args[0]) != null) {
            World w = Bukkit.getWorld(args[0]);
            try {
                if (args.length >= 4) {
                    int x = (int) Math.floor(Double.parseDouble(args[1]));
                    int y = (int) Math.floor(Double.parseDouble(args[2]));
                    int z = (int) Math.floor(Double.parseDouble(args[3]));
                    return launch(sender, w, x, y, z, false);
                }
                int x = (int) Math.floor(Double.parseDouble(args[1]));
                int z = (int) Math.floor(Double.parseDouble(args[2]));
                return launch(sender, w, x, null, z, false);
            } catch (NumberFormatException e) {
                sender.sendMessage(events.text("<red>Kullanım: /meteor <dünya> <x> <y> <z>"));
                return true;
            }
        }
        if (args.length < 2) {
            sender.sendMessage(events.text("<red>Kullanım: /meteor [x z] | reload | etkinlik [-z|-k] | debug"));
            return true;
        }
        int x;
        int z;
        try {
            x = Integer.parseInt(args[0]);
            z = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(events.text("<red>x ve z sayı olmalı."));
            return true;
        }
        World world;
        if (args.length >= 3) {
            world = Bukkit.getWorld(args[2]);
        } else if (sender instanceof Player player) {
            world = player.getWorld();
        } else {
            world = null;
        }
        if (world == null) {
            sender.sendMessage(events.text("<red>Dünya bulunamadı. Konsoldan: /meteor <x> <z> <dünya>"));
            return true;
        }
        return launch(sender, world, x, z, false);
    }

    private boolean launch(CommandSender sender, World world, int x, int z, boolean force) {
        return launch(sender, world, x, null, z, force);
    }

    private boolean launch(CommandSender sender, World world, int x, Integer y, int z, boolean force) {
        String error = events.start(world, x, z, y, force);
        if (error != null) {
            sender.sendMessage(events.text("<red>" + error));
            return true;
        }
        sender.sendMessage(events.text(plugin.getConfig().getString("messages.called", "<green>Meteor yola çıktı.")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("etkinlik", "reload", "debug", "pack")) {
                if (s.startsWith(args[0].toLowerCase())) {
                    out.add(s);
                }
            }
            for (World w : Bukkit.getWorlds()) {
                if (w.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(w.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("etkinlik")) {
            for (String s : List.of("-z", "-k")) {
                if (s.startsWith(args[1].toLowerCase())) {
                    out.add(s);
                }
            }
        } else if (args.length == 3 && Bukkit.getWorld(args[0]) == null) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getName().toLowerCase().startsWith(args[2].toLowerCase())) {
                    out.add(w.getName());
                }
            }
        }
        return out;
    }
}
