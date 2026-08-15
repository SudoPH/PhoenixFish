package com.phoenix.fish.task;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.ItemFixer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class ContainerFixTask extends BukkitRunnable {

    private static final int CHUNKS_PER_TICK = 5;
    private final PhoenixFish plugin;
    private final ItemFixer fixer;
    private final CommandSender notifyTo;
    private final Deque<Chunk> chunkQueue = new ArrayDeque<>();

    private int totalContainers = 0;
    private int totalRods = 0;
    private int totalBaits = 0;

    public ContainerFixTask(PhoenixFish plugin, ItemFixer fixer, CommandSender notifyTo) {
        this.plugin = plugin;
        this.fixer = fixer;
        this.notifyTo = notifyTo;

        for (World world : plugin.getServer().getWorlds()) {
            chunkQueue.addAll(Arrays.asList(world.getLoadedChunks()));
        }
    }

    public int getTotalChunks() {
        return chunkQueue.size();
    }

    @Override
    public void run() {
        if (chunkQueue.isEmpty()) {
            if (notifyTo != null) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("%containers%", String.valueOf(totalContainers));
                placeholders.put("%rods%", String.valueOf(totalRods));
                placeholders.put("%baits%", String.valueOf(totalBaits));

                notifyTo.sendMessage(
                        plugin.getMessageManager().getMessage("fixall_done_containers", true, placeholders));
            }
            cancel();
            return;
        }

        int processed = 0;
        while (processed < CHUNKS_PER_TICK && !chunkQueue.isEmpty()) {
            Chunk chunk = chunkQueue.poll();
            processed++;

            if (chunk == null || !chunk.isLoaded()) {
                continue;
            }

            for (BlockState state : chunk.getTileEntities(false)) {
                if (!(state instanceof Container container)) {
                    continue;
                }

                totalContainers++;
                int[] result = fixer.fixInventory(container.getInventory());
                totalRods += result[0];
                totalBaits += result[1];
            }
        }
    }
}