package com.networkanalyzer.app.services;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.networkanalyzer.app.database.AppDatabase;
import com.networkanalyzer.app.database.CellDataEntity;
import com.networkanalyzer.app.helpers.CellInfoHelper;
import com.networkanalyzer.app.models.CellDataEntry;
import com.networkanalyzer.app.network.ApiService;
import com.networkanalyzer.app.network.RetrofitClient;
import com.networkanalyzer.app.network.models.CellDataRequest;
import com.networkanalyzer.app.network.models.GenericResponse;
import com.networkanalyzer.app.utils.AdaptiveMonitoringEngine;
import com.networkanalyzer.app.utils.Constants;
import com.networkanalyzer.app.utils.NetworkIdentityHelper;
import com.networkanalyzer.app.utils.NotificationHelper;
import com.networkanalyzer.app.utils.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class CellMonitorService extends Service {

    private static final String TAG = "CellMonitorService";
    private static final SimpleDateFormat ISO_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);

    static {
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable collectionRunnable = new Runnable() {
        @Override
        public void run() {
            collectAndDispatch();
            handler.postDelayed(this, nextIntervalMs);
        }
    };

    private PreferenceManager preferenceManager;
    private TelephonyManager telephonyManager;
    private AppDatabase database;
    private final Deque<Integer> recentSignals = new ArrayDeque<>();
    private long nextIntervalMs = Constants.DEFAULT_COLLECTION_INTERVAL;
    private String previousServingKey;
    private int recentHandovers;
    private int lastNeighborCount;

    @Override
    public void onCreate() {
        super.onCreate();
        preferenceManager = new PreferenceManager(this);
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        database = AppDatabase.getInstance(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(
                Constants.NOTIFICATION_SERVICE,
                NotificationHelper.showServiceNotification(
                        this,
                        getString(com.networkanalyzer.app.R.string.service_title),
                        getString(com.networkanalyzer.app.R.string.service_idle)
                )
        );
        preferenceManager.setMonitoringActive(true);
        handler.removeCallbacks(collectionRunnable);
        handler.post(collectionRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(collectionRunnable);
        executor.shutdownNow();
        preferenceManager.setMonitoringActive(false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void collectAndDispatch() {
        if (telephonyManager == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        executor.execute(() -> {
            try {
                List<CellInfo> allCellInfo = telephonyManager.getAllCellInfo();
                if (allCellInfo == null || allCellInfo.isEmpty()) {
                    return;
                }

                CellDataEntry selected = null;
                for (CellInfo info : allCellInfo) {
                    if (info.isRegistered()) {
                        selected = CellInfoHelper.parseCellInfo(info);
                        break;
                    }
                }
                if (selected == null) {
                    selected = CellInfoHelper.parseCellInfo(allCellInfo.get(0));
                }
                if (selected == null) {
                    return;
                }

                selected.setOperator(safeOperatorName());
                selected.setSimSlot(0);
                populateLocation(selected);
                List<CellDataRequest.NeighborCellPayload> neighborPayloads = buildNeighborPayloads(allCellInfo);
                updateAdaptiveState(selected, neighborPayloads);

                CellDataEntity entity = toEntity(selected);
                long rowId = database.cellDataDao().insert(entity);
                sendBroadcastUpdate(selected);
                updateNotification(selected);
                submitToServer(entity, neighborPayloads, (int) rowId);
            } catch (SecurityException e) {
                Log.w(TAG, "Missing permission while collecting cell data", e);
            } catch (Exception e) {
                Log.e(TAG, "Failed to collect cell data", e);
            }
        });
    }

    private void populateLocation(CellDataEntry entry) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location best = gps != null ? gps : network;
            if (best != null) {
                entry.setLatitude(best.getLatitude());
                entry.setLongitude(best.getLongitude());
            }
        } catch (SecurityException ignored) {
        }
    }

    private String safeOperatorName() {
        String name = telephonyManager != null ? telephonyManager.getNetworkOperatorName() : null;
        return name == null || name.trim().isEmpty() ? "Unknown Operator" : name;
    }

    private CellDataEntity toEntity(CellDataEntry entry) {
        CellDataEntity entity = new CellDataEntity();
        entity.setDeviceId(preferenceManager.getDeviceId());
        entity.setOperator(entry.getOperator());
        entity.setSignalPower(entry.getSignalPower());
        entity.setSnr(entry.getSnr());
        entity.setNetworkType(entry.getNetworkType());
        entity.setFrequencyBand(entry.getFrequencyBand());
        entity.setCellId(entry.getCellId());
        entity.setLac(entry.getLac());
        entity.setMcc(entry.getMcc());
        entity.setMnc(entry.getMnc());
        entity.setLatitude(entry.getLatitude());
        entity.setLongitude(entry.getLongitude());
        entity.setTimestamp(entry.getTimestamp());
        entity.setSimSlot(entry.getSimSlot());
        entity.setSynced(false);
        return entity;
    }

    private void sendBroadcastUpdate(CellDataEntry entry) {
        Intent intent = new Intent(Constants.ACTION_CELL_DATA_UPDATED);
        intent.putExtra("operator", entry.getOperator());
        intent.putExtra("networkType", entry.getNetworkType());
        intent.putExtra("signalPower", entry.getSignalPower());
        intent.putExtra("snr", (float) entry.getSnr());
        intent.putExtra("cellId", parseLong(entry.getCellId()));
        intent.putExtra("frequencyBand", entry.getFrequencyBand());
        intent.putExtra("lac", parseInt(entry.getLac()));
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void updateNotification(CellDataEntry entry) {
        String text = entry.getNetworkType() + " | "
                + entry.getOperator() + " | "
                + entry.getSignalPower() + " dBm";
        NotificationHelper.showServiceNotification(
                this,
                getString(com.networkanalyzer.app.R.string.service_title),
                text
        );
        if (entry.getSignalPower() <= preferenceManager.getSignalThreshold()
                && preferenceManager.isAlertEnabled()) {
            NotificationHelper.showSignalAlert(
                    this,
                    getString(com.networkanalyzer.app.R.string.alert_signal_low_title),
                    text
            );
        }
    }

    private void submitToServer(CellDataEntity entity,
                                List<CellDataRequest.NeighborCellPayload> neighborPayloads,
                                int localId) {
        ApiService apiService = RetrofitClient.getInstance(this).getApiService();
        CellDataRequest request = new CellDataRequest();
        request.setDeviceId(entity.getDeviceId());
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
        request.setIpAddress(NetworkIdentityHelper.getBestEffortIpAddress(this));
        request.setMacAddress(NetworkIdentityHelper.getBestEffortMacAddress(this));
        request.setSimSlot(entity.getSimSlot());
        request.setNeighborCells(neighborPayloads);

        apiService.sendCellData(request).enqueue(new Callback<GenericResponse>() {
            @Override
            public void onResponse(Call<GenericResponse> call, Response<GenericResponse> response) {
                if (response.isSuccessful()) {
                    executor.execute(() -> database.cellDataDao().markAsSynced(localId));
                }
            }

            @Override
            public void onFailure(Call<GenericResponse> call, Throwable t) {
                Log.w(TAG, "Server submission failed; record will sync later", t);
            }
        });
    }

    private List<CellDataRequest.NeighborCellPayload> buildNeighborPayloads(List<CellInfo> allCellInfo) {
        List<CellDataRequest.NeighborCellPayload> payloads = new ArrayList<>();
        for (CellInfo info : allCellInfo) {
            CellDataEntry entry = CellInfoHelper.parseCellInfo(info);
            if (entry == null) {
                continue;
            }
            payloads.add(new CellDataRequest.NeighborCellPayload(
                    entry.getNetworkType(),
                    entry.getCellId(),
                    entry.getSignalPower() != CellInfoHelper.UNAVAILABLE ? entry.getSignalPower() : null,
                    info.isRegistered()
            ));
        }
        return payloads;
    }

    private void updateAdaptiveState(CellDataEntry selected,
                                     List<CellDataRequest.NeighborCellPayload> neighborPayloads) {
        recentSignals.addLast(selected.getSignalPower());
        while (recentSignals.size() > Constants.PREDICTION_WINDOW_SIZE) {
            recentSignals.removeFirst();
        }

        String servingKey = selected.getNetworkType() + "|" + selected.getCellId();
        if (previousServingKey != null && !previousServingKey.equals(servingKey)) {
            recentHandovers = Math.min(4, recentHandovers + 1);
        } else {
            recentHandovers = Math.max(0, recentHandovers - 1);
        }
        previousServingKey = servingKey;

        int neighbors = 0;
        for (CellDataRequest.NeighborCellPayload payload : neighborPayloads) {
            if (!payload.isRegistered()) {
                neighbors++;
            }
        }
        lastNeighborCount = neighbors;
        nextIntervalMs = AdaptiveMonitoringEngine.chooseIntervalMs(
                preferenceManager.getCollectionInterval(),
                recentSignals,
                recentHandovers,
                lastNeighborCount
        );
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return -1L;
        }
    }
}
