package net.tfminecraft.activitytf.testsupport;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Populates Paper's cached registries before proxy-only tests can observe empty registries. */
public final class RegistryBootstrap implements LauncherSessionListener {

    private static boolean initialized;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        synchronized (RegistryBootstrap.class) {
            if (initialized) {
                return;
            }
            if (Bukkit.getServer() != null) {
                throw new IllegalStateException("Registry bootstrap must run before a test installs Bukkit.server");
            }
            try {
                MockBukkit.mock();
                // MockBukkit loads registry entries lazily; resolve them before clearing its server.
                new ItemStack(Material.PAPER);
                Sound.BLOCK_NOTE_BLOCK_BIT.getKey();
            } finally {
                MockBukkit.unmock();
            }
            initialized = true;
        }
    }
}
