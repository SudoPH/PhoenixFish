package com.phoenix.fish.manager.database;

import com.phoenix.fish.data.FishingData;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for database operations related to player fishing data.
 * <p>
 * Implementations of this interface MUST handle data asynchronously using
 * {@link CompletableFuture} to prevent blocking the main server thread.
 * All database calls should ideally use connection pooling (e.g., HikariCP).
 * </p>
 */
public interface IDatabase {

    /**
     * Initializes the database connection pool and creates necessary tables if they
     * don't exist. Should be called synchronously during plugin enable.
     *
     * @throws RuntimeException if the database connection cannot be established.
     */
    void init();

    /**
     * Closes the database connection pool safely. Should be called during plugin
     * disable.
     */
    void close();

    /**
     * Asynchronously loads the fishing data for a specific player.
     *
     * @param uuid The unique identifier of the player. Must not be null.
     * @return A {@link CompletableFuture} containing the player's
     *         {@link FishingData},
     *         or a new empty instance if no data exists. Exceptionally completed
     *         future
     *         if a database error occurs.
     */
    CompletableFuture<FishingData> loadData(UUID uuid);

    /**
     * Asynchronously saves or updates the fishing data for a specific player.
     * Using the object itself prevents interface signature changes when new data
     * fields are added.
     *
     * @param uuid The unique identifier of the player.
     * @param data The FishingData object to save.
     * @return A {@link CompletableFuture} that completes when the save operation is
     *         done.
     */
    CompletableFuture<Void> saveData(UUID uuid, FishingData data);

    /**
     * Asynchronously saves data for multiple players in a single batch operation.
     * This is crucial for performance during auto-saves or server shutdowns.
     *
     * @param dataMap A collection of UUID and FishingData pairs to save.
     * @return A {@link CompletableFuture} that completes when the batch save is
     *         done.
     */
    CompletableFuture<Void> batchSave(Collection<FishingData> dataCollection);
}