package com.enthusiasmpvp.donornpcs;

/**
 * A pre-cached skin texture (value + signature) for a username.
 * Stored in config.yml to avoid Mojang API calls on offline-mode servers.
 */
public record CachedSkin(String name, String textureValue, String textureSignature) {
}
