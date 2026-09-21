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

public class PlayerData {

    public static final int TASKS_PER_DAY = 7;

    private final Map<String, Integer> daily = new ConcurrentHashMap<>();

    private final List<String> tasks = new CopyOnWriteArrayList<>();
    private final Set<String> revealed = ConcurrentHashMap.newKeySet();

    private volatile int points;
    private volatile int dailyPoints;
    private volatile int votePoints;
    private volatile String weekKey;
    private volatile String dayKey;
    private volatile int claimedPoints;

    private volatile int rerolls;

    private volatile boolean dailyRewardClaimed;

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

    public PlayerData(int points, int dailyPoints, String weekKey, String dayKey, int claimedPoints,
                      Map<String, Integer> daily, List<String> tasks, Collection<String> revealed, int rerolls) {
        this(points, dailyPoints, weekKey, dayKey, claimedPoints, daily, tasks, revealed);
        this.rerolls = rerolls;
    }

    public boolean roll(String weekKey, String dayKey) {
        boolean changed = false;

        if (!this.weekKey.equals(weekKey)) {
            points = 0;
            claimedPoints = 0;
            dailyPoints = 0;
            votePoints = 0;
            daily.clear();
            clearTasks();
            rerolls = 0;
            dailyRewardClaimed = false;
            this.weekKey = weekKey;
            changed = true;
        }

        if (!this.dayKey.equals(dayKey)) {
            dailyPoints = 0;
            votePoints = 0;
            daily.clear();
            clearTasks();
            rerolls = 0;
            dailyRewardClaimed = false;
            this.dayKey = dayKey;
            changed = true;
        }

        return changed;
    }

    public RecordResult record(int amount, ActivityDef def, int max, int dailyMax, List<Integer> milestones) {
        return record(amount, def, max, dailyMax, dailyMax, milestones);
    }

    public RecordResult record(int amount, ActivityDef def, int max, int dailyMax, int nonVoteMax,
                               List<Integer> milestones) {
        int before = daily.getOrDefault(def.id(), 0);
        int after = (int) Math.min(Integer.MAX_VALUE, (long) before + amount);
        daily.put(def.id(), after);

        int earned = def.worth(after) - def.worth(before);
        int dailyBudget = Math.max(0, dailyMax - dailyPoints);
        boolean isVote = def.id().equals("vote");
        int budget = isVote ? dailyBudget
            : Math.min(dailyBudget, Math.max(0, nonVoteMax - (dailyPoints - votePoints)));
        if (earned > 0 && budget <= 0) {
            return new RecordResult(0, 0, dailyBudget <= 0 ? Recorded.DAILY_MAX : Recorded.VOTE_SHARE);
        }
        earned = Math.min(earned, budget);
        if (earned <= 0) {
            return new RecordResult(0, 0,
                def.dailyCap() > 0 && def.worth(before) >= def.capPoints()
                    ? Recorded.ACTIVITY_CAP : Recorded.RECORDED);
        }
        int pointsBefore = points;
        addPoints(earned, max);
        dailyPoints += points - pointsBefore;
        if (isVote) {
            votePoints += points - pointsBefore;
        }
        if (points == pointsBefore) {
            return new RecordResult(0, 0, Recorded.WEEKLY_MAX);
        }

        return new RecordResult(points - pointsBefore, due(points, pointsBefore, milestones).size(),
            Recorded.RECORDED);
    }

    public RecordResult recordForced(int amount, ActivityDef def, int max, List<Integer> milestones) {
        int before = daily.getOrDefault(def.id(), 0);
        int after = (int) Math.min(Integer.MAX_VALUE, (long) before + amount);
        daily.put(def.id(), after);

        int earned = def.rawWorth(after) - def.rawWorth(before);
        if (earned <= 0) {
            return new RecordResult(0, 0, Recorded.RECORDED);
        }
        return creditForced(earned, max, milestones);
    }

    public RecordResult creditForced(int earned, int max, List<Integer> milestones) {
        int pointsBefore = points;
        addPoints(earned, max);
        int awarded = points - pointsBefore;
        if (awarded <= 0) {
            return new RecordResult(0, 0, Recorded.WEEKLY_MAX);
        }
        return new RecordResult(awarded, due(points, pointsBefore, milestones).size(),
            awarded < earned ? Recorded.WEEKLY_CLAMPED : Recorded.RECORDED);
    }

    public void addPoints(int p, int max) {
        points = (int) Math.max(0, Math.min(max, (long) points + p));
    }

    public boolean clamp(int max) {
        if (points <= max) {
            return false;
        }
        points = max;
        return true;
    }

    public static List<Integer> due(int points, int claimedPoints, List<Integer> milestones) {
        List<Integer> due = new ArrayList<>();
        for (Integer milestone : milestones) {
            if (milestone != null && milestone <= points && milestone > claimedPoints) {
                due.add(milestone);
            }
        }
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
        votePoints = 0;
        daily.clear();
        clearTasks();
        rerolls = 0;
        if (!dayKey.equals(this.dayKey) || !weekKey.equals(this.weekKey)) {
            dailyRewardClaimed = false;
        }
        this.weekKey = weekKey;
        this.dayKey = dayKey;
    }

    public void reroll(List<String> newTasks, int max) {
        int before = points;
        points = Math.min(max, Math.max(claimedPoints, points - dailyPoints));
        int refund = Math.max(0, before - points);
        int dailyBefore = dailyPoints;
        dailyPoints = Math.max(0, dailyPoints - refund);
        votePoints = dailyBefore == 0 ? 0 : (int) ((long) votePoints * dailyPoints / dailyBefore);
        daily.clear();
        clearTasks();
        tasks.addAll(newTasks);
        rerolls++;
    }

    private void clearTasks() {
        tasks.clear();
        revealed.clear();
    }

    public static List<String> draw(Collection<String> ids, Collection<String> guaranteed, Random random) {
        List<String> forced = new ArrayList<>(new LinkedHashSet<>(guaranteed));
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

    public boolean ensureTasks(Collection<String> ids, Collection<String> guaranteed, Random random) {
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

    public boolean reveal(int slot) {
        if (slot < 0 || slot >= tasks.size()) {
            return false;
        }
        return revealed.add(tasks.get(slot));
    }

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

    public int votePoints() {
        return votePoints;
    }

    public void setVotePoints(int votePoints) {
        this.votePoints = Math.max(0, Math.min(votePoints, dailyPoints));
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

    public boolean refundReroll() {
        if (rerolls <= 0) {
            return false;
        }
        rerolls--;
        return true;
    }

    public boolean dailyRewardClaimed() {
        return dailyRewardClaimed;
    }

    public void setDailyRewardClaimed(boolean dailyRewardClaimed) {
        this.dailyRewardClaimed = dailyRewardClaimed;
    }

    public boolean allRevealed() {
        return !tasks.isEmpty() && revealed.containsAll(tasks);
    }

    public int claimedPoints() {
        return claimedPoints;
    }

    public void setClaimedPoints(int claimedPoints) {
        this.claimedPoints = claimedPoints;
    }
}
