package net.tfminecraft.activitytf.models;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** One account's referral state for the current season. */
public class ReferralData {

    /** Most connection hashes kept per account; the oldest is dropped first. */
    public static final int MAX_IPS = 16;

    private String name;
    private int minutes;
    private long firstSeen;
    private UUID invitedBy;
    private long linkedAt;
    private UUID rareCountedFor;
    private long lastSeen;
    private final Set<UUID> ipPartners = new HashSet<>();
    private final Set<Integer> claimed = new TreeSet<>();
    private final Set<Integer> inviterClaimed = new TreeSet<>();
    private final LinkedHashSet<String> ips = new LinkedHashSet<>();
    private final Set<Integer> rareClaimed = new TreeSet<>();

    public ReferralData(String name, long firstSeen) {
        this.name = name;
        this.firstSeen = firstSeen;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int minutes() {
        return minutes;
    }

    public void setMinutes(int minutes) {
        this.minutes = Math.max(0, minutes);
    }

    public void addMinute() {
        if (minutes < Integer.MAX_VALUE) {
            minutes++;
        }
    }

    public long firstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(long firstSeen) {
        this.firstSeen = firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    /** Accounts staff let this one link and claim with despite a shared connection (one household). */
    public Set<UUID> ipPartners() {
        return ipPartners;
    }

    public UUID invitedBy() {
        return invitedBy;
    }

    public long linkedAt() {
        return linkedAt;
    }

    public void link(UUID inviter, long at) {
        this.invitedBy = inviter;
        this.linkedAt = inviter == null ? 0 : at;
    }

    /**
     * The inviter whose rare-track claim this account already counted towards; after that it never
     * counts for anyone else, even if staff link it to another inviter.
     */
    public UUID rareCountedFor() {
        return rareCountedFor;
    }

    public void setRareCountedFor(UUID inviter) {
        this.rareCountedFor = inviter;
    }

    /** Milestone hours this account has claimed as the invited player. */
    public Set<Integer> claimed() {
        return claimed;
    }

    /** Milestone hours the inviter has claimed for this account. */
    public Set<Integer> inviterClaimed() {
        return inviterClaimed;
    }

    public Set<String> ips() {
        return ips;
    }

    /** Remembers a connection hash as the most recent one. */
    public boolean addIp(String hash) {
        boolean added = !ips.remove(hash);
        ips.add(hash);
        while (ips.size() > MAX_IPS) {
            ips.remove(ips.iterator().next());
        }
        return added;
    }

    public boolean sharesIp(ReferralData other) {
        for (String ip : other.ips) {
            if (ips.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    /** Rare-track steps this account has claimed as an inviter, by their player count. */
    public Set<Integer> rareClaimed() {
        return rareClaimed;
    }

    public List<String> ipList() {
        return new ArrayList<>(ips);
    }
}
