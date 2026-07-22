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
                if (desiredUuid == null) {
                    placeholderValue = namePlaceholderValue;
                    desiredSkinName = fallbackSkinName;
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
                applyUuidSkinAsync(entry, npc, status, placeholderValue, desiredUuid, desiredSkinKey, fallbackSkinName, force);
            } else {
                applyNameSkin(entry, npc, desiredSkinName);
                status.markSuccess(placeholderValue, desiredSkinKey, "Updated by name/default skin");
                if (config.logUpdates()) {
                    plugin.getLogger().info(entry.label() + " skin updated to '" + desiredSkinName + "'.");
                }
            }
        } catch (Exception ex) {
            String message = "Failed to update " + entry.label() + " to skin '" + desiredSkinName + "'";
            status.markFailure(placeholderValue, desiredSkinName, message + ": " + ex.getMessage());
            plugin.getLogger().log(Level.WARNING, message + ".", ex);
        }
    }

    private void applyUuidSkinAsync(
            LeaderboardEntry entry,
            Npc npc,
            UpdateStatus status,
            String placeholderValue,
            UUID uuid,
            String desiredSkinKey,
            String fallbackSkinName,
            boolean force
    ) {
        status.markSkipped(placeholderValue, desiredSkinKey, "Fetching UUID skin texture");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SkinTexture texture = mojangSkinService.fetchTexture(uuid, force);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        applyUuidSkin(entry, npc, uuid, texture);
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
                        applyFallbackSkin(entry, npc, status, placeholderValue, desiredSkinKey, uuid, fallbackSkinName, ex.getMessage()));
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        applyFallbackSkin(entry, npc, status, placeholderValue, desiredSkinKey, uuid, fallbackSkinName, ex.getMessage()));
            } catch (Exception ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    String message = "Failed to fetch UUID skin for " + entry.label() + " using UUID '" + uuid + "'";
                    status.markFailure(placeholderValue, desiredSkinKey, message + ": " + ex.getMessage());
                    plugin.getLogger().log(Level.WARNING, message + ".", ex);
                });
            }
        });
    }

    private void applyFallbackSkin(
            LeaderboardEntry entry,
            Npc npc,
            UpdateStatus status,
            String placeholderValue,
            String desiredSkinKey,
            UUID uuid,
            String fallbackSkinName,
            String reason
    ) {
        try {
            applyNameSkin(entry, npc, fallbackSkinName);
            status.markSuccess(placeholderValue, desiredSkinKey, "UUID skin unavailable; used fallback skin '" + fallbackSkinName + "'");
            plugin.getLogger().warning(entry.label()
                    + ": could not use UUID skin '" + uuid + "' (" + reason
                    + "), so fallback skin '" + fallbackSkinName + "' was applied. "
                    + "If you want exact UUID skins, make sure the placeholder returns an online-mode Mojang UUID.");
        } catch (Exception ex) {
            String message = "Failed to apply fallback skin for " + entry.label() + " after UUID skin fetch failed";
            status.markFailure(placeholderValue, desiredSkinKey, message + ": " + ex.getMessage());
            plugin.getLogger().log(Level.WARNING, message + ".", ex);
        }
    }

    private void applyUuidSkin(LeaderboardEntry entry, Npc npc, UUID uuid, SkinTexture texture) {
        NpcData data = npc.getData();
        SkinData skinData = new SkinData(uuid.toString(), SkinData.SkinVariant.AUTO, texture.value(), texture.signature());
        data.setSkinData(skinData);
        respawnNpc(entry, npc);
    }

    private void applyNameSkin(LeaderboardEntry entry, Npc npc, String skinName) {
        NpcData data = npc.getData();
        data.setSkin(skinName, SkinData.SkinVariant.AUTO);
        respawnNpc(entry, npc);
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

    private void setRotation(Location location, FacingDirection facingDirection) {
        location.setYaw(facingDirection.yaw());
        location.setPitch(0.0F);
    }
}
