package net.tfminecraft.activitytf.gui;

import net.tfminecraft.activitytf.models.RewardEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class RewardPreview {

    // Display text supplies the name/colour only. Bounds come from the actual item amounts.
    private static final String COLOUR = "(?:#[a-fA-F0-9]{6}|[&§][0-9a-fk-orA-FK-OR])";
    private static final Pattern QUANTITY = Pattern.compile("^(?:" + COLOUR + ")*[xX×]\\d+\\s+");

    private RewardPreview() {
    }

    private record Key(String path, String label) {
    }

    private record Range(String label, int min, int max) {
        Range include(int amount) {
            return new Range(label, Math.min(min, amount), Math.max(max, amount));
        }

        String display() {
            return "#50d990x" + min + (min == max ? "" : "–" + max) + " &r" + label;
        }
    }

    static List<String> summarize(List<RewardEntry> entries) {
        Map<Object, Range> options = new LinkedHashMap<>();
        for (RewardEntry entry : entries) {
            if (entry.weight() <= 0) {
                continue;
            }
            if (entry.items().size() == 1 && entry.commands().isEmpty()) {
                RewardEntry.Item item = entry.items().get(0);
                String label = entry.display() == null ? "" : QUANTITY.matcher(entry.display()).replaceFirst("").strip();
                if (label.isBlank()) {
                    label = "&7" + item.path();
                }
                Key key = new Key(item.path(), label);
                Range range = options.get(key);
                options.put(key, range == null ? new Range(label, item.amount(), item.amount())
                    : range.include(item.amount()));
            } else {
                // Commands and bundles are whole outcomes; do not invent per-item ranges for them.
                String label = entry.display();
                if (label == null || label.isBlank()) {
                    label = "&7Other reward";
                }
                options.putIfAbsent(label, new Range(label, 0, 0));
            }
        }
        List<String> displays = new ArrayList<>();
        options.forEach((key, range) -> displays.add(key instanceof Key ? range.display() : range.label()));
        return displays;
    }
}
