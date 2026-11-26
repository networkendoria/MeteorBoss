package com.zyven.meteorboss;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;


import java.util.List;

/** Boss ölünce: ödüller, sohbet duyurusu ve KAZANAN barı. */
final class BossRewards {

    private final MeteorBossPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    BossRewards(MeteorBossPlugin plugin) {
        this.plugin = plugin;
    }

    /** Eşyaları envantere ekler, sığmayanlar oyuncunun ayağına düşer. */
    void give(Player killer, List<ItemStack> items, int exp, Location fallback) {
        World world = fallback.getWorld();
        killer.getScheduler().run(plugin, t -> {
            for (ItemStack it : items) {
                for (ItemStack left : killer.getInventory().addItem(it).values()) {
                    killer.getWorld().dropItemNaturally(killer.getLocation(), left);
                }
            }
            killer.giveExp(exp);
        }, () -> Bukkit.getRegionScheduler().run(plugin, fallback, t -> {
            // oyuncu çıkmış/ölmüşse eşyalar ölüm noktasına düşer
            for (ItemStack it : items) {
                world.dropItemNaturally(fallback, it);
            }
        }));
    }

    /** Sohbete mesaj, 10 saniye boyunca azalan "KAZANAN" boss barı. */
    void announce(String name, World world, Location at, FileConfiguration cfg) {
        Component chat = mm.deserialize(cfg.getString("messages.killed",
                "<gold>⚔ <yellow><bold>%player%</bold> <gray>Typhon'u yendi!").replace("%player%", name));
        Bukkit.getServer().sendMessage(chat);

        Component title = mm.deserialize(cfg.getString("messages.victory-bar",
                "<gradient:#ffb12b:#ff2a2a><bold>KAZANAN</bold></gradient> <gray>│ <white>%player%").replace("%player%", name));
        BossBar bar = BossBar.bossBar(title, 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        double r = cfg.getDouble("boss.bar-radius", 150.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> {
                if (p.getWorld().equals(world) && p.getLocation().distanceSquared(at) <= r * r) {
                    p.showBossBar(bar);
                }
            }, null);
        }
        final int steps = 40; // 40 adım x 5 tick = 10 saniye
        int[] step = {0};
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            step[0]++;
            if (step[0] >= steps) {
                task.cancel();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.getScheduler().run(plugin, t -> p.hideBossBar(bar), null);
                }
                return;
            }
            bar.progress(1.0f - step[0] / (float) steps);
        }, 5L, 5L);
    }
}
