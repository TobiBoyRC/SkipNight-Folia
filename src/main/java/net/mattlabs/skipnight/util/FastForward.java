package net.mattlabs.skipnight.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * Folia-safe replacement for the old BukkitRunnable-based FastForward.
 * <p>
 * World day time, game time, and the weather cycle are explicitly documented as
 * global-region-owned state (see https://docs.papermc.io/paper/dev/folia-support/
 * and the Folia GlobalRegionScheduler javadoc: "The global region is responsible
 * for maintaining world day time, world game time, weather cycle, sleep night
 * skipping..."). world.setTime()/world.setStorm() must therefore run on the
 * GlobalRegionScheduler, not a location-anchored RegionScheduler.
 * <p>
 * This class self-reschedules instead of using a periodic task so it can stop
 * exactly when the target time is reached, mirroring the original behaviour.
 */
public class FastForward implements Consumer<ScheduledTask> {

    private final World world;
    private final Plugin plugin;
    private final VoteType voteType;

    public FastForward(World world, Plugin plugin, VoteType voteType) {
        this.world = world;
        this.plugin = plugin;
        this.voteType = voteType;
    }

    /**
     * Starts (or resumes) the fast-forward. Call this instead of scheduling
     * {@link #accept(ScheduledTask)} directly.
     */
    public void start(long initialDelayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, this, initialDelayTicks);
    }

    @Override
    public void accept(ScheduledTask task) {
        long totalTime = voteType == VoteType.DAY ? 12541 - world.getTime() : 24000 - world.getTime();
        world.setTime(world.getTime() + 80);
        totalTime -= 80;
        if (totalTime < 80 && (voteType == VoteType.NIGHT && world.hasStorm())) world.setStorm(false);
        if (totalTime > 0) Bukkit.getGlobalRegionScheduler().runDelayed(plugin, this, 1);
    }
}
