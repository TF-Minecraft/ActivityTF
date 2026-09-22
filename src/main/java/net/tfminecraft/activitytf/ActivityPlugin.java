package net.tfminecraft.activitytf;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.activitytf.commands.ActivityCommand;
import net.tfminecraft.activitytf.gui.ActivityGui;
import net.tfminecraft.activitytf.hooks.PlaceholderHook;
import net.tfminecraft.activitytf.listeners.AdvancedCraftListener;
import net.tfminecraft.activitytf.listeners.ArchaeologyListener;
import net.tfminecraft.activitytf.listeners.BattleListener;
import net.tfminecraft.activitytf.listeners.CasinoWinListener;
import net.tfminecraft.activitytf.listeners.CharacterChatListener;
import net.tfminecraft.activitytf.listeners.CraftListener;
import net.tfminecraft.activitytf.listeners.DishCookedListener;
import net.tfminecraft.activitytf.listeners.FurnitureListener;
import net.tfminecraft.activitytf.listeners.GeigerListener;
import net.tfminecraft.activitytf.listeners.InjuryListener;
import net.tfminecraft.activitytf.listeners.InstrumentListener;
import net.tfminecraft.activitytf.listeners.JoinListener;
import net.tfminecraft.activitytf.listeners.MarketSaleListener;
import net.tfminecraft.activitytf.listeners.MmoItemsStationListener;
import net.tfminecraft.activitytf.listeners.ProfessionUpgradeListener;
import net.tfminecraft.activitytf.listeners.ProfessionXpListener;
import net.tfminecraft.activitytf.listeners.VehicleBuildListener;
import net.tfminecraft.activitytf.listeners.VoteListener;
import net.tfminecraft.activitytf.managers.ActivityManager;

public class ActivityPlugin extends JavaPlugin {

    private PlaceholderHook placeholderHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ActivityManager manager = ActivityManager.getInstance(this);
        manager.initialize();

        ActivityGui gui = new ActivityGui(manager);

        ActivityCommand command = new ActivityCommand(manager, gui);
        getCommand("activity").setExecutor(command);
        getCommand("activity").setTabCompleter(command);

        getServer().getPluginManager().registerEvents(new JoinListener(manager), this);
        getServer().getPluginManager().registerEvents(new CraftListener(manager), this);
        getServer().getPluginManager().registerEvents(gui, this);

        registerHooks();

        getLogger().info("activity has been enabled!");
    }

    @Override
    public void onDisable() {
        if (ActivityManager.getInstance() != null) {
            ActivityManager.getInstance().shutdown();
        }

        if (placeholderHook != null) {
            try {
                placeholderHook.unregister();
            } catch (Throwable t) {
                getLogger().warning("Failed to unregister the PlaceholderAPI expansion: " + t.getMessage());
            }
            placeholderHook = null;
        }
        getLogger().info("activity has been disabled!");
    }

    private void registerHooks() {
        if (Bukkit.getPluginManager().isPluginEnabled("VotingPlugin")) {
            getServer().getPluginManager().registerEvents(new VoteListener(this, ActivityManager.getInstance()), this);
            getLogger().info("Hooked into VotingPlugin.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("MusicalInstruments")) {
            getServer().getPluginManager().registerEvents(new InstrumentListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into MusicalInstruments.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("geiger_counter")) {
            getServer().getPluginManager().registerEvents(new GeigerListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into geiger_counter.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("MMOCore")) {
            getServer().getPluginManager().registerEvents(
                new ProfessionXpListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into MMOCore.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            getServer().getPluginManager().registerEvents(
                new MmoItemsStationListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into MMOItems.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("VFBuilders")) {
            getServer().getPluginManager().registerEvents(
                new VehicleBuildListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into VFBuilders.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("RPCharacters")) {
            getServer().getPluginManager().registerEvents(
                new CharacterChatListener(ActivityManager.getInstance()), this);
            getServer().getPluginManager().registerEvents(
                new InjuryListener(ActivityManager.getInstance()), this);
            getServer().getPluginManager().registerEvents(
                new ProfessionUpgradeListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into RPCharacters.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("InteractibleFurniture")) {
            getServer().getPluginManager().registerEvents(
                new FurnitureListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into InteractibleFurniture.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("SimpleFactions")) {
            getServer().getPluginManager().registerEvents(
                new BattleListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into SimpleFactions.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedCrafting")) {
            getServer().getPluginManager().registerEvents(
                new AdvancedCraftListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into AdvancedCrafting.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Archaeo")) {
            getServer().getPluginManager().registerEvents(
                new ArchaeologyListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into Archaeo.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("MarketBlock")) {
            getServer().getPluginManager().registerEvents(
                new MarketSaleListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into MarketBlock.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Games")) {
            getServer().getPluginManager().registerEvents(
                new CasinoWinListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into Games.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Cooking")) {
            getServer().getPluginManager().registerEvents(
                new DishCookedListener(ActivityManager.getInstance()), this);
            getLogger().info("Hooked into Cooking.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderHook = new PlaceholderHook(ActivityManager.getInstance());
            placeholderHook.register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }
    }
}
