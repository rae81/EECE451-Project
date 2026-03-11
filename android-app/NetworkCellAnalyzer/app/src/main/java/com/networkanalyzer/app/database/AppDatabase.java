package com.networkanalyzer.app.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room database for the NetworkCellAnalyzer application.
 * <p>
 * Holds cell data readings and speed test results. Uses a singleton pattern
 * so only one instance of the database exists throughout the application lifecycle.
 */
@Database(
        entities = {CellDataEntity.class, SpeedTestEntity.class},
        version = 2,
        exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "network_analyzer_db";

    private static volatile AppDatabase INSTANCE;

    /**
     * Returns the DAO for cell data operations.
     */
    public abstract CellDataDao cellDataDao();

    /**
     * Returns the DAO for speed test operations.
     */
    public abstract SpeedTestDao speedTestDao();

    /**
     * Returns the singleton instance of the database, creating it if necessary.
     *
     * @param context application context (will be converted to application context internally)
     * @return the singleton {@link AppDatabase} instance
     */
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DATABASE_NAME
                            )
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
