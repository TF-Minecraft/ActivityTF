package tfmc.justin.activity.models;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ====================================
// One player's week. Plain POJO with no Bukkit in it, so the rollover and
// award rules are unit testable and the store stays a dumb serializer.
// Mutated on the main thread only.
// ====================================
public class PlayerData {

    // ====================================
    // Written only on the main thread, but PlaceholderAPI reads a player's bar
    // off one - so the fields it touches are published safely rather than left
    // to whatever the reader's cache happens to hold.
    // ====================================
    private final Map<String, Integer> daily = new ConcurrentHashMap<>();

    private volatile int points;
    private volatile String weekKey;
    private volatile String dayKey;
    // Milestones already paid out this week
    private volatile int claimed;

    public PlayerData(String weekKey, String dayKey) {
        this.weekKey = weekKey;
        this.dayKey = dayKey;
    }

    public PlayerData(int points, String weekKey, String dayKey, int claimed, Map<String, Integer> daily) {
        this.points = points;
        this.weekKey = weekKey;
        this.dayKey = dayKey;
        this.claimed = claimed;
        this.daily.putAll(daily);
    }

    // ====================================
    // Lazy rollover, called before anything reads or writes this player.
    // A new week wipes everything; a new day only wipes the daily counters,
    // so points earned earlier in the week survive.
    // ====================================
    public boolean roll(String weekKey, String dayKey) {
        boolean changed = false;

        if (!this.weekKey.equals(weekKey)) {
            points = 0;
            claimed = 0;
            daily.clear();
            this.weekKey = weekKey;
            changed = true;
        }

        if (!this.dayKey.equals(dayKey)) {
            daily.clear();
            this.dayKey = dayKey;
            changed = true;
        }

        return changed;
    }

    // ====================================
    // Points come from the change in "what today's count is worth", so no
    // per-day awarded counter has to be kept in sync with the action count.
    // ponytail: changing every/dailyCap mid-day can under- or over-award that
    // day by the difference; acceptable
    // ====================================
    public RecordResult record(int amount, ActivityDef def, int max, int rewardEvery) {
        int before = daily.getOrDefault(def.id(), 0);
        // Saturate: a bogus /activity add 2000000000 twice must not wrap the
        // counter negative and hand out awards all over again
        int after = (int) Math.min(Integer.MAX_VALUE, (long) before + amount);
        daily.put(def.id(), after);

        int earned = def.worth(after) - def.worth(before);
        if (earned <= 0) {
            return new RecordResult(0, 0);
        }

        int pointsBefore = points;
        addPoints(earned, max);

        return new RecordResult(points - pointsBefore, points / rewardEvery - pointsBefore / rewardEvery);
    }

    public void addPoints(int p, int max) {
        points = Math.max(0, Math.min(max, points + p));
    }

    // Stored points can exceed the bar after bar.max is lowered or the file
    // came from an older scale; claimable and percent both assume they do not
    public boolean clamp(int max) {
        if (points <= max) {
            return false;
        }
        points = max;
        return true;
    }

    public int claimable(int rewardEvery) {
        return Math.max(0, points / rewardEvery - claimed);
    }

    public void reset(String weekKey, String dayKey) {
        points = 0;
        claimed = 0;
        daily.clear();
        this.weekKey = weekKey;
        this.dayKey = dayKey;
    }

    public int points() {
        return points;
    }

    public int count(String id) {
        return daily.getOrDefault(id, 0);
    }

    public Map<String, Integer> daily() {
        return daily;
    }

    public String weekKey() {
        return weekKey;
    }

    public String dayKey() {
        return dayKey;
    }

    public int claimed() {
        return claimed;
    }

    public void setClaimed(int claimed) {
        this.claimed = claimed;
    }
}
