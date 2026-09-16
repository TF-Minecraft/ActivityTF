package tfmc.justin.activity;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import tfmc.justin.activity.commands.ActivityCommand;
import tfmc.justin.activity.gui.ActivityGui;
import tfmc.justin.activity.hooks.PlaceholderHook;
import tfmc.justin.activity.listeners.AdvancedCraftListener;
import tfmc.justin.activity.listeners.ArchaeologyListener;
import tfmc.justin.activity.listeners.BattleListener;
import tfmc.justin.activity.listeners.CasinoWinListener;
import tfmc.justin.activity.listeners.CharacterChatListener;
import tfmc.justin.activity.listeners.CraftListener;
import tfmc.justin.activity.listeners.DishCookedListener;
import tfmc.justin.activity.listeners.FurnitureListener;
import tfmc.justin.activity.listeners.GeigerListener;
import tfmc.justin.activity.listeners.InjuryListener;
import tfmc.justin.activity.listeners.InstrumentListener;
import tfmc.justin.activity.listeners.JoinListener;
import tfmc.justin.activity.listeners.MarketSaleListener;
import tfmc.justin.activity.listeners.ProfessionUpgradeListener;
import tfmc.justin.activity.listeners.ProfessionXpListener;
import tfmc.justin.activity.listeners.VehicleBuildListener;
import tfmc.justin.activity.listeners.VoteListener;
import tfmc.justin.activity.managers.ActivityManager;

public class ActivityPlugin extends JavaPlugin {

    // Held only so onDisable can take it back out of PlaceholderAPI - an
    // expansion left registered keeps this classloader alive across a reload
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
        // Vanilla crafting - no soft-depend to check, unlike registerHooks below
        getServer().getPluginManager().registerEvents(new CraftListener(manager), this);
        getServer().getPluginManager().registerEvents(gui, this);

        registerHooks();

        getLogger().info("activity has been enabled!");
    }

    @Override
    public void onDisable() {
        // ====================================
        // Save first: the final write must land no matter what happens next.
        // Unregistering from PAPI runs after, wrapped so a teardown throw from
        // another plugin's classloader can never follow-skip or interrupt it.
        // ====================================
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

    // ====================================
    // Source plugins are compiled against but optional at runtime. Each
    // listener is only constructed inside its own isPluginEnabled check, so
    // the class - and the missing event type it references - is never loaded
    // on a server that does not have the plugin installed.
    // ====================================
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
