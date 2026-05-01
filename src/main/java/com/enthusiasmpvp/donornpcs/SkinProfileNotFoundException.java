package com.enthusiasmpvp.donornpcs;

import java.io.IOException;
import java.util.UUID;

public final class SkinProfileNotFoundException extends IOException {
    public SkinProfileNotFoundException(UUID uuid, int statusCode) {
        super("No Mojang skin profile exists for UUID '" + uuid + "' (HTTP " + statusCode + ")");
    }
}
