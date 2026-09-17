package tfmc.justin.activity.models;

// 'outcome' is what the points (or the lack of them) mean - see Recorded
public record RecordResult(int pointsAwarded, int milestonesReached, Recorded outcome) {
}
