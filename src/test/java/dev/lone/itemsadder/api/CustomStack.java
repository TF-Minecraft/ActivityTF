package dev.lone.itemsadder.api;

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
