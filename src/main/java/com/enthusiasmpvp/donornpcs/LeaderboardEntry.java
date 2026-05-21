package com.enthusiasmpvp.donornpcs;

public record LeaderboardEntry(
        String leaderboardKey,
        String leaderboardDisplayName,
        int position,
        String npcName,
        String namePlaceholder,
        String uuidPlaceholder,
        FacingDirection facingDirection
) {
    public String statusKey() {
        return leaderboardKey + ":" + position + ":" + npcName;
    }

    public String label() {
        return leaderboardDisplayName + " #" + position + " (NPC " + npcName + ")";
    }
}
