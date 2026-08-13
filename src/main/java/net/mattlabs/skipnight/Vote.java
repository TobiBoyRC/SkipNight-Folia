package net.mattlabs.skipnight;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.mattlabs.skipnight.util.FastForward;
import net.mattlabs.skipnight.util.Versions;
import net.mattlabs.skipnight.util.VoteType;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Folia-safe rewrite of the vote state machine.
 * <p>
 * Threading model:
 * <ul>
 *     <li>All mutable vote state (voters map, yes/no/away/idle counters, boss bar,
 *     timer enum) is only ever mutated from tasks run on the
 *     {@link io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler}.
 *     This is the "fan-in" side.</li>
 *     <li>Anything that needs to read a specific {@link Player}'s state
 *     (permissions, world, sleeping status) or send that player a message/boss bar
 *     is dispatched to that player's own {@link org.bukkit.entity.Player#getScheduler()}.
 *     This is the "fan-out" side.</li>
 *     <li>World day time / weather reads and writes run on the
 *     {@link io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler}
 *     (see {@link FastForward}), since PaperMC's own docs state the global region
 *     owns world day time, game time, and the weather cycle - it is not tied to
 *     any single chunk region.</li>
 * </ul>
 * This avoids the two failure modes that would otherwise be invisible until a busy,
 * multi-region production server hits them: (1) touching Player/World state from a
 * thread that doesn't own the owning region, and (2) unsynchronized concurrent
 * mutation of the voters map / counters from multiple region threads at once.
 */
public class Vote implements Listener {

    enum Timer {
        INIT,
        OPERATION,
        INTERRUPT,
        CANCEL,
        FINAL,
        COMPLETE,
        COOLDOWN,
        OFF
    }

    Timer timer;
    private VoteType voteType;
    private int yes, no, playerCount, countDown, countDownInit, away, idle;
    private BossBar bar;
    private final SkipNight plugin;
    private Map<UUID, Voter> voters;
    private Player voteInitiator;
    private World world;
    private FastForward fastForward;
    private ScheduledTask bossBarFastForwardTask;
    private final Messages messages;
    private final BukkitAudiences platform;
    private final String version;
    private final boolean playerActivity;
    private final Config config;

    Vote(SkipNight plugin) {
        timer = Timer.OFF;
        this.plugin = plugin;
        messages = SkipNight.getInstance().getMessages();
        platform = SkipNight.getInstance().getPlatform();
        version = SkipNight.getInstance().getVersion();
        playerActivity = SkipNight.getInstance().hasPlayerActivity();
        config = SkipNight.getInstance().getConfiguration();
    }

