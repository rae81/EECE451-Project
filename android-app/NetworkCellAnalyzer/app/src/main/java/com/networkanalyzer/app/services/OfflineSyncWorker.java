package com.networkanalyzer.app.services;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.networkanalyzer.app.database.AppDatabase;
import com.networkanalyzer.app.database.CellDataDao;
import com.networkanalyzer.app.database.CellDataEntity;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.BatchCellDataRequest;
import com.networkanalyzer.app.network.models.CellDataRequest;
import com.networkanalyzer.app.network.models.GenericResponse;
import com.networkanalyzer.app.utils.NetworkIdentityHelper;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import retrofit2.Response;

/**
 * WorkManager {@link Worker} that periodically syncs locally stored cell data
 * to the remote server.
 * <p>
 * The worker queries all unsynced {@link CellDataEntity} entries from Room,
 * converts them to a {@link BatchCellDataRequest}, sends them via
 * {@link ApiService#sendBatchCellData(BatchCellDataRequest)}, and marks them
 * as synced upon success.
 * <p>
 * Scheduled to run every 15 minutes when a network connection is available.
 */
public class OfflineSyncWorker extends Worker {

    private static final String TAG = "OfflineSyncWorker";
    private static final String UNIQUE_WORK_NAME = "offline_sync_worker";

    private static final SimpleDateFormat ISO_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);

    static {
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public OfflineSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // -------------------------------------------------------------------------
    // doWork
    // -------------------------------------------------------------------------

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Starting offline sync work...");

        Context context = getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(context);
        CellDataDao dao = db.cellDataDao();

        // Fetch all unsynced entries
        List<CellDataEntity> unsyncedEntries = dao.getUnsynced();
        if (unsyncedEntries == null || unsyncedEntries.isEmpty()) {
            Log.i(TAG, "No unsynced entries found. Nothing to sync.");
            return Result.success();
        }

        Log.i(TAG, "Found " + unsyncedEntries.size() + " unsynced entries.");

        PreferenceManager preferenceManager = new PreferenceManager(context);
        String deviceId = preferenceManager.getDeviceId();
        String ipAddress = NetworkIdentityHelper.getBestEffortIpAddress(context);
        String macAddress = NetworkIdentityHelper.getBestEffortMacAddress(context);
        RetrofitClient retrofitClient = RetrofitClient.getInstance(context);
        retrofitClient.updateBaseUrl();
        ApiService apiService = retrofitClient.getApiService();

        // Convert entities to API request objects
        List<CellDataRequest> requestList = new ArrayList<>();
        List<Integer> entityIds = new ArrayList<>();
        for (CellDataEntity entity : unsyncedEntries) {
            CellDataRequest request = new CellDataRequest();
            request.setDeviceId(entity.getDeviceId() != null ? entity.getDeviceId() : deviceId);
            request.setOperator(entity.getOperator());
            request.setSignalPower(entity.getSignalPower());
            request.setSnr(entity.getSnr());
            request.setNetworkType(entity.getNetworkType());
            request.setFrequencyBand(entity.getFrequencyBand());
            request.setCellId(entity.getCellId());
            request.setLac(entity.getLac());
            request.setMcc(entity.getMcc());
            request.setMnc(entity.getMnc());
            request.setLatitude(entity.getLatitude());
            request.setLongitude(entity.getLongitude());
            request.setTimestamp(ISO_FORMAT.format(new Date(entity.getTimestamp())));
            request.setIpAddress(ipAddress);
            request.setMacAddress(macAddress);
            request.setSimSlot(entity.getSimSlot());

            requestList.add(request);
            entityIds.add(entity.getId());
        }

        BatchCellDataRequest batchRequest = new BatchCellDataRequest(deviceId, requestList);

        // Send batch request synchronously
        try {
            Response<GenericResponse> response = apiService.sendBatchCellData(batchRequest).execute();

            if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                // Mark all entries as synced
                dao.markAllAsSynced(entityIds);
                Log.i(TAG, "Successfully synced " + entityIds.size() + " entries.");
                return Result.success();
            } else {
                int code = response.code();
                Log.w(TAG, "Server returned unsuccessful response. Code: " + code);
                return Result.retry();
            }
        } catch (IOException e) {
            Log.e(TAG, "Network error during sync", e);
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during sync", e);
            return Result.retry();
        }
    }

    // -------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------

    /**
     * Enqueues a periodic sync worker that runs every 15 minutes when a network
     * connection is available. Uses {@link ExistingPeriodicWorkPolicy#KEEP} to
     * avoid duplicating already-scheduled work.
     *
     * @param context application context
     */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest syncRequest = new PeriodicWorkRequest.Builder(
                OfflineSyncWorker.class,
                15, TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .addTag(TAG)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
        );

        Log.i(TAG, "Periodic sync worker scheduled (every 15 minutes, network required).");
    }

    /**
     * Cancels any previously scheduled periodic sync worker.
     *
     * @param context application context
     */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
        Log.i(TAG, "Periodic sync worker cancelled.");
    }
}
