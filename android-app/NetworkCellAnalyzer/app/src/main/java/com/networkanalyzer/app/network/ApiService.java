package com.networkanalyzer.app.network;

import com.networkanalyzer.app.network.models.AuthResponse;
import com.networkanalyzer.app.network.models.BatchCellDataRequest;
import com.networkanalyzer.app.network.models.CellDataRequest;
import com.networkanalyzer.app.network.models.GenericResponse;
import com.networkanalyzer.app.network.models.HeatmapResponse;
import com.networkanalyzer.app.network.models.HistoryResponse;
import com.networkanalyzer.app.network.models.LoginRequest;
import com.networkanalyzer.app.network.models.RefreshRequest;
import com.networkanalyzer.app.network.models.RegisterRequest;
import com.networkanalyzer.app.network.models.SpeedTestRequest;
import com.networkanalyzer.app.network.models.StatsResponse;
import com.networkanalyzer.app.network.models.DiagnosticsResponse;
import com.networkanalyzer.app.network.models.DiagnosticsHistoryResponse;
import com.networkanalyzer.app.network.models.TowerClusterDetailResponse;
import com.networkanalyzer.app.network.models.TowerClustersResponse;
import com.networkanalyzer.app.network.models.DeadzoneBatchPredictionResponse;
import com.networkanalyzer.app.network.models.DeadzonePredictionResponse;

import retrofit2.Call;
import okhttp3.ResponseBody;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.Streaming;

/**
 * Retrofit API interface defining all network endpoints for the NetworkCellAnalyzer server.
 */
public interface ApiService {

    // ---- Authentication ----

    @POST("auth/login")
    Call<AuthResponse> login(@Body LoginRequest request);

    @POST("auth/register")
    Call<AuthResponse> register(@Body RegisterRequest request);

    @POST("auth/refresh")
    Call<AuthResponse> refreshToken(@Body RefreshRequest request);

    // ---- Cell Data Submission ----

    @POST("receive-data")
    Call<GenericResponse> sendCellData(@Body CellDataRequest request);

    @POST("receive-data/batch")
    Call<GenericResponse> sendBatchCellData(@Body BatchCellDataRequest request);

    // ---- Statistics ----

    @GET("get-stats")
    Call<StatsResponse> getStats(
            @Query("device_id") String deviceId,
            @Query("from") String from,
            @Query("to") String to
    );

    @GET("get-stats/avg-all")
    Call<StatsResponse> getAvgAllStats(
            @Query("from") String from,
            @Query("to") String to
    );

    @Headers("Accept: application/json")
    @GET("api/history")
    Call<HistoryResponse> getHistory(
            @Query("device_id") String deviceId,
            @Query("limit") Integer limit
    );

    @GET("api/heatmap-data")
    Call<HeatmapResponse> getHeatmapData(
            @Query("device_id") String deviceId,
            @Query("network_type") String networkType,
            @Query("limit") Integer limit
    );

    @GET("predict")
    Call<DeadzonePredictionResponse> getDeadzonePrediction(
            @Query("latitude") double latitude,
            @Query("longitude") double longitude,
            @Query("operator") String operator,
            @Query("network_type") String networkType
    );

    @POST("predict/batch")
    Call<DeadzoneBatchPredictionResponse> getDeadzoneBatchPrediction(
            @Body com.google.gson.JsonObject batchRequest
    );

    @GET("api/tower-clusters")
    Call<TowerClustersResponse> getTowerClusters(
            @Query("device_id") String deviceId,
            @Query("network_type") String networkType,
            @Query("limit") Integer limit
    );

    @GET("api/tower-clusters/detail")
    Call<TowerClusterDetailResponse> getTowerClusterDetail(
            @Query("device_id") String deviceId,
            @Query("cell_id") String cellId,
            @Query("network_type") String networkType,
            @Query("operator") String operator,
            @Query("limit") Integer limit
    );

    @GET("api/diagnostics-summary")
    Call<DiagnosticsResponse> getDiagnosticsSummary(
            @Query("device_id") String deviceId,
            @Query("limit") Integer limit
    );

    @GET("api/diagnostics-history")
    Call<DiagnosticsHistoryResponse> getDiagnosticsHistory(
            @Query("device_id") String deviceId,
            @Query("limit") Integer limit
    );

    // ---- Speed Test ----

    @POST("speed-test")
    Call<GenericResponse> submitSpeedTest(@Body SpeedTestRequest request);

    @Streaming
    @GET("api/export.csv")
    Call<ResponseBody> exportCsv(
            @Query("device_id") String deviceId,
            @Query("limit") Integer limit
    );

    @Streaming
    @GET("api/report.pdf")
    Call<ResponseBody> exportPdf(
            @Query("device_id") String deviceId,
            @Query("limit") Integer limit
    );
}
