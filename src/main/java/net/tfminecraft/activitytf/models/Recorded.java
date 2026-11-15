package net.tfminecraft.activitytf.models;

public enum Recorded {

    UNKNOWN,

    NOT_A_TASK,

    /** Held for a task that counts once revealed (a vote cast before Voting was clicked today). */
    HELD,

    RECORDED,

    ACTIVITY_CAP,

    DAILY_MAX,

    VOTE_SHARE,

    WEEKLY_MAX,

    WEEKLY_CLAMPED
}
