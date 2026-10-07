package net.tfminecraft.activitytf;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import net.tfminecraft.activitytf.managers.ActivityManager;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;

class ActivityAPITest {
    @Test void mainThreadRecordsReachTheActiveManagerAndShutdownCallsAreIgnored() {
        ActivityManager manager=mock(ActivityManager.class); UUID player=UUID.randomUUID();
        try(var bukkit=mockStatic(Bukkit.class);var singleton=mockStatic(ActivityManager.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            singleton.when(ActivityManager::getInstance).thenReturn(manager);
            ActivityAPI.record(player,"craft",7); verify(manager).recordAction(player,"craft",7);
            singleton.when(ActivityManager::getInstance).thenReturn(null);
            assertDoesNotThrow(()->ActivityAPI.record(player,"craft",7)); verifyNoMoreInteractions(manager);
        }
    }
    @Test void asynchronousCallersAreRejectedBeforeLookingUpOrChangingPlayerState() {
        try(var bukkit=mockStatic(Bukkit.class);var singleton=mockStatic(ActivityManager.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class,()->ActivityAPI.record(UUID.randomUUID(),"craft",1));
            singleton.verifyNoInteractions();
        }
    }
}
