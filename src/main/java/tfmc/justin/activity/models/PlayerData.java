package tfmc.justin.activity.models;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// ====================================
// One player's week. Plain POJO with no Bukkit in it, so the rollover and
// award rules are unit testable and the store stays a dumb serializer.
// Mutated on the main thread only.
// ====================================
public class PlayerData {

    // How many tasks a player is handed per day
    public static final int TASKS_PER_DAY = 7;

    // ====================================
    // Written only on the main thread, but PlaceholderAPI reads a player's bar
    // off one - so the fields it touches are published safely rather than left
    // to whatever the reader's cache happens to hold.
    // ====================================
    private final Map<String, Integer> daily = new ConcurrentHashMap<>();

    // ====================================
    // Today's drawn task ids in slot order, and the ids of those the player
    // has revealed. Draws are distinct, so an id stands for its slot. Empty
    // means "not drawn yet today". Only a revealed task earns anything.
    // ====================================
    private final List<String> tasks = new CopyOnWriteArrayList<>();
    private final Set<String> revealed = ConcurrentHashMap.newKeySet();

    private volatile int points;
    // Points accepted today across all activities; anything past dailyMax is
    // dropped and never reaches the weekly total
    private volatile int dailyPoints;
    private volatile String weekKey;
    private volatile String dayKey;
    // ====================================
    // The highest milestone already paid out, not a milestone count: editing
    // bar.milestones and reloading must not make a milestone that was already
    // handed over claimable a second time.
    // ====================================
    private volatile int claimedPoints;

    public PlayerData(String weekKey, String dayKey) {
        this.weekKey = weekKey;
        this.dayKey = dayKey;
    }

    public PlayerData(int points, int dailyPoints, String weekKey, String dayKey, int claimedPoints,
                      Map<String, Integer> daily) {
        this.points = points;
        this.dailyPoints = dailyPoints;
        this.weekKey = weekKey;
        this.dayKey = dayKey;
        this.claimedPoints = claimedPoints;
        this.daily.putAll(daily);
    }

    public PlayerData(int points, int dailyPoints, String weekKey, String dayKey, int claimedPoints,
                      Map<String, Integer> daily, List<String> tasks, Collection<String> revealed) {
        this(points, dailyPoints, weekKey, dayKey, claimedPoints, daily);
        for (String id : tasks) {
            if (!this.tasks.contains(id) && this.tasks.size() < TASKS_PER_DAY) {
                this.tasks.add(id);
            }
        }
        for (String id : revealed) {
            if (this.tasks.contains(id)) {
                this.revealed.add(id);
            }
        }
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
            claimedPoints = 0;
            dailyPoints = 0;
            daily.clear();
            clearTasks();
            this.weekKey = weekKey;
            changed = true;
        }

        if (!this.dayKey.equals(dayKey)) {
            dailyPoints = 0;
            daily.clear();
            clearTasks();
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
    public RecordResult record(int amount, ActivityDef def, int max, int dailyMax, List<Integer> milestones) {
        int before = daily.getOrDefault(def.id(), 0);
        // Saturate: a bogus /activity add 2000000000 twice must not wrap the
        // counter negative and hand out awards all over again
        int after = (int) Math.min(Integer.MAX_VALUE, (long) before + amount);
        daily.put(def.id(), after);

        int earned = def.worth(after) - def.worth(before);
        // Everything past today's budget is lost outright - it must not reach
        // the weekly bar, today or later
        earned = Math.min(earned, Math.max(0, dailyMax - dailyPoints));
        if (earned <= 0) {
            return new RecordResult(0, 0);
        }
        int pointsBefore = points;
        addPoints(earned, max);
        dailyPoints += points - pointsBefore;

        return new RecordResult(points - pointsBefore, due(points, pointsBefore, milestones).size());
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

    // ====================================
    // The milestones this bar has reached and not yet been paid for, lowest
    // first - the order claim() hands them over in. Static and pure so the
    // manager can walk the same list it counts.
    // ====================================
    public static List<Integer> due(int points, int claimedPoints, List<Integer> milestones) {
        List<Integer> due = new ArrayList<>();
        for (Integer milestone : milestones) {
            if (milestone != null && milestone <= points && milestone > claimedPoints) {
                due.add(milestone);
            }
        }
        // Config order is not guaranteed ascending; claim() pays these in
        // order and burns claimedPoints up to the highest one.
        due.sort(null);
        return due;
    }

    public int claimable(List<Integer> milestones) {
        return due(points, claimedPoints, milestones).size();
    }

    public void reset(String weekKey, String dayKey) {
        points = 0;
        claimedPoints = 0;
        dailyPoints = 0;
        daily.clear();
        clearTasks();
        this.weekKey = weekKey;
        this.dayKey = dayKey;
    }

    private void clearTasks() {
        tasks.clear();
        revealed.clear();
    }

    // ====================================
    // Up to TASKS_PER_DAY distinct ids out of the loaded ones, in random
    // order. Fewer than that loaded: all of them. Pure so it can be tested
    // with a seeded Random.
    // ====================================
    public static List<String> draw(Collection<String> ids, Random random) {
        List<String> pool = new ArrayList<>(ids);
        Collections.shuffle(pool, random);
        return List.copyOf(pool.subList(0, Math.min(TASKS_PER_DAY, pool.size())));
    }

    // ====================================
    // Brings the draw in line with what is actually loaded: an id whose
    // activity a reload has removed is dropped along with its revealed flag,
    // and the draw is then topped back up to TASKS_PER_DAY with ids it does
    // not already hold. Survivors keep their order (the list compacts, so a
    // drop shifts the slots after it); a refilled task is always unrevealed.
    // True if anything changed, which is what the caller saves on.
    // ====================================
    public boolean ensureTasks(Collection<String> ids, Random random) {
        Set<String> known = new HashSet<>(ids);
        boolean changed = tasks.removeIf(id -> !known.contains(id));
        changed |= revealed.removeIf(id -> !tasks.contains(id));

        if (tasks.size() >= TASKS_PER_DAY) {
            return changed;
        }

        List<String> pool = new ArrayList<>(known);
        pool.removeAll(tasks);
        Collections.shuffle(pool, random);
        for (String id : pool) {
            if (tasks.size() >= TASKS_PER_DAY) {
                break;
            }
            tasks.add(id);
            changed = true;
        }
        return changed;
    }

    // Reveals the task in this slot. False for an empty slot or one already
    // revealed, so the caller knows whether anything changed.
    public boolean reveal(int slot) {
        if (slot < 0 || slot >= tasks.size()) {
            return false;
        }
        return revealed.add(tasks.get(slot));
    }

    // Whether this activity is one of today's tasks and has been revealed
    public boolean isRevealed(String id) {
        return revealed.contains(id);
    }

    public List<String> tasks() {
        return Collections.unmodifiableList(tasks);
    }

    public Set<String> revealed() {
        return Collections.unmodifiableSet(revealed);
    }

    public int points() {
        return points;
    }

    public int dailyPoints() {
        return dailyPoints;
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

    public int claimedPoints() {
        return claimedPoints;
    }

    public void setClaimedPoints(int claimedPoints) {
        this.claimedPoints = claimedPoints;
    }
}
