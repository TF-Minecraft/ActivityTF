package net.tfminecraft.activitytf.gui;

import net.tfminecraft.activitytf.models.RewardEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RewardPreview {

    // Display text supplies the name/colour only. Bounds come from the actual item amounts.
    private static final String COLOUR = "(?:#[a-fA-F0-9]{6}|[&§][0-9a-fk-orA-FK-OR])";
    private static final Pattern QUANTITY = Pattern.compile("^(?:" + COLOUR + ")*[xX×](\\d+)\\s+");
    // The " + " between the parts of a bundle's display, with any colour codes in front of the plus.
    private static final Pattern PART_SEPARATOR = Pattern.compile("\\s+(?:" + COLOUR + ")*\\+\\s+");

    private RewardPreview() {
    }

    /** {@code lines} are the options to show; {@code rangedLines} marks each line that joins rewards of different sizes. */
    record Preview(List<String> lines, List<Boolean> rangedLines) {
        /** True when any line joins rewards of different sizes. */
        boolean ranged() {
            return ranged(lines.size());
        }

        /** True when one of the first {@code shown} lines joins rewards of different sizes. */
        boolean ranged(int shown) {
            return rangedLines.subList(0, Math.min(shown, rangedLines.size())).contains(true);
        }
    }

    private record Key(List<String> paths, List<String> labels) {
    }

    private record Range(List<String> labels, int[] min, int[] max) {
        Range include(int[] amounts) {
            int[] low = min.clone();
            int[] high = max.clone();
            for (int i = 0; i < amounts.length; i++) {
                low[i] = Math.min(low[i], amounts[i]);
                high[i] = Math.max(high[i], amounts[i]);
            }
            return new Range(labels, low, high);
        }

        boolean ranged() {
            return !Arrays.equals(min, max);
        }

        String display() {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                parts.add("#50d990x" + min[i] + (min[i] == max[i] ? "" : "–" + max[i]) + " &r" + labels.get(i));
            }
            return String.join(" &7+ ", parts);
        }
    }

    static List<String> summarize(List<RewardEntry> entries) {
        return preview(entries).lines();
    }

    /**
     * One line per kind of outcome. Entries handing over the same items (stacks of one item added
     * together) share a line with a range per item; commands, and bundles whose display cannot be
     * matched to their items, stay whole outcomes under their own text.
     */
    static Preview preview(List<RewardEntry> entries) {
        Map<Object, Range> options = new LinkedHashMap<>();
        for (RewardEntry entry : entries) {
            if (entry.weight() <= 0) {
                continue;
            }
            Map<String, Integer> totals = totals(entry);
            List<String> labels = totals == null ? null : labels(entry.display(), totals, entry.items().size());
            if (labels != null) {
                int[] amounts = totals.values().stream().mapToInt(Integer::intValue).toArray();
                Key key = new Key(List.copyOf(totals.keySet()), labels);
                Range range = options.get(key);
                options.put(key, range == null ? new Range(labels, amounts, amounts.clone()) : range.include(amounts));
            } else {
                String label = entry.display();
                if (label == null || label.isBlank()) {
                    label = "&7Other reward";
                }
                options.putIfAbsent(label, new Range(List.of(label), new int[0], new int[0]));
            }
        }
        List<String> displays = new ArrayList<>();
        List<Boolean> ranged = new ArrayList<>();
        for (Map.Entry<Object, Range> option : options.entrySet()) {
            Range range = option.getValue();
            displays.add(option.getKey() instanceof Key ? range.display() : range.labels().get(0));
            ranged.add(range.ranged());
        }
        return new Preview(List.copyOf(displays), List.copyOf(ranged));
    }

    // Item amounts by path, in first-seen order; null for commands or an entry without items.
    private static Map<String, Integer> totals(RewardEntry entry) {
        if (entry.items().isEmpty() || !entry.commands().isEmpty()) {
            return null;
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (RewardEntry.Item item : entry.items()) {
            totals.merge(item.path(), item.amount(), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        }
        return totals;
    }

    /**
     * The name of each item, in {@code totals} order. A single stack is named by its display without
     * the quantity. Otherwise the display must have one "xN name" part per item, in item order, whose N
     * matches that item's total (stacks of one item added up); if not, null, and the entry stays a
     * whole outcome.
     */
    private static List<String> labels(String display, Map<String, Integer> totals, int stacks) {
        String text = display == null ? "" : display.strip();
        if (stacks == 1) {
            String label = QUANTITY.matcher(text).replaceFirst("").strip();
            return List.of(label.isBlank() ? "&7" + totals.keySet().iterator().next() : label);
        }
        String[] parts = PART_SEPARATOR.split(text);
        if (parts.length != totals.size()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        int index = 0;
        for (int amount : totals.values()) {
            Matcher quantity = QUANTITY.matcher(parts[index++].strip());
            if (!quantity.find() || !quantity.group(1).equals(Integer.toString(amount))) {
                return null;
            }
            labels.add(quantity.replaceFirst("").strip());
        }
        return List.copyOf(labels);
    }
}
