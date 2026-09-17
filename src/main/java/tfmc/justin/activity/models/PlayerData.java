package tfmc.justin.activity.models;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    // Rerolls of today's draw already used. Reset by every rollover, so the
    // budget in config is per day.
    private volatile int rerolls;

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

    // The store's constructor: same as above plus the persisted reroll count.
    // Overloaded rather than added to the others so every existing caller that
    // has no reroll count to give keeps working.
    public PlayerData(int points, int dailyPoints, String weekKey, String dayKey, int claimedPoints,
                      Map<String, Integer> daily, List<String> tasks, Collection<String> revealed, int rerolls) {
        this(points, dailyPoints, weekKey, dayKey, claimedPoints, daily, tasks, revealed);
        this.rerolls = rerolls;
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
            rerolls = 0;
            this.weekKey = weekKey;
            changed = true;
        }

        if (!this.dayKey.equals(dayKey)) {
            dailyPoints = 0;
            daily.clear();
            clearTasks();
            rerolls = 0;
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
        int budget = Math.max(0, dailyMax - dailyPoints);
        if (earned > 0 && budget <= 0) {
            return new RecordResult(0, 0, Recorded.DAILY_MAX);
        }
        earned = Math.min(earned, budget);
        if (earned <= 0) {
            // Either the activity has nothing more to give today, or the count
            // is simply part-way to its next point - which is a plain success
            return new RecordResult(0, 0,
                def.dailyCap() > 0 && def.worth(before) >= def.dailyCap()
                    ? Recorded.ACTIVITY_CAP : Recorded.RECORDED);
        }
        int pointsBefore = points;
        addPoints(earned, max);
        dailyPoints += points - pointsBefore;
        if (points == pointsBefore) {
            return new RecordResult(0, 0, Recorded.WEEKLY_MAX);
        }

        return new RecordResult(points - pointsBefore, due(points, pointsBefore, milestones).size(),
            Recorded.RECORDED);
    }

    // ====================================
    // /activity add --force only. The count goes in exactly as record() puts
    // it in, but the award ignores both the activity's dailyCap and today's
    // bar.daily-max budget: staff asking for 50 get 50.
    //
    // bar.max is NOT ignored. PlayerStore.parse clamps stored points to it on
    // load, so a bar past its maximum would be silently cut back at the next
    // restart - memory and disk disagreeing about a live player. An award the
    // bar cuts short comes back as WEEKLY_CLAMPED so the admin is told.
    //
    // dailyPoints is deliberately left alone rather than raised past
    // bar.daily-max, which parse() clamps just as hard. The consequences are
    // the right ones: a forced award does not eat the player's real budget for
    // the day, does not trip the reroll.max-points gate, and is not handed
    // back off the bar by a later reroll - a reroll refunds today's genuinely
    // earned points only, which is what it is for.
    // ====================================
    public RecordResult recordForced(int amount, ActivityDef def, int max, List<Integer> milestones) {
        int before = daily.getOrDefault(def.id(), 0);
        int after = (int) Math.min(Integer.MAX_VALUE, (long) before + amount);
        daily.put(def.id(), after);

        int earned = def.rawWorth(after) - def.rawWorth(before);
        if (earned <= 0) {
            // Part-way to the next award: the count landed, which is a success
            return new RecordResult(0, 0, Recorded.RECORDED);
        }

        int pointsBefore = points;
        addPoints(earned, max);
        int awarded = points - pointsBefore;
        if (awarded <= 0) {
            return new RecordResult(0, 0, Recorded.WEEKLY_MAX);
        }
        return new RecordResult(awarded, due(points, pointsBefore, milestones).size(),
            awarded < earned ? Recorded.WEEKLY_CLAMPED : Recorded.RECORDED);
    }

    // Widened to long before the sum: a forced add hands over an award that
    // saturates at Integer.MAX_VALUE, and 'points + p' as an int would wrap
    // negative and Math.max(0, ..) would then wipe the bar instead of filling
    // it. Every value that did not overflow before is unaffected.
    public void addPoints(int p, int max) {
        points = (int) Math.max(0, Math.min(max, (long) points + p));
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
        rerolls = 0;
        this.weekKey = weekKey;
        this.dayKey = dayKey;
    }

    // ====================================
    // Throws today away and starts it over on a fresh draw: today's unclaimed
    // points come back off the weekly bar, the counts and the draw go, and the
    // new tasks go in unrevealed. One method rather than a handful the caller
    // sequences, so it is all written in a single call on the main thread -
    // there is no lock, so an off-thread reader (PlaceholderAPI) can still
    // observe an intermediate pair of fields.
    //
    // The weekly total never falls below claimedPoints: those points have
    // already been paid for, and letting the bar drop under them would make
    // the same milestone claimable a second time. It never rises above the
    // bar either - claimedPoints can sit above a lowered bar.max.
    //
    // Whatever that floor kept on the bar is NOT refunded, so it must not hand
    // today's budget back either: only the points actually taken off the bar
    // buy back budget, and the rest carries forward as today's starting
    // dailyPoints. A player who claims a milestone and then rerolls therefore
    // keeps their points and gets no extra budget at all. The per-activity
    // count map still goes - those tasks no longer exist.
    // ====================================
    public void reroll(List<String> newTasks, int max) {
        int before = points;
        points = Math.min(max, Math.max(claimedPoints, points - dailyPoints));
        // The refund is clamped to zero before it is subtracted: if a reroll
        // ever RAISES points (claimedPoints sitting above a bar.max that was
        // lowered and then raised back), before - points is negative and
        // must not be allowed to increase dailyPoints instead.
        int refund = Math.max(0, before - points);
        dailyPoints = Math.max(0, dailyPoints - refund);
        daily.clear();
        clearTasks();
        tasks.addAll(newTasks);
        rerolls++;
    }

    private void clearTasks() {
        tasks.clear();
        revealed.clear();
    }

    // ====================================
    // Up to TASKS_PER_DAY distinct ids out of the loaded ones, in random
    // order. Fewer than that loaded: all of them. Pure so it can be tested
    // with a seeded Random.
    //
    // Every id in 'guaranteed' that is actually loaded is always in the draw
    // (that is the whole point of the flag), but the finished list is shuffled
    // as a whole, so a guaranteed task lands in a random slot rather than
    // always the first. More guaranteed ids than slots: the draw is
    // TASKS_PER_DAY of them, picked and ordered at random, and no other
    // activity can get in - config warns about that at load.
    // ====================================
    public static List<String> draw(Collection<String> ids, Collection<String> guaranteed, Random random) {
        List<String> forced = new ArrayList<>(new LinkedHashSet<>(guaranteed));
        // An id whose plugin is missing never made it into the loaded set, so
        // it simply cannot be drawn
        forced.retainAll(new HashSet<>(ids));
        Collections.shuffle(forced, random);
        if (forced.size() >= TASKS_PER_DAY) {
            return List.copyOf(forced.subList(0, TASKS_PER_DAY));
        }

        List<String> pool = new ArrayList<>(ids);
        pool.removeAll(forced);
        Collections.shuffle(pool, random);

        List<String> drawn = new ArrayList<>(forced);
        drawn.addAll(pool.subList(0, Math.min(TASKS_PER_DAY - forced.size(), pool.size())));
        Collections.shuffle(drawn, random);
        return List.copyOf(drawn);
    }

    // ====================================
    // Brings the draw in line with what is actually loaded: an id whose
    // activity a reload has removed is dropped along with its revealed flag,
    // and the draw is then topped back up to TASKS_PER_DAY with ids it does
    // not already hold. Survivors keep their order (the list compacts, so a
    // drop shifts the slots after it); a refilled task is always unrevealed.
    // A guaranteed id that is loaded but missing from the draw is put back
    // here too - a draw persisted before the flag existed, or one a reload
    // dropped it out of, must not leave the player without it for the day.
    // The rest of the draw is left alone rather than re-rolled: it is slotted
    // in at a random position, and only when the draw is already full does it
    // take a slot over, preferring an unrevealed one so as little progress as
    // possible is lost.
    // True if anything changed, which is what the caller saves on.
    // ====================================
    public boolean ensureTasks(Collection<String> ids, Collection<String> guaranteed, Random random) {
        // First use today: one whole draw, so guaranteed tasks are placed at
        // random rather than slotted into an existing order
        if (tasks.isEmpty()) {
            tasks.addAll(draw(ids, guaranteed, random));
            return !tasks.isEmpty();
        }

        Set<String> known = new HashSet<>(ids);
        boolean changed = tasks.removeIf(id -> !known.contains(id));
        changed |= revealed.removeIf(id -> !tasks.contains(id));

        changed |= restoreGuaranteed(known, guaranteed, random);

        if (tasks.size() >= TASKS_PER_DAY) {
            return changed;
        }

        // The refill is a fresh draw out of what is not already held: at most
        // TASKS_PER_DAY ids come back, which is always enough to top up
        List<String> pool = new ArrayList<>(known);
        pool.removeAll(tasks);
        for (String id : draw(pool, List.of(), random)) {
            if (tasks.size() >= TASKS_PER_DAY) {
                break;
            }
            tasks.add(id);
            changed = true;
        }
        return changed;
    }

    // ====================================
    // Puts every loaded guaranteed id the draw is missing back into it: at a
    // random free slot while there is room, otherwise over a slot chosen at
    // random among the unrevealed ones (all revealed: any slot), whose
    // revealed flag goes with it. Slots already holding a guaranteed id are
    // never taken over, so more guaranteed ids than slots settles on
    // TASKS_PER_DAY of them instead of churning.
    // ====================================
    private boolean restoreGuaranteed(Set<String> known, Collection<String> guaranteed, Random random) {
        Set<String> forced = new LinkedHashSet<>(guaranteed);
        boolean changed = false;
        for (String id : forced) {
            if (!known.contains(id) || tasks.contains(id)) {
                continue;
            }
            if (tasks.size() < TASKS_PER_DAY) {
                tasks.add(random.nextInt(tasks.size() + 1), id);
                changed = true;
                continue;
            }
            List<Integer> takeable = new ArrayList<>();
            for (int slot = 0; slot < tasks.size(); slot++) {
                if (!forced.contains(tasks.get(slot))) {
                    takeable.add(slot);
                }
            }
            if (takeable.isEmpty()) {
                break;
            }
            List<Integer> hidden = takeable.stream().filter(slot -> !revealed.contains(tasks.get(slot))).toList();
            List<Integer> from = hidden.isEmpty() ? takeable : hidden;
            int slot = from.get(random.nextInt(from.size()));
            revealed.remove(tasks.get(slot));
            tasks.set(slot, id);
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

    public int rerolls() {
        return rerolls;
    }

    // ====================================
    // Hands one consumed reroll back, floored at zero - /activity givereroll
    // <player>, for a player who burned theirs on a misclick. Nothing else
    // about the day is touched: the draw and the points stay as they are.
    // True if the counter actually moved.
    // ====================================
    public boolean refundReroll() {
        if (rerolls <= 0) {
            return false;
        }
        rerolls--;
        return true;
    }

    public int claimedPoints() {
        return claimedPoints;
    }

    public void setClaimedPoints(int claimedPoints) {
        this.claimedPoints = claimedPoints;
    }
}
