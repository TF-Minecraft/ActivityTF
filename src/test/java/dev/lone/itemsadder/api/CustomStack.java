package dev.lone.itemsadder.api;

// ====================================
// A test-only stand-in for ItemsAdder's real class, on the exact fully
// qualified name ItemsAdderItems.fromItemsAdder() hardcodes. Its only job is
// to make Method#invoke throw from inside the reflected call itself, so a
// real java.lang.reflect.InvocationTargetException comes out of
// fromItemsAdder() the way ItemsAdder's own getInstance()/getItemStack() can
// while a pack is mid-reload - fromItemsAdder is private, so this is the only
// way to drive that exact exception through it without ItemsAdder installed.
// ====================================
public final class CustomStack {

    public static int getItemStackCalls = 0;

    public static CustomStack getInstance(String id) {
        return new CustomStack();
    }

    public Object getItemStack() {
        getItemStackCalls++;
        throw new RuntimeException("half-rebuilt registry");
    }
}
