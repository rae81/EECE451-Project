package com.networkanalyzer.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object for {@link SpeedTestEntity}.
 * Provides queries against the "speed_tests" table.
 */
@Dao
public interface SpeedTestDao {

    @Insert
    long insert(SpeedTestEntity entity);

    @Query("SELECT * FROM speed_tests ORDER BY timestamp DESC")
    List<SpeedTestEntity> getAll();

    @Query("SELECT * FROM speed_tests ORDER BY timestamp DESC LIMIT :limit")
    List<SpeedTestEntity> getRecent(int limit);

    @Query("SELECT * FROM speed_tests WHERE synced = 0")
    List<SpeedTestEntity> getUnsynced();

    @Query("UPDATE speed_tests SET synced = 1 WHERE id = :id")
    void markAsSynced(int id);

    @Query("DELETE FROM speed_tests")
    void deleteAll();
}
