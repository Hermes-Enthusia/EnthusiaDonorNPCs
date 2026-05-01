package com.enthusiasmpvp.donornpcs;

import java.util.Locale;

public enum FacingDirection {
    NORTH(180.0F, 0.0, 0.0, -10.0),
    EAST(-90.0F, 10.0, 0.0, 0.0),
    SOUTH(0.0F, 0.0, 0.0, 10.0),
    WEST(90.0F, -10.0, 0.0, 0.0);

    private final float yaw;
    private final double targetXOffset;
    private final double targetYOffset;
    private final double targetZOffset;

    FacingDirection(float yaw, double targetXOffset, double targetYOffset, double targetZOffset) {
        this.yaw = yaw;
        this.targetXOffset = targetXOffset;
        this.targetYOffset = targetYOffset;
        this.targetZOffset = targetZOffset;
    }

    public static FacingDirection fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return EAST;
        }

        try {
            return FacingDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return EAST;
        }
    }

    public float yaw() {
        return yaw;
    }

    public double targetXOffset() {
        return targetXOffset;
    }

    public double targetYOffset() {
        return targetYOffset;
    }

    public double targetZOffset() {
        return targetZOffset;
    }
}
