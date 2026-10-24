package com.phoenix.fish.api;

/**
 * Outcome of a bulk crafting attempt via {@link PhoenixFishAPI#craftBulk}.
 *
 * <p>Carries the number of items actually crafted (which may be less than
 * requested if the inventory ran out of materials mid-way) plus the
 * standard result code.</p>
 *
 * @since 1.3.3
 */
public record CraftBulkResult(
        CraftResult result,
        int craftedCount,
        int xpAwarded,
        int levelsGained) {

    /**
     * Convenience factory for the common failure cases (0 crafted).
     */
    public static CraftBulkResult failure(CraftResult result) {
        return new CraftBulkResult(result, 0, 0, 0);
    }

    /**
     * Convenience factory for the no-recipe / no-data cases.
     */
    public static CraftBulkResult empty(CraftResult result) {
        return new CraftBulkResult(result, 0, 0, 0);
    }
}
