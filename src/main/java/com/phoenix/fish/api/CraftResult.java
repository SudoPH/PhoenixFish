package com.phoenix.fish.api;

/**
 * Outcome of a crafting attempt via {@link PhoenixFishAPI#craft}.
 */
public enum CraftResult {
    /** Recipe crafted successfully — items consumed, result given, XP added. */
    SUCCESS,
    /** Player does not meet the crafting-level requirement. */
    LOW_LEVEL,
    /** Player's inventory lacks one or more required ingredients. */
    MISSING_MATERIALS,
    /** Recipe id is unknown / unloaded. */
    UNKNOWN_RECIPE,
    /** Player data unavailable (XP system disabled or not loaded yet). */
    NO_DATA,
    /** Internal failure while consuming items or granting result. */
    INTERNAL_ERROR
}
