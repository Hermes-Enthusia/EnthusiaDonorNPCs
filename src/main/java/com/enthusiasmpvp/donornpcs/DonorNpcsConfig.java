package com.enthusiasmpvp.donornpcs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class DonorNpcsConfig {
    private final int updateIntervalMinutes;
    private final String defaultSkinName;
    private final boolean onlyUpdateWhenNameChanges;
    private final boolean refreshNpcAfterSkinChange;
    private final boolean logUpdates;
    private final boolean logNoChange;
    private final List<LeaderboardEntry> entries;

    private DonorNpcsConfig(
            int updateIntervalMinutes,
            String defaultSkinName,
            boolean onlyUpdateWhenNameChanges,
            boolean refreshNpcAfterSkinChange,
            boolean logUpdates,
            boolean logNoChange,
            List<LeaderboardEntry> entries
    ) {
        this.updateIntervalMinutes = updateIntervalMinutes;
        this.defaultSkinName = defaultSkinName;
        this.onlyUpdateWhenNameChanges = onlyUpdateWhenNameChanges;
        this.refreshNpcAfterSkinChange = refreshNpcAfterSkinChange;
        this.logUpdates = logUpdates;
        this.logNoChange = logNoChange;
        this.entries = List.copyOf(entries);
    }

    public static DonorNpcsConfig from(FileConfiguration config) {
        int updateIntervalMinutes = Math.max(1, config.getInt("update-interval-minutes", 10));
        String defaultSkinName = nonBlank(config.getString("default-skin-name"), "Steve");

        boolean onlyUpdateWhenNameChanges = config.getBoolean("settings.only-update-when-name-changes", true);
        boolean refreshNpcAfterSkinChange = config.getBoolean("settings.refresh-npc-after-skin-change", true);
        boolean logUpdates = config.getBoolean("settings.log-updates", true);
        boolean logNoChange = config.getBoolean("settings.log-no-change", false);

        List<LeaderboardEntry> entries = new ArrayList<>();
        ConfigurationSection leaderboards = config.getConfigurationSection("leaderboards");
        if (leaderboards != null) {
            for (String leaderboardKey : leaderboards.getKeys(false)) {
                ConfigurationSection leaderboard = leaderboards.getConfigurationSection(leaderboardKey);
                if (leaderboard == null) {
                    continue;
                }

                String displayName = nonBlank(leaderboard.getString("display-name"), prettifyKey(leaderboardKey));
                ConfigurationSection positions = leaderboard.getConfigurationSection("positions");
                if (positions == null) {
                    continue;
                }

                for (String positionKey : positions.getKeys(false)) {
                    ConfigurationSection position = positions.getConfigurationSection(positionKey);
                    if (position == null) {
                        continue;
                    }

                    int parsedPosition = parsePosition(positionKey);
                    String npcName = position.getString("npc-name", "");
                    String namePlaceholder = position.getString("name-placeholder", "");
                    String uuidPlaceholder = position.getString("uuid-placeholder", "");
                    FacingDirection facingDirection = FacingDirection.fromConfig(position.getString("facing", "east"));
                    if (parsedPosition < 1 || npcName.isBlank() && (namePlaceholder.isBlank() && uuidPlaceholder.isBlank())) {
                        continue;
                    }

                    entries.add(new LeaderboardEntry(
                            leaderboardKey,
                            displayName,
                            parsedPosition,
                            npcName,
                            namePlaceholder.trim(),
                            uuidPlaceholder.trim(),
                            facingDirection
                    ));
                }
            }
        }

        entries.sort(Comparator
                .comparing(LeaderboardEntry::leaderboardKey)
                .thenComparingInt(LeaderboardEntry::position));

        return new DonorNpcsConfig(
                updateIntervalMinutes,
                defaultSkinName,
                onlyUpdateWhenNameChanges,
                refreshNpcAfterSkinChange,
                logUpdates,
                logNoChange,
                entries
        );
    }

    public int updateIntervalMinutes() {
        return updateIntervalMinutes;
    }

    public String defaultSkinName() {
        return defaultSkinName;
    }

    public boolean onlyUpdateWhenNameChanges() {
        return onlyUpdateWhenNameChanges;
    }

    public boolean refreshNpcAfterSkinChange() {
        return refreshNpcAfterSkinChange;
    }

    public boolean logUpdates() {
        return logUpdates;
    }

    public boolean logNoChange() {
        return logNoChange;
    }

    public List<LeaderboardEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    private static int parsePosition(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String prettifyKey(String key) {
        String[] parts = key.replace('_', '-').split("-");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? key : builder.toString();
    }
}
