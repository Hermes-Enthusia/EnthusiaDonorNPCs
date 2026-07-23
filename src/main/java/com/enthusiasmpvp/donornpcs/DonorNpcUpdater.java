package com.enthusiasmpvp.donornpcs;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.skins.SkinData;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class DonorNpcUpdater {
    private final EnthusiaDonorNPCsPlugin plugin;
    private final MojangSkinService mojangSkinService = new MojangSkinService();
    private final Map<String, UpdateStatus> statuses = new LinkedHashMap<>();
    private DonorNpcsConfig config;

    public DonorNpcUpdater(EnthusiaDonorNPCsPlugin plugin, DonorNpcsConfig config) {
        this.plugin = plugin;
        setConfig(config);
    }

    public void setConfig(DonorNpcsConfig config) {
        this.config = config;
        for (LeaderboardEntry entry : config.entries()) {
            statuses.computeIfAbsent(entry.statusKey(), ignored -> new UpdateStatus(entry)).setEntry(entry);
        }
        statuses.keySet().removeIf(key -> config.entries().stream().noneMatch(entry -> entry.statusKey().equals(key)));
    }

    public Collection<UpdateStatus> statuses() {
        return statuses.values();
    }

    public void updateAll(boolean force) {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> updateAll(force));
            return;
        }

        for (LeaderboardEntry entry : config.entries()) {
            updateOne(entry, force);
        }
    }

    private void updateOne(LeaderboardEntry entry, boolean force) {
        UpdateStatus status = statuses.computeIfAbsent(entry.statusKey(), ignored -> new UpdateStatus(entry));
        String placeholderValue = "";
        String desiredSkinName = config.defaultSkinName();
        String fallbackSkinName = config.defaultSkinName();
        String displayName = "";
        UUID desiredUuid = null;

        try {
            if (!entry.uuidPlaceholder().isBlank()) {
                placeholderValue = PlaceholderAPI.setPlaceholders((org.bukkit.OfflinePlayer) null, entry.uuidPlaceholder());
                String uuidValue = PlaceholderNameUtil.cleanUuidValue(entry.uuidPlaceholder(), placeholderValue);
                Optional<UUID> parsedUuid = UuidUtil.parseUuid(uuidValue);
                if (parsedUuid.isPresent()) {
                    desiredUuid = parsedUuid.get();
                    desiredSkinName = desiredUuid.toString();
                } else if (!uuidValue.isBlank()) {
                    plugin.getLogger().warning(entry.label() + ": UUID placeholder returned an invalid UUID: '" + uuidValue + "'. Falling back to name/default skin.");
                }
            }

            if (!entry.namePlaceholder().isBlank()) {
                String namePlaceholderValue = PlaceholderAPI.setPlaceholders((org.bukkit.OfflinePlayer) null, entry.namePlaceholder());
                fallbackSkinName = PlaceholderNameUtil.cleanOrDefault(
                        entry.namePlaceholder(),
                        namePlaceholderValue,
                        config.defaultSkinName()
                );
                displayName = namePlaceholderValue.isBlank() ? fallbackSkinName : namePlaceholderValue;
                if (desiredUuid == null) {
                    placeholderValue = namePlaceholderValue;
                    desiredSkinName = fallbackSkinName;
                    displayName = fallbackSkinName;
                }
            }

            String desiredSkinKey = desiredUuid == null ? desiredSkinName : "uuid:" + desiredUuid;
            Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(entry.npcName());
            if (npc == null) {
                String message = "FancyNPCs NPC '" + entry.npcName() + "' does not exist";
                status.markFailure(placeholderValue, desiredSkinKey, message);
                plugin.getLogger().warning(entry.label() + ": " + message + ".");
                return;
            }

            if (!force
                    && config.onlyUpdateWhenNameChanges()
                    && desiredSkinKey.equalsIgnoreCase(status.lastAppliedSkinName())) {
                status.markSkipped(placeholderValue, desiredSkinKey, "No change");
                if (config.logNoChange()) {
                    plugin.getLogger().info(entry.label() + " unchanged at skin '" + desiredSkinKey + "'.");
                }
                return;
            }

            faceConfiguredDirection(entry, npc);

            if (desiredUuid != null) {
                applyUuidSkinAsync(entry, npc, status, placeholderValue, desiredUuid, desiredSkinKey, fallbackSkinName, displayName, force);
            } else {
                applyNameSkinCached(entry, npc, status, placeholderValue, desiredSkinName, desiredSkinKey, fallbackSkinName, displayName);
            }
        } catch (Exception ex) {
            String message = "Failed to update " + entry.label() + " to skin '" + desiredSkinName + "'";
            status.markFailure(placeholderValue, desiredSkinName, message + ": " + ex.getMessage());
            plugin.getLogger().log(Level.WARNING, message + ".", ex);
        }
    }

    private void applyNameSkinCached(
            LeaderboardEntry entry,
            Npc npc,
            UpdateStatus status,
            String placeholderValue,
            String desiredSkinName,
            String desiredSkinKey,
            String fallbackSkinName,
            String displayName
    ) {
        CachedSkin cached = config.getCachedSkin(desiredSkinName);
        if (cached != null) {
            applyCachedSkin(entry, npc, displayName, cached);
            status.markSuccess(placeholderValue, desiredSkinKey, "Updated from skin cache");
            if (config.logUpdates()) {
                plugin.getLogger().info(entry.label() + " skin updated to '" + desiredSkinName + "' (cached).");
            }
            return;
        }

        try {
            applyNameSkin(entry, npc, desiredSkinName, displayName);
            status.markSuccess(placeholderValue, desiredSkinKey, "Updated by name lookup");
            if (config.logUpdates()) {
                plugin.getLogger().info(entry.label() + " skin updated to '" + desiredSkinName + "'.");
            }
        } catch (Exception ex) {
            if (!desiredSkinName.equalsIgnoreCase(fallbackSkinName)) {
                CachedSkin fallbackCached = config.getCachedSkin(fallbackSkinName);
                if (fallbackCached != null) {
                    applyCachedSkin(entry, npc, displayName, fallbackCached);
                    status.markSuccess(placeholderValue, desiredSkinKey, "Name '" + desiredSkinName + "' lookup failed; used cached fallback '" + fallbackSkinName + "'");
                    plugin.getLogger().warning(entry.label() + ": name lookup for '" + desiredSkinName + "' failed; used cached fallback '" + fallbackSkinName + "'.");
                    return;
                }
            }
            plugin.getLogger().warning(entry.label() + ": name lookup for '" + desiredSkinName + "' and fallback '" + fallbackSkinName + "' both failed (no cached skins). Add skins to config.yml or verify Mojang connectivity.");
            status.markFailure(placeholderValue, desiredSkinKey, "Skin lookup failed (no cache, no Mojang)");
            tryUpdateDisplayNameOnly(entry, npc, status, placeholderValue, desiredSkinKey, displayName);
        }
    }

    private void applyCachedSkin(LeaderboardEntry entry, Npc npc, String displayName, CachedSkin cached) {
        NpcData data = npc.getData();
        SkinData skinData = new SkinData(cached.name().toLowerCase(), SkinData.SkinVariant.AUTO, cached.textureValue(), cached.textureSignature());
        data.setSkinData(skinData);
        updateDisplayName(data, displayName);
        respawnNpc(entry, npc);
    }

    private void applyUuidSkinAsync(
            LeaderboardEntry entry,
            Npc npc,
            UpdateStatus status,
            String placeholderValue,
            UUID uuid,
            String desiredSkinKey,
            String fallbackSkinName,
            String displayName,
            boolean force
    ) {
        status.markSkipped(placeholderValue, desiredSkinKey, "Fetching UUID skin texture");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SkinTexture texture = mojangSkinService.fetchTexture(uuid, force);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        applyUuidSkin(entry, npc, uuid, displayName, texture);
                        status.markSuccess(placeholderValue, desiredSkinKey, "Updated by UUID");
                        if (config.logUpdates()) {
                            plugin.getLogger().info(entry.label() + " skin updated from UUID '" + uuid + "'.");
                        }
                    } catch (Exception ex) {
                        String message = "Failed to apply UUID skin for " + entry.label() + " using UUID '" + uuid + "'";
                        status.markFailure(placeholderValue, desiredSkinKey, message + ": " + ex.getMessage());
                        plugin.getLogger().log(Level.WARNING, message + ".", ex);
                    }
                });
            } catch (SkinProfileNotFoundException ex) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        retryWithResolvedUuid(entry, npc, status, placeholderValue, desiredSkinKey, uuid, fallbackSkinName, displayName, force, ex.getMessage()));
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        applyFallbackSkinCached(entry, npc, status, placeholderValue, desiredSkinKey, uuid, fallbackSkinName, displayName, ex.getMessage()));
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    String message = "Failed to fetch UUID skin for " + entry.label() + " using UUID '" + uuid + "'";
                    status.markFailure(placeholderValue, desiredSkinKey, message + ": " + ex.getMessage());
                    plugin.getLogger().log(Level.WARNING, message + ".", ex);
                });
            }
        });
    }

    private void retryWithResolvedUuid(
            LeaderboardEntry entry,
            Npc npc,
            UpdateStatus status,
            String placeholderValue,
            String desiredSkinKey,
            UUID donorUuid,
            String fallbackSkinName,
            String displayName,
            boolean force,
            String reason
    ) {
        if (fallbackSkinName.equals(config.defaultSkinName())) {
            // No player name available to resolve
            applyFallbackSkinCached(entry, npc, status, placeholderValue, desiredSkinKey, donorUuid, fallbackSkinName, displayName, reason);
            return;
        }
        // Try resolving the real Mojang UUID from the player name (Geyser UUIDs are different)
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var resolved = mojangSkinService.resolveUuid(fallbackSkinName);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (resolved.isPresent() && !resolved.get().equals(donorUuid)) {
                        UUID realUuid = resolved.get();
                        plugin.getLogger().info(entry.label() + ": resolved real Mojang UUID " + realUuid + " from name '" + fallbackSkinName + "' (donor system had " + donorUuid + ")");
                        // Retry the full UUID flow with the real UUID
                        applyUuidSkinAsync(entry, npc, status, placeholderValue, realUuid, desiredSkinKey, fallbackSkinName, displayName, force);
                    } else {
                        applyFallbackSkinCached(entry, npc, status, placeholderValue, desiredSkinKey, donorUuid, fallbackSkinName, displayName, reason);
                    }
                });
            } catch (Exception retryEx) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        applyFallbackSkinCached(entry, npc, status, placeholderValue, desiredSkinKey, donorUuid, fallbackSkinName, displayName, reason));
            }
        });
    }

    private void applyFallbackSkinCached(
            LeaderboardEntry entry,
            Npc npc,
            UpdateStatus status,
            String placeholderValue,
            String desiredSkinKey,
            UUID uuid,
            String fallbackSkinName,
            String displayName,
            String reason
    ) {
        try {
            CachedSkin cached = config.getCachedSkin(fallbackSkinName);
            if (cached != null) {
                applyCachedSkin(entry, npc, displayName, cached);
                status.markSuccess(placeholderValue, desiredSkinKey, "UUID skin unavailable; used cached fallback skin '" + fallbackSkinName + "'");
                plugin.getLogger().warning(entry.label()
                        + ": could not use UUID skin '" + uuid + "' (" + reason
                        + "), so cached fallback skin '" + fallbackSkinName + "' was applied.");
            } else {
                try {
                    applyNameSkin(entry, npc, fallbackSkinName, displayName);
                    status.markSuccess(placeholderValue, desiredSkinKey, "UUID skin unavailable; used fallback skin '" + fallbackSkinName + "'");
                    plugin.getLogger().warning(entry.label()
                            + ": could not use UUID skin '" + uuid + "' (" + reason
                            + "), so fallback skin '" + fallbackSkinName + "' was applied. "
                            + "If you want exact UUID skins, make sure the placeholder returns an online-mode Mojang UUID.");
                } catch (Exception fallbackEx) {
                    plugin.getLogger().warning(entry.label() + ": UUID skin and fallback lookup both failed. Skin unchanged.");
                    status.markFailure(placeholderValue, desiredSkinKey, "Skin lookup failed (no cache, no Mojang)");
                    tryUpdateDisplayNameOnly(entry, npc, status, placeholderValue, desiredSkinKey, displayName);
                }
            }
        } catch (Exception ex) {
            String message = "Failed to apply fallback skin for " + entry.label() + " after UUID skin fetch failed";
            status.markFailure(placeholderValue, desiredSkinKey, message + ": " + ex.getMessage());
            plugin.getLogger().log(Level.WARNING, message + ".", ex);
        }
    }

    private void applyUuidSkin(LeaderboardEntry entry, Npc npc, UUID uuid, String displayName, SkinTexture texture) {
        NpcData data = npc.getData();
        SkinData skinData = new SkinData(uuid.toString(), SkinData.SkinVariant.AUTO, texture.value(), texture.signature());
        data.setSkinData(skinData);
        updateDisplayName(data, displayName);
        respawnNpc(entry, npc);
    }

    private void applyNameSkin(LeaderboardEntry entry, Npc npc, String skinName, String displayName) {
        NpcData data = npc.getData();
        data.setSkin(skinName, SkinData.SkinVariant.AUTO);
        updateDisplayName(data, displayName);
        respawnNpc(entry, npc);
    }

    private void updateDisplayName(NpcData data, String displayName) {
        if (displayName == null || displayName.isBlank()) return;
        data.setDisplayName(displayName);
    }

    private void respawnNpc(LeaderboardEntry entry, Npc npc) {
        if (config.refreshNpcAfterSkinChange()) {
            refreshNpc(entry, npc);
        } else {
            npc.spawnForAll();
        }
    }

    private void refreshNpc(LeaderboardEntry entry, Npc npc) {
        Location loc = npc.getData().getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        setRotation(loc, entry.facingDirection());
        npc.removeForAll();
        npc.create();
        npc.spawnForAll();
    }

    private void faceConfiguredDirection(LeaderboardEntry entry, Npc npc) {
        Location location = npc.getData().getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        FacingDirection facingDirection = entry.facingDirection();
        setRotation(location, facingDirection);
        NpcData data = npc.getData();
        data.setLocation(location);
    }

    private void tryUpdateDisplayNameOnly(LeaderboardEntry entry, Npc npc, UpdateStatus status, String placeholderValue, String desiredSkinKey, String displayName) {
        try {
            NpcData data = npc.getData();
            updateDisplayName(data, displayName);
            npc.spawnForAll();
            if (config.logUpdates()) {
                plugin.getLogger().info(entry.label() + " display name updated to '" + displayName + "' (skin unchanged).");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning(entry.label() + ": failed to update display name: " + ex.getMessage());
        }
    }

    private void setRotation(Location location, FacingDirection facingDirection) {
        location.setYaw(facingDirection.yaw());
        location.setPitch(0.0F);
    }
}
