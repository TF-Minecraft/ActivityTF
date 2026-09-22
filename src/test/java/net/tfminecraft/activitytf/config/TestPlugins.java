package net.tfminecraft.activitytf.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class TestPlugins {

    private TestPlugins() {
    }

    private static final class TestPlugin extends JavaPlugin {
    }

    static JavaPlugin capturing(List<String> logged) {
        try {
            JavaPlugin plugin = new ObjenesisStd().newInstance(TestPlugin.class);

            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    logged.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });

            Field loggerField = JavaPlugin.class.getDeclaredField("logger");
            loggerField.setAccessible(true);
            loggerField.set(plugin, logger);

            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
