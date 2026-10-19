import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class CommunityGoalPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static class GoalData {
        final String id;
        final String displayName;
        final String type;
        final Set<String> validTargets;
        final int baseTarget;
        final int perPlayerTarget;

        GoalData(String id, String displayName, String type, List<String> targets, int baseTarget, int perPlayerTarget) {
            this.id = id;
            this.displayName = displayName;
            this.type = type.toUpperCase(Locale.ROOT);
            this.validTargets = new HashSet<String>();
            for (String t : targets) {
                this.validTargets.add(t.toUpperCase(Locale.ROOT));
            }
            this.baseTarget = baseTarget;
            this.perPlayerTarget = perPlayerTarget;
        }

        boolean matches(String candidate) {
            return validTargets.contains("ANY") || validTargets.contains(candidate.toUpperCase(Locale.ROOT));
        }
    }

    private final List<GoalData> goalList = new ArrayList<GoalData>();
    private final Map<UUID, Integer> activeContributions = new HashMap<UUID, Integer>();
    private final Set<String> placedBlockKeys = new HashSet<String>();
    private final Random random = new Random();

    private GoalData currentGoal = null;
    private int currentProgress = 0;
    private int currentTarget = 100;
    private BossBar bossBar = null;
    private BukkitTask nextGoalTask = null;
    private BukkitTask bossBarWatchdog = null;
    private String previousGoalId = "";

    private File rewardFile;
    private FileConfiguration rewardConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRewardFile();
        loadGoalsFromConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("reward") != null) {
            getCommand("reward").setExecutor(this);
        }
        if (getCommand("goaladmin") != null) {
            getCommand("goaladmin").setExecutor(this);
        }

        startRandomGoal();

        // Har 4 second me Geyser/Bedrock aur Java players ka BossBar check karega
        bossBarWatchdog = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                if (bossBar != null && currentGoal != null) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (!bossBar.getPlayers().contains(p)) {
                            bossBar.addPlayer(p);
                        }
                    }
                }
            }
        }, 80L, 80L);

        getLogger().info("CommunityGoalPlugin successfully loaded and enabled!");
    }

    @Override
    public void onDisable() {
        if (nextGoalTask != null) {
            nextGoalTask.cancel();
        }
        if (bossBarWatchdog != null) {
            bossBarWatchdog.cancel();
        }
        if (bossBar != null) {
            bossBar.removeAll();
        }
        saveRewardFile();
    }

    private void loadRewardFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        rewardFile = new File(getDataFolder(), "data.yml");
        if (!rewardFile.exists()) {
            try {
                rewardFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create data.yml: " + e.getMessage());
            }
        }
        rewardConfig = YamlConfiguration.loadConfiguration(rewardFile);
    }

    private void saveRewardFile() {
        if (rewardConfig == null || rewardFile == null) return;
        try {
            rewardConfig.save(rewardFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    private void loadGoalsFromConfig() {
        goalList.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("goals");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection g = sec.getConfigurationSection(key);
            if (g == null) continue;

            String name = g.getString("name", key);
            String type = g.getString("type", "BLOCK_BREAK");
            List<String> targets = g.getStringList("targets");
            if (targets == null || targets.isEmpty()) {
                targets = new ArrayList<String>();
                targets.add("ANY");
            }
            int base = Math.max(1, g.getInt("base-amount", 50));
            int perPlayer = Math.max(0, g.getInt("per-player-amount", 25));

            goalList.add(new GoalData(key, name, type, targets, base, perPlayer));
        }
    }

    private void startRandomGoal() {
        if (nextGoalTask != null) {
            nextGoalTask.cancel();
            nextGoalTask = null;
        }

        if (goalList.isEmpty()) {
            getLogger().warning("No goals found in config.yml!");
            return;
        }

        List<GoalData> pool = new ArrayList<GoalData>(goalList);
        if (pool.size() > 1) {
            for (int i = pool.size() - 1; i >= 0; i--) {
                if (pool.get(i).id.equals(previousGoalId)) {
                    pool.remove(i);
                }
            }
        }

        currentGoal = pool.get(random.nextInt(pool.size()));
        previousGoalId = currentGoal.id;

        int onlinePlayers = Math.max(1, Bukkit.getOnlinePlayers().size());
        currentTarget = Math.max(1, currentGoal.baseTarget + (currentGoal.perPlayerTarget * onlinePlayers));
        currentProgress = 0;
        activeContributions.clear();
        placedBlockKeys.clear();

        createOrResetBossBar();

        Bukkit.broadcastMessage(color("&8[&a&lServerGoal&8] &eNew Server Goal started: &b"
                + currentGoal.displayName + " &7(Target: &a" + currentTarget + "&7)"));

        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
            playSafeSound(p, "ENTITY_EXPERIENCE_ORB_PICKUP");
        }
    }

    private void createOrResetBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
        }

        BarColor barColor = BarColor.GREEN;
        try {
            barColor = BarColor.valueOf(getConfig().getString("bossbar.color", "GREEN").toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
        }

        BarStyle barStyle = BarStyle.SOLID;
        try {
            barStyle = BarStyle.valueOf(getConfig().getString("bossbar.style", "SOLID").toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
        }

        bossBar = Bukkit.createBossBar("", barColor, barStyle);
        bossBar.setVisible(true);
        updateBossBarTitleAndProgress();
    }

    private void updateBossBarTitleAndProgress() {
        if (bossBar == null || currentGoal == null) return;

        double ratio = (double) currentProgress / (double) currentTarget;
        if (ratio < 0.0) ratio = 0.0;
        if (ratio > 1.0) ratio = 1.0;

        int percent = (int) Math.round(ratio * 100.0);

        String format = getConfig().getString("bossbar.format",
                "&e&lGOAL: &f%goal_name% &7| &a%current%&7/&a%target% &8(&b%percent%%%&8)");
        String title = format
                .replace("%goal_name%", currentGoal.displayName)
                .replace("%current%", String.valueOf(currentProgress))
                .replace("%target%", String.valueOf(currentTarget))
                .replace("%percent%", String.valueOf(percent));

        bossBar.setTitle(color(title));
        bossBar.setProgress(ratio);
    }

    // Bedrock/Geyser World Change Fix
    private void reattachBossBarDelayed(final Player player) {
        if (bossBar == null) return;
        bossBar.removePlayer(player);
        long delay = Math.max(20L, getConfig().getLong("bossbar.geyser-delay-ticks", 60L));
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline() && bossBar != null && currentGoal != null) {
                    bossBar.addPlayer(player);
                }
            }
        }, delay);
    }

    private void recordProgress(Player player, int amount) {
        if (currentGoal == null || amount <= 0) return;

        int needed = currentTarget - currentProgress;
        int added = Math.min(amount, needed);
        if (added <= 0) return;

        UUID uuid = player.getUniqueId();
        int oldVal = activeContributions.containsKey(uuid) ? activeContributions.get(uuid) : 0;
        activeContributions.put(uuid, oldVal + added);
        currentProgress += added;

        updateBossBarTitleAndProgress();

        if (currentProgress >= currentTarget) {
            finishCurrentGoal();
        }
    }

    private void finishCurrentGoal() {
        if (currentGoal == null) return;

        GoalData completed = currentGoal;
        int totalTarget = currentTarget;
        Map<UUID, Integer> contributors = new HashMap<UUID, Integer>(activeContributions);

        currentGoal = null;
        activeContributions.clear();
        placedBlockKeys.clear();

        if (bossBar != null) {
            bossBar.setTitle(color("&a&lGOAL COMPLETED! &eType &b/reward &eto claim!"));
            bossBar.setProgress(1.0);
        }

        int maxDiamonds = getConfig().getInt("rewards.max-diamonds", 16);
        int maxEmeralds = getConfig().getInt("rewards.max-emeralds", 24);
        int maxIron = getConfig().getInt("rewards.max-iron-ingots", 32);
        int maxGapples = getConfig().getInt("rewards.max-golden-apples", 6);
        int maxXp = getConfig().getInt("rewards.max-xp", 500);

        for (Map.Entry<UUID, Integer> entry : contributors.entrySet()) {
            UUID uuid = entry.getKey();
            int pts = entry.getValue();
            if (pts <= 0) continue;

            double share = Math.min(1.0, (double) pts / (double) totalTarget);
            int pct = Math.max(1, (int) Math.round(share * 100.0));

            int d = Math.max(1, (int) Math.round(maxDiamonds * share));
            int em = Math.max(1, (int) Math.round(maxEmeralds * share));
            int ir = Math.max(2, (int) Math.round(maxIron * share));
            int gap = share >= 0.15 ? Math.max(1, (int) Math.round(maxGapples * share)) : 0;
            int xp = Math.max(25, (int) Math.round(maxXp * share));

            String path = "pending." + uuid.toString();
            rewardConfig.set(path + ".diamonds", rewardConfig.getInt(path + ".diamonds", 0) + d);
            rewardConfig.set(path + ".emeralds", rewardConfig.getInt(path + ".emeralds", 0) + em);
            rewardConfig.set(path + ".iron", rewardConfig.getInt(path + ".iron", 0) + ir);
            rewardConfig.set(path + ".gapples", rewardConfig.getInt(path + ".gapples", 0) + gap);
            rewardConfig.set(path + ".xp", rewardConfig.getInt(path + ".xp", 0) + xp);
            rewardConfig.set(path + ".percent", pct);

            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                sendGoalCompletedScreen(online, completed.displayName, pct);
            }
        }

        saveRewardFile();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!contributors.containsKey(p.getUniqueId())) {
                p.sendTitle(
                        color("&a&lGOAL COMPLETED!"),
                        color("&eGoal has been completed! Participate in the next goal!"),
                        10, 80, 20
                );
                p.sendMessage(color("&8[&a&lServerGoal&8] &aGoal has been completed! Agle goal me contribute karke reward jeetein!"));
                playSafeSound(p, "ENTITY_PLAYER_LEVELUP");
            }
        }

        int cooldownSec = Math.max(10, getConfig().getInt("cooldown-seconds", 600));
        Bukkit.broadcastMessage(color("&8[&a&lServerGoal&8] &7Next random goal will start in &e" + (cooldownSec / 60) + " minutes&7."));

        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                if (bossBar != null && currentGoal == null) {
                    bossBar.removeAll();
                }
            }
        }, 200L);

        nextGoalTask = Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                startRandomGoal();
            }
        }, cooldownSec * 20L);
    }

    private void sendGoalCompletedScreen(Player player, String goalName, int percent) {
        player.sendTitle(
                color("&a&lGOAL COMPLETED!"),
                color("&eGoal has been completed! Type &b/reward &eto claim"),
                10, 100, 20
        );
        player.sendMessage(color("&8&m--------------------------------------------------"));
        player.sendMessage(color("&a&lGoal has been completed! &eType &b/reward &ein chat to claim your reward"));
        player.sendMessage(color("&7Goal: &f" + goalName + " &8| &7Your Contribution: &b" + percent + "%"));
        player.sendMessage(color("&8&m--------------------------------------------------"));
        playSafeSound(player, "ENTITY_PLAYER_LEVELUP");
    }

    private void sendOfflineJoinScreen(Player player) {
        player.sendTitle(
                color("&6&lREWARD READY!"),
                color("&eYou contributed! Type &b/reward &ein chat to claim"),
                10, 120, 20
        );
        player.sendMessage(color("&8&m--------------------------------------------------"));
        player.sendMessage(color("&a&lYou have contributed to complete the server goal"));
        player.sendMessage(color("&eType &b/reward &ein chat to claim your reward"));
        player.sendMessage(color("&8&m--------------------------------------------------"));
        playSafeSound(player, "ENTITY_PLAYER_LEVELUP");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        reattachBossBarDelayed(player);

        final String path = "pending." + player.getUniqueId().toString();
        if (rewardConfig.contains(path)) {
            long delay = Math.max(40L, getConfig().getLong("bossbar.geyser-delay-ticks", 60L));
            Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                @Override
                public void run() {
                    if (player.isOnline() && rewardConfig.contains(path)) {
                        sendOfflineJoinScreen(player);
                    }
                }
            }, delay);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reattachBossBarDelayed(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        reattachBossBarDelayed(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (bossBar != null) {
            bossBar.removePlayer(event.getPlayer());
        }
    }

    private String toBlockKey(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        placedBlockKeys.add(toBlockKey(event.getBlock().getLocation()));

        if (currentGoal != null && currentGoal.type.equals("BLOCK_PLACE")) {
            String mat = event.getBlockPlaced().getType().name();
            if (currentGoal.matches(mat)) {
                recordProgress(event.getPlayer(), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String key = toBlockKey(event.getBlock().getLocation());
        if (placedBlockKeys.remove(key)) {
            return;
        }

        if (currentGoal != null && currentGoal.type.equals("BLOCK_BREAK")) {
            String mat = event.getBlock().getType().name();
            if (currentGoal.matches(mat)) {
                recordProgress(event.getPlayer(), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null) return;

        if (currentGoal != null && currentGoal.type.equals("ENTITY_KILL")) {
            String name = event.getEntityType().name();
            if (currentGoal.matches(name)) {
                recordProgress(killer, 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        if (currentGoal != null && currentGoal.type.equals("FISH_CATCH")) {
            recordProgress(event.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceExtractEvent event) {
        if (currentGoal != null && currentGoal.type.equals("FURNACE_EXTRACT")) {
            String mat = event.getItemType().name();
            if (currentGoal.matches(mat)) {
                recordProgress(event.getPlayer(), event.getItemAmount());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent event) {
        if (event.getAmount() <= 0) return;

        if (currentGoal != null && currentGoal.type.equals("EXP_GAIN")) {
            recordProgress(event.getPlayer(), event.getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        if (currentGoal != null && currentGoal.type.equals("CONSUME")) {
            String mat = event.getItem().getType().name();
            if (currentGoal.matches(mat)) {
                recordProgress(event.getPlayer(), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player)) return;
        Player breeder = (Player) event.getBreeder();

        if (currentGoal != null && currentGoal.type.equals("BREED")) {
            String type = event.getEntityType().name();
            if (currentGoal.matches(type)) {
                recordProgress(breeder, 1);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("reward")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use /reward.");
                return true;
            }
            giveSavedRewards((Player) sender);
            return true;
        }

        if (command.getName().equalsIgnoreCase("goaladmin")) {
            if (!sender.hasPermission("communitygoal.admin")) {
                sender.sendMessage(color("&cNo permission."));
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(color("&eUsage: /goaladmin <skip|reload>"));
                return true;
            }
            if (args[0].equalsIgnoreCase("skip")) {
                startRandomGoal();
                sender.sendMessage(color("&aSkipped to a new random goal!"));
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadGoalsFromConfig();
                updateBossBarTitleAndProgress();
                sender.sendMessage(color("&aConfig reloaded!"));
                return true;
            }
        }
        return false;
    }

    private void giveSavedRewards(Player player) {
        String path = "pending." + player.getUniqueId().toString();
        if (!rewardConfig.contains(path)) {
            player.sendMessage(color("&8[&a&lServerGoal&8] &cYou have no pending rewards to claim!"));
            return;
        }

        int d = rewardConfig.getInt(path + ".diamonds", 0);
        int em = rewardConfig.getInt(path + ".emeralds", 0);
        int ir = rewardConfig.getInt(path + ".iron", 0);
        int gap = rewardConfig.getInt(path + ".gapples", 0);
        int xp = rewardConfig.getInt(path + ".xp", 0);
        int pct = rewardConfig.getInt(path + ".percent", 10);

        giveItemSafely(player, Material.DIAMOND, d);
        giveItemSafely(player, Material.EMERALD, em);
        giveItemSafely(player, Material.IRON_INGOT, ir);
        giveItemSafely(player, Material.GOLDEN_APPLE, gap);

        if (xp > 0) {
            player.giveExp(xp);
        }

        List<String> cmds = getConfig().getStringList("rewards.custom-commands");
        if (cmds != null) {
            for (String c : cmds) {
                String parsed = c.replace("%player%", player.getName()).replace("%percent%", String.valueOf(pct));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            }
        }

        rewardConfig.set(path, null);
        saveRewardFile();

        player.sendTitle(
                color("&a&lREWARD CLAIMED!"),
                color("&fItems have been added to your inventory!"),
                10, 60, 20
        );
        player.sendMessage(color("&8[&a&lServerGoal&8] &aClaimed: &b"
                + d + "x Diamond, " + em + "x Emerald, " + ir + "x Iron, "
                + (gap > 0 ? gap + "x Golden Apple, " : "") + xp + " XP!"));
        playSafeSound(player, "ENTITY_PLAYER_LEVELUP");
    }

    private void giveItemSafely(Player player, Material mat, int amount) {
        if (amount <= 0) return;
        ItemStack stack = new ItemStack(mat, amount);
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        for (ItemStack drop : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private void playSafeSound(Player player, String soundName) {
        try {
            Sound s = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), s, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