    @EventHandler
    public void onLogoff(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // PlayerQuitEvent for a given player fires on the region owning that player,
        // which may not be the global region. Hop onto the global region thread
        // before touching shared vote state.
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (timer != Timer.OFF) // vote is running
                if (player.hasPermission("skipnight.vote." + voteTypeCommandString(voteType))) { // player has permission
                    voters.remove(player.getUniqueId());
                }
        });
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        // Player has permission, isn't the only one in the world, and it is night (or storming)
        if (player.hasPermission("skipnight.vote.night")
                && player.getWorld().getPlayers().size() > 1
                && timer == Timer.OFF
                && (player.getWorld().getTime() > 12516 || player.getWorld().hasStorm())) {
            platform.player(player).sendMessage(messages.beforeVote().inBedNoVoteInProg());
        }
    }

    /**
     * Dispatches the next tick of the state machine on the global region thread.
     * This replaces the old {@code Runnable#run()} entry point.
     */
    private void tick() {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            switch (timer) {
                case INIT -> doInit();
                case OPERATION -> doOperation();
                case INTERRUPT -> doInterrupt();
                case CANCEL -> doCancel();
                case FINAL -> doFinal();
                case COMPLETE -> doComplete();
                case COOLDOWN -> doCooldown();
                default -> {}
            }
        });
    }

    private void tickLater(long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> tick(), delayTicks);
    }

    /* The first stage of a vote. This is where lists, variables and the boss bar are created. The players are updated
    *  about the vote that has just started. */
    private void doInit() {
        voters = new HashMap<>();

        bar = BossBar.bossBar(Component.text(), 1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);

        yes = 1;
        no = 0;
        countDown = config.getVoteDuration();
        countDownInit = config.getVoteDuration();
        away = 0;
        idle = 0;

        if (playerActivity)
            bar.name(messages.duringVote().currentVotePA(yes, no, idle, away)).color(BossBar.Color.PURPLE);
        else
            bar.name(messages.duringVote().currentVote(yes, no)).color(BossBar.Color.PURPLE);

        updateAll(null, () -> {
            timer = Timer.OPERATION;
            tickLater(20);
        });
    }

    /* The main stage of the vote. Checks for a completed vote or waits until the last 10 seconds to move on.
    *  Sets the boss bar each second. */
    private void doOperation() {
        countDown--;
        if (yes + no + idle + away == playerCount || thresholdDecided()) timer = Timer.INTERRUPT;
        if (voteCancel()) timer = Timer.CANCEL;
        bar.progress((float) countDown / countDownInit);
        if (playerActivity)
            bar.name(messages.duringVote().currentVotePA(yes, no, idle, away));
        else
            bar.name(messages.duringVote().currentVote(yes, no));
        if (countDown <= 10) timer = Timer.FINAL;
        updateAll(null, () -> tickLater(20));
    }

    /* The stage for when everyone has voted. Sets the boss bar and moves onto the next stage. */
    private void doInterrupt() {
        countDown = 0;
        bar.progress(1.0f);
        bar.name(messages.afterVote().allPlayersHaveVoted());
        bar.color(BossBar.Color.YELLOW);

        timer = Timer.COMPLETE;
        // yes/no/playerCount are only refreshed inside finishUpdateAll(), which runs
        // as part of updateAll(). doOperation/doFinal call updateAll() every tick, but
        // the INTERRUPT transition itself can fire the same tick a player's vote lands
        // in the voters map, before that tick's updateAll() has folded it into yes/no.
        // doComplete() reads yes/no immediately next tick to decide votePassed(), so we
        // must force one more refresh here or it can evaluate against stale counts.
        updateAll(null, () -> tickLater(20));
    }

    /* The last 10 seconds of the vote. Boss bar alternates white and purple and players receive a message. */
    private void doFinal() {
        countDown--;
        if (yes + no + idle + away == playerCount || thresholdDecided()) timer = Timer.INTERRUPT;
        if (voteCancel()) timer = Timer.CANCEL;
        bar.progress((float) countDown / countDownInit);
        if (playerActivity)
            bar.name(messages.duringVote().currentVotePA(yes, no, idle, away));
        else
            bar.name(messages.duringVote().currentVote(yes, no));

        if (countDown % 2 == 1) bar.color(BossBar.Color.WHITE);
        else bar.color(BossBar.Color.PURPLE);

        Component finalMessage = countDown == 9 ? messages.duringVote().tenSecondsLeft() : null;

        if (countDown == 0) timer = Timer.COMPLETE;
        updateAll(finalMessage, () -> tickLater(20));
    }

    /* The stage of the vote after the timer has run out. Displays vote passed/failed via boss bar and message.
    *  Initiates a fast-forward to the correct time. */
    private void doComplete() {
        countDown--;
        if (countDown == -1) {
            bar.progress(1.0f);
            if (votePassed()) {
                bar.name(messages.afterVote().votePassedBossBar());
                bar.color(BossBar.Color.GREEN);
                Component passedMessage = messages.afterVote().votePassedBossBar(voteTypeString());

                fastForward = new FastForward(world, plugin, voteType);
                fastForward.start(10);

                // Set boss bar progress to fast-forward progress. World day time is
                // global-region-owned state (per PaperMC docs), so this must run on
                // the GlobalRegionScheduler, same as FastForward itself.
                bar.progress(0.0f);
                float totalTime = voteType == VoteType.NIGHT ? 23900f : 12516f;
                bossBarFastForwardTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                        plugin,
                        task -> {
                            float progress = world.getTime() / totalTime;
                            if (progress > 1.0f) {
                                bar.progress(1.0f);
                                task.cancel();
                            } else {
                                bar.progress(progress);
                            }
                        }, 1, 1);

                updateAll(passedMessage, () -> tickLater(20));
            }
            else {
                bar.name(messages.afterVote().voteFailedBossBar());
                bar.color(BossBar.Color.RED);
                Component failedMessage = messages.afterVote().voteFailedBossBar(voteTypeString());
                updateAll(failedMessage, () -> tickLater(20));
            }
            return;
        }

        if (countDown <= -2) tickLater(20);

        if (countDown <= -9 && bar.progress() == 1.0f) {
            platform.all().hideBossBar(bar);
            if (bossBarFastForwardTask != null) {
                bossBarFastForwardTask.cancel();
                bossBarFastForwardTask = null;
            }
            bar = null;
            voters = null;
            fastForward = null;
            voteType = null;
            timer = votePassed() ? Timer.OFF : Timer.COOLDOWN;
        }
    }

    /* Runs after everything is done to prevent a vote from starting again until after a time. */
    private void doCooldown() {
        countDown--;
        if (countDown >= (config.getCooldown() * -1) - 9) tickLater(20);
        else timer = Timer.OFF;
    }

    /* Runs when it becomes the target time during the vote. Switches to blue boss bar and cancels everything. */
    private void doCancel() {
        if (countDown > 0) countDown = 0;
        if (countDown == 0) {
            bar.progress(1.0f);
            bar.color(BossBar.Color.BLUE);
            if (voteType == VoteType.NIGHT) bar.name(messages.afterVote().itIsAlreadyDay());
            else bar.name(messages.afterVote().itIsAlreadyNight());
        }

        countDown--;

        if (countDown > -4) tickLater(20);

        if (countDown == -4) {
            platform.all().hideBossBar(bar);
            bar = null;
            voters = null;
            fastForward = null;
            voteType = null;
            timer = Timer.OFF;
        }
    }

    public void addYes(Player player, VoteType voteType) {
        // May be invoked from the command handler on the player's own region thread.
        // Hop to the global region thread before touching shared vote state.
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (timer != Timer.OFF) {
                Voter voter = new Voter(player.getUniqueId());
                if (voters.containsKey(voter.getUuid())) {
                    voter = voters.get(voter.getUuid());
                    if (voter.getVote() == 0) {
                        if (this.voteType == VoteType.NIGHT && playerMustSleep(player) && config.isPhantomSupport()) {
                            sendToPlayer(player, messages.beforeVote().mustSleep());
                            actionBarMessage(messages.duringVote().playerHasNotSlept(player.getName()));
                        }
                        else {
                            voter.voteYes();
                            sendToPlayer(player, messages.duringVote().youVoteYes());
                            actionBarMessage(messages.duringVote().playerHasVotedYes(player.getName()));
                        }
                    }
                    else sendToPlayer(player, messages.duringVote().alreadyVoted());
                }
            }
            else sendToPlayer(player, messages.beforeVote().noVoteInProg(voteTypeCommandString(voteType)));
        });
    }

    public void addNo(Player player, VoteType voteType) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (timer != Timer.OFF) {
                Voter voter = new Voter(player.getUniqueId());
                if (voters.containsKey(voter.getUuid())) {
                    voter = voters.get(voter.getUuid());
                    if (voter.getVote() == 0) {
                        if (this.voteType == VoteType.NIGHT && playerMustSleep(player) && config.isPhantomSupport()) {
                            sendToPlayer(player, messages.beforeVote().mustSleep());
                            actionBarMessage(messages.duringVote().playerHasNotSlept(player.getName()));
                        }
                        else {
                            voter.voteNo();
                            sendToPlayer(player, messages.duringVote().youVoteNo());
                            actionBarMessage(messages.duringVote().playerHasVotedNo(player.getName()));
                        }
                    }
                    else sendToPlayer(player, messages.duringVote().alreadyVoted());
                }
            }
            else sendToPlayer(player, messages.beforeVote().noVoteInProg(voteTypeCommandString(voteType)));
        });
    }

    // Attempts to start a vote if all conditions are met, otherwise informs player why vote can't start.
    // start() itself is invoked from the command handler on the initiating player's region thread,
    // so the only work done here is reads of that same player's own state (always safe - a region
    // that is ticking a player owns that player's data) plus dispatching onto the global region
    // for anything that touches shared vote state.
    public void start(Player player, VoteType voteType) {
        if (!player.hasPermission("skipnight.vote." + voteTypeCommandString(voteType))) // If player doesn't have permission
            platform.player(player).sendMessage(messages.general().noPerm());
        else if (config.getWorldBlacklist().contains(player.getWorld().getName())) // If world is blacklisted
            platform.player(player).sendMessage(messages.beforeVote().worldIsBlacklisted());
        else if (!isInOverworld(player)) // If player isn't in the overworld
            platform.player(player).sendMessage(messages.beforeVote().worldNotOverworld());
        else if (voteType == VoteType.NIGHT && player.getWorld().getTime() < 12516 && !player.getWorld().hasStorm()) // If it's day and not raining, trying to skip night
            platform.player(player).sendMessage(messages.beforeVote().canOnlyVoteAtNight());
        else if (voteType == VoteType.DAY && player.getWorld().getTime() >= 12516) // If it's night, trying to skip day
            platform.player(player).sendMessage(messages.beforeVote().canOnlyVoteAtDay());
        else if (readTag(player).equalsIgnoreCase("Idle"))
            platform.player(player).sendMessage(messages.beforeVote().noVoteWhileIdle());
        else if (readTag(player).equalsIgnoreCase("Away"))
            platform.player(player).sendMessage(messages.beforeVote().noVoteWhileAway());
        else if (playerMustSleep(player) && voteType == VoteType.NIGHT && config.isPhantomSupport()) // If it's night, player hasn't slept in 3 days
            platform.player(player).sendMessage(messages.beforeVote().mustSleepNewVote());
        else {
            // Everything past this point touches shared vote state (timer, voters map,
            // world reference for the whole vote) - must run on the global region thread.
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (timer == Timer.COOLDOWN) // If the vote is in cooldown
                    sendToPlayer(player, messages.beforeVote().cooldown());
                else if (!(timer == Timer.OFF)) // If there's a vote happening
                    sendToPlayer(player, messages.duringVote().voteInProg());
                else {
                    timer = Timer.INIT;
                    this.voteType = voteType;
                    this.voteInitiator = player;
                    world = player.getWorld();
                    tick();
                }
            });
        }
    }

    /**
     * Fan-out/fan-in refresh of the vote. For every online player this:
     * <ol>
     *     <li>Dispatches a read-only task to that player's own EntityScheduler to
     *     safely read permission/world/sleeping state and send them their boss bar
     *     and any per-player messages.</li>
     *     <li>Sends the (small, immutable) result back to the global region thread,
     *     which is the only place voters/yes/no/playerCount/etc. are mutated.</li>
     * </ol>
     * {@code onComplete} runs on the global region thread once every player's report
     * has been folded back in, so callers can safely continue the state machine
     * (e.g. schedule the next tick) only after the shared counters are up to date.
     */
    private void updateAll(Component extraMessage, Runnable onComplete) {
        List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        AtomicInteger pending = new AtomicInteger(online.size());

        if (online.isEmpty()) {
            finishUpdateAll(onComplete);
            return;
        }

        for (Player player : online) {
            player.getScheduler().run(plugin, entityTask -> {
                // --- Fan-out side: only reads this player's own state, safe here. ---
                boolean hasPermission = player.hasPermission("skipnight.vote." + voteTypeCommandString(voteType));
                boolean inOverworld = isInOverworld(player);
                String tag = hasPermission && inOverworld ? readTag(player) : null;

                // Hand the report back to the global region thread to fold into shared state.
                Bukkit.getGlobalRegionScheduler().run(plugin, globalTask -> {
                    applyPlayerReport(player, hasPermission, inOverworld, tag, extraMessage);
                    if (pending.decrementAndGet() == 0) {
                        finishUpdateAll(onComplete);
                    }
                });
            }, /* retired callback if player leaves before task runs */ () -> {
                if (pending.decrementAndGet() == 0) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, globalTask -> finishUpdateAll(onComplete));
                }
            });
        }
    }

    /** Runs on the global region thread once every player report for this pass is in. */
    private void finishUpdateAll(Runnable onComplete) {
        if (voters != null) {
            playerCount = voters.size();
            away = (int) voters.values().stream().filter(Voter::isAway).count();
            idle = (int) voters.values().stream().filter(Voter::isIdle).count();
            yes = (int) voters.values().stream().filter(voter -> voter.getVote() == 1).count();
            no = (int) voters.values().stream().filter(voter -> voter.getVote() == -1).count();
        }
        if (onComplete != null) onComplete.run();
    }

    /**
     * Folds one player's report into shared vote state (voters map, message queue) and
     * dispatches that player's own messages/boss bar back onto their EntityScheduler.
     * Must only be called from the global region thread.
     */
    private void applyPlayerReport(Player player, boolean hasPermission, boolean inOverworld,
                                    String tag, Component extraMessage) {
        if (!hasPermission || voters == null) return;

        Voter voter = new Voter(player.getUniqueId());
        List<Component> messageList = new ArrayList<>();
        boolean showBossBar = false;

        if (inOverworld) {
            showBossBar = true;

            if (!voters.containsKey(voter.getUuid())) {
                voters.put(voter.getUuid(), voter);
                messageList.add(messages.duringVote().voteStarted(voteInitiator.getName(), voteTypeString()));
            }
            else voter = voters.get(voter.getUuid());

            if (player.equals(voteInitiator) && timer == Timer.INIT) {
                messageList.add(messages.duringVote().youVoteYes());
                voter.voteYes();
            }

            if (tag != null) {
                switch (tag) {
                    case "Active" -> {
                        if (!voter.isActive()) {
                            if (voter.isIdle() || voter.isAway())
                                messageList.add(messages.duringVote().back());
                            if (voter.getVote() == 0)
                                messageList.add(messages.duringVote().voteButtons(voteTypeString()));
                            voter.setActive();
                        }
                    }
                    case "Bed" -> {
                        if (!voter.isBed()) {
                            if (voter.isIdle() || voter.isAway())
                                messageList.add(messages.duringVote().back());
                            if (voter.getVote() == 0) {
                                messageList.add(messages.duringVote().inBedVotedYes());
                                voter.voteYes();
                            }
                            voter.setBed();
                        }
                    }
                    case "Idle" -> {
                        if (!voter.isIdle()) {
                            messageList.add(messages.duringVote().idle());
                            voter.resetVote();
                            voter.setIdle();
                        }
                    }
                    case "Away" -> {
                        if (!voter.isAway()) {
                            messageList.add(messages.duringVote().away());
                            voter.resetVote();
                            voter.setAway();
                        }
                    }
                }
            }

            if (extraMessage != null) messageList.add(extraMessage);
        }
        else {
            if (voters.containsKey(voter.getUuid())) {
                messageList.add(messages.duringVote().leftWorld());
                voters.remove(voter.getUuid());
            }
        }

        if (showBossBar || !messageList.isEmpty()) {
            BossBar barSnapshot = bar;
            boolean showBossBarSnapshot = showBossBar;
            player.getScheduler().run(plugin, task -> {
                if (showBossBarSnapshot && barSnapshot != null) platform.player(player).showBossBar(barSnapshot);
                for (Component messageToSend : messageList) {
                    platform.player(player).sendMessage(messageToSend);
                }
            }, null);
        }
    }

    /** Sends a single message to a player, safely, from wherever this is called. */
    private void sendToPlayer(Player player, Component message) {
        player.getScheduler().run(plugin, task -> platform.player(player).sendMessage(message), null);
    }

    /**
     * Sends an action bar message to every current voter. Must be called from the
     * global region thread (it reads the shared voters map); dispatches the actual
     * send to each recipient's own region via their EntityScheduler.
     */
    private void actionBarMessage(Component message) {
        if (voters == null) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Voter voter = new Voter(player.getUniqueId());
            if (voters.containsKey(voter.getUuid())
                    && player.hasPermission("skipnight.vote." + voteTypeCommandString(voteType))) {
                player.getScheduler().run(plugin, task -> platform.player(player).sendActionBar(message), null);
            }
        }
    }

    private boolean voteCancel() {
        return (voteType == VoteType.NIGHT && (world.getTime() > 23900 || world.getTime() < 12516)) && !world.hasStorm() ||
                (voteType == VoteType.DAY && world.getTime() > 12516 && world.getTime() < 23900);
    }

    /**
     * Whether the outcome is already mathematically locked in against the current
     * (possibly still-growing) playerCount, so we don't need to wait for every
     * registered voter to actually cast a vote. This is intentionally evaluated
     * against playerCount as it stands right now: if a new player joins the
     * overworld after this fires, they won't get a chance to vote and the result
     * won't be recomputed. That's a deliberate trade-off (speed over waiting for
     * late joiners) - see votePassed() for the actual pass/fail check used once
     * the vote is over.
     */
    private boolean thresholdDecided() {
        if (playerCount <= 0) return false;
        double threshold = config.getVoteThreshold();
        // Yes has already mathematically secured the threshold.
        if (yes >= threshold * playerCount) return true;
        // No is already mathematically guaranteed: even if every player who hasn't
        // voted yet (i.e. is registered in voters with vote == 0, not counting
        // idle/away, who are tracked separately and are not currently voting) went
        // yes, the threshold still couldn't be reached.
        int stillCouldVoteYes = playerCount - yes - no - idle - away;
        return (yes + stillCouldVoteYes) < threshold * playerCount;
    }

    /**
     * Whether enough players voted yes to pass the vote, per the configurable
     * {@code vote-threshold} (fraction of participating players, not just yes/no
     * voters - idle/away players still count against the threshold, matching how
     * playerCount is tracked elsewhere). Falls back to false (fail-safe) if no one
     * participated, avoiding a 0/0 pass-by-default.
     */
    private boolean votePassed() {
        if (playerCount <= 0) return false;
        return yes >= config.getVoteThreshold() * playerCount;
    }

    private boolean playerMustSleep(Player player) {
        // Check version for TIME_SINCE_REST added in 1.13
        if (Versions.versionCompare("1.13.0", version) <= 0) {
            return player.getStatistic(Statistic.TIME_SINCE_REST) >= 72000;
        } else return false;
    }

    @SuppressWarnings("deprecation")
    private String readTag(Player player) {
        // Read players tag, null if not there
        try {
            return player.getPlayerListName().split("#")[1];
        } catch (IndexOutOfBoundsException e) {
            return player.isSleeping() ? "Bed" : "Active";
        }
    }

    // Checks whether player is in overworld
    private boolean isInOverworld(Player player) {
        return timer == Timer.OFF ? player.getWorld().getEnvironment() == World.Environment.NORMAL : player.getWorld().equals(voteInitiator.getWorld());
    }

    public String voteTypeString() {
        return voteTypeString(this.voteType);
    }

    public String voteTypeString(VoteType voteType) {
        return switch (voteType) {
            case DAY -> messages.getDayString();
            case NIGHT -> messages.getNightString();
        };
    }

    public String voteTypeCommandString(VoteType voteType) {
        return switch (voteType) {
            case DAY -> "day";
            case NIGHT -> "night";
        };
    }
}
