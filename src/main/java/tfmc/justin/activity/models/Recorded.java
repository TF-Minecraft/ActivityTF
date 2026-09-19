package tfmc.justin.activity.models;

// ====================================
// What one record attempt did. Listeners and the API ignore it; /activity add
// turns it into the line the admin reads, which is why "the count went in but
// no point reached the bar" names the cap that swallowed it rather than
// reporting a plain success.
// ====================================
public enum Recorded {

    // No such activity, or a non-positive amount
    UNKNOWN,

    // Gated, and not one of the player's revealed tasks today (which covers a
    // player with no draw at all)
    NOT_A_TASK,

    // The count went in. A point may or may not have landed with it - an
    // activity part-way to its next point is still a plain success.
    RECORDED,

    // This activity's own daily cap was already reached
    ACTIVITY_CAP,

    // Today's point budget (bar.daily-max) was already spent
    DAILY_MAX,

    // Not vote, and every other activity together has already added its
    // share of the day (bar.daily-max less bar.vote-share); voting still can
    VOTE_SHARE,

    // The weekly bar (bar.max) was already full
    WEEKLY_MAX,

    // Points landed, but fewer than were earned: the weekly bar (bar.max)
    // filled up part-way through. Only a forced add can produce this - the
    // gated path spends its whole award or none of it.
    WEEKLY_CLAMPED
}
