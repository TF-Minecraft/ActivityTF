package tfmc.justin.activity.config;

import org.bukkit.plugin.java.JavaPlugin;
import sun.reflect.ReflectionFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

// ====================================
// The hand-built JavaPlugin every ActivityConfiguration test needs, in one
// place rather than copied per class. JavaPlugin's constructor throws unless
// its classloader is a live PluginClassLoader, so one is allocated through
// ReflectionFactory and handed a logger of its own - an anonymous one, so two
// test classes running in the same JVM never see each other's lines.
//
// Everything a config parser warns about goes to that logger, which is why it
// is captured: the warnings are half of what these tests assert.
// ====================================
final class TestPlugins {

    private TestPlugins() {
    }

    private static final class TestPlugin extends JavaPlugin {
    }

    // The plugin, logging every message it emits into 'logged'
    static JavaPlugin capturing(List<String> logged) {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
            Constructor<?> bypass = rf.newConstructorForSerialization(TestPlugin.class, objectCtor);
            JavaPlugin plugin = (JavaPlugin) bypass.newInstance();

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
