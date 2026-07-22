package com.enthusiasmpvp.donornpcs;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class EnthusiaDonorNPCsPlugin extends JavaPlugin {
    private DonorNpcsConfig donorNpcsConfig;
    private DonorNpcUpdater updater;
    private BukkitTask repeatingTask;
    private BukkitTask startupTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!requireEnabledPlugin("FancyNpcs") || !requireEnabledPlugin("PlaceholderAPI")) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        reloadPluginConfig();
        registerCommand();

        getLogger().info("Enabled with " + donorNpcsConfig.entries().size() + " configured donor NPC position(s).");
    }

    @Override
    public void onDisable() {
        cancelTasks();
    }

    public DonorNpcUpdater updater() {
        return updater;
    }

    public void reloadPluginConfig() {
        reloadConfig();
        donorNpcsConfig = DonorNpcsConfig.from(getConfig());

        if (updater == null) {
            updater = new DonorNpcUpdater(this, donorNpcsConfig);
        } else {
            updater.setConfig(donorNpcsConfig);
        }

        scheduleUpdates();
    }

    public void forceUpdate() {
        updater.updateAll(true);
    }

    private boolean requireEnabledPlugin(String pluginName) {
        Plugin dependency = getServer().getPluginManager().getPlugin(pluginName);
        if (dependency == null || !dependency.isEnabled()) {
            getLogger().severe(pluginName + " is required but is not installed or enabled. Disabling EnthusiaDonorNPCs.");
            return false;
        }
        return true;
    }

    private void registerCommand() {
        PluginCommand command = getCommand("EnthusiaDonorNPCs");
        if (command == null) {
            getLogger().severe("Command EnthusiaDonorNPCs is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        DonorNpcsCommand executor = new DonorNpcsCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void scheduleUpdates() {
        if (!isEnabled() || donorNpcsConfig == null || updater == null) {
            return;
        }

        cancelTasks();

        long intervalTicks = donorNpcsConfig.updateIntervalMinutes() * 60L * 20L;
        repeatingTask = getServer().getScheduler().runTaskTimer(this, () -> updater.updateAll(false), intervalTicks, intervalTicks);

        // Delay the startup update so FancyNpcs and PlaceholderAPI have finished their own loading work.
        startupTask = getServer().getScheduler().runTaskLater(this, () -> updater.updateAll(false), 10L * 20L);
    }

    private void cancelTasks() {
        if (repeatingTask != null) {
            repeatingTask.cancel();
            repeatingTask = null;
        }
        if (startupTask != null) {
            startupTask.cancel();
            startupTask = null;
        }
    }
}
