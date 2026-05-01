package com.enthusiasmpvp.donornpcs;

public record LeaderboardEntry(
        String leaderboardKey,
        String leaderboardDisplayName,
        int position,
        int npcId,
        String namePlaceholder,
        String uuidPlaceholder,
        FacingDirection facingDirection
) {
    public String statusKey() {
        return leaderboardKey + ":" + position + ":" + npcId;
    }

    public String label() {
        return leaderboardDisplayName + " #" + position + " (NPC " + npcId + ")";
    }
}
