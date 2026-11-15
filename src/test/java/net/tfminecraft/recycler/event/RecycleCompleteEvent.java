package net.tfminecraft.recycler.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Test stand-in for the Recycler plugin's event, found by class name like the real one. */
public class RecycleCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean broken;

    public RecycleCompleteEvent(Player player) {
        this(player, false);
    }

    public RecycleCompleteEvent(Player player, boolean broken) {
        this.player = player;
        this.broken = broken;
    }

    public Player getPlayer() {
        if (broken) {
            throw new IllegalStateException("broken on purpose");
        }
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
