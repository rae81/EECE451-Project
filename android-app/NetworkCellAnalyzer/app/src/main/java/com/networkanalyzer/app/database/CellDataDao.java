package com.networkanalyzer.app.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object for {@link CellDataEntity}.
 * Provides all CRUD and analytical queries against the "cell_data" table.
 */
@Dao
public interface CellDataDao {

    // -------------------------------------------------------------------------
    // Insert / Delete
    // -------------------------------------------------------------------------

    @Insert
    long insert(CellDataEntity entity);

    @Insert
    List<Long> insertAll(List<CellDataEntity> entities);

    @Delete
    void delete(CellDataEntity entity);

    // -------------------------------------------------------------------------
    // Basic queries
    // -------------------------------------------------------------------------

    @Query("SELECT * FROM cell_data ORDER BY timestamp DESC")
    List<CellDataEntity> getAll();

    @Query("SELECT * FROM cell_data WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    List<CellDataEntity> getByDateRange(long from, long to);

    @Query("SELECT * FROM cell_data WHERE network_type = :type")
    List<CellDataEntity> getByNetworkType(String type);

    @Query("SELECT * FROM cell_data WHERE operator = :operator")
    List<CellDataEntity> getByOperator(String operator);

    // -------------------------------------------------------------------------
    // Sync-related queries
    // -------------------------------------------------------------------------

    @Query("SELECT * FROM cell_data WHERE synced = 0")
    List<CellDataEntity> getUnsynced();

    @Query("UPDATE cell_data SET synced = 1 WHERE id = :id")
    void markAsSynced(int id);

    @Query("UPDATE cell_data SET synced = 1 WHERE id IN (:ids)")
    void markAllAsSynced(List<Integer> ids);

    // -------------------------------------------------------------------------
    // Aggregation / utility queries
    // -------------------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM cell_data")
    int getCount();

    @Query("SELECT * FROM cell_data ORDER BY timestamp DESC LIMIT :limit")
    List<CellDataEntity> getLatest(int limit);

    // -------------------------------------------------------------------------
    // Location / heatmap queries
    // -------------------------------------------------------------------------

    @Query("SELECT * FROM cell_data WHERE latitude != 0 AND longitude != 0")
    List<CellDataEntity> getWithLocation();

    @Query("SELECT * FROM cell_data WHERE latitude != 0 AND longitude != 0 AND network_type = :networkType")
    List<CellDataEntity> getWithLocationByType(String networkType);

    // -------------------------------------------------------------------------
    // Deletion queries
    // -------------------------------------------------------------------------

    @Query("DELETE FROM cell_data")
    void deleteAll();

    @Query("DELETE FROM cell_data WHERE timestamp < :timestamp")
    void deleteOlderThan(long timestamp);

    // -------------------------------------------------------------------------
    // Analytics queries
    // -------------------------------------------------------------------------

    @Query("SELECT network_type AS networkType, AVG(signal_power) AS avgSignal FROM cell_data GROUP BY network_type")
    List<TypeAvg> getAvgSignalByType();

    /**
     * Projection class for the average signal per network type query.
     */
    class TypeAvg {
        public String networkType;
        public double avgSignal;

        public TypeAvg(String networkType, double avgSignal) {
            this.networkType = networkType;
            this.avgSignal = avgSignal;
        }
    }
}
