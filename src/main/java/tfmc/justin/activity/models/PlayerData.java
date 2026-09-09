package tfmc.justin.activity.models;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ====================================
// One player's week. Plain POJO with no Bukkit in it, so the rollover and
// award rules are unit testable and the store stays a dumb serializer.
// Mutated on the main thread only.
// ====================================
public class PlayerData {

    public static final int MAX_POINTS = 100;

    // ====================================
    // Written only on the main thread, but PlaceholderAPI reads a player's bar
    // off one - so the fields it touches are published safely rather than left
    // to whatever the reader's cache happens to hold.
    // ====================================
    private final Map<String, Integer> daily = new ConcurrentHashMap<>();

    private volatile int points;
    private volatile String weekKey;
    private volatile String dayKey;
    private boolean rewarded;
    private boolean pendingReward;

    public PlayerData(String weekKey, String dayKey) {
        this.weekKey = weekKey;
        this.dayKey = dayKey;
    }

    public PlayerData(int points, String weekKey, String dayKey, boolean rewarded, boolean pendingReward,
                      Map<String, Integer> daily) {
        this.points = points;
        this.weekKey = weekKey;
        this.dayKey = dayKey;
        this.rewarded = rewarded;
        this.pendingReward = pendingReward;
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
            rewarded = false;
            pendingReward = false;
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
    // Points are awarded on the transition across the goal, not on a stored
    // "awarded today" flag - one less field to keep in sync.
    // ponytail: lowering daily-goal mid-day below a player's count skips that
    // day's award; acceptable
    // ====================================
    public RecordResult record(String id, int amount, int goal, int points) {
        int before = daily.getOrDefault(id, 0);
        // Saturate: a bogus /activity add 2000000000 twice must not wrap the
        // counter negative and hand out the goal award all over again
        int after = (int) Math.min(Integer.MAX_VALUE, (long) before + amount);
        daily.put(id, after);

        if (before >= goal || after < goal) {
            return new RecordResult(false, 0, false);
        }

        int pointsBefore = this.points;
        addPoints(points);

        return new RecordResult(true, this.points - pointsBefore, pointsBefore < MAX_POINTS && this.points >= MAX_POINTS);
    }

    public void addPoints(int p) {
        points = Math.max(0, Math.min(MAX_POINTS, points + p));
    }

    public void reset(String weekKey, String dayKey) {
        points = 0;
        rewarded = false;
        pendingReward = false;
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

    public boolean rewarded() {
        return rewarded;
    }

    public void setRewarded(boolean rewarded) {
        this.rewarded = rewarded;
    }

    public boolean pendingReward() {
        return pendingReward;
    }

    public void setPendingReward(boolean pendingReward) {
        this.pendingReward = pendingReward;
    }
}
