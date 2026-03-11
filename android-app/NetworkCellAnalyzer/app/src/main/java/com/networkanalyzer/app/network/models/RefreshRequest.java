package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

public class RefreshRequest {

    @SerializedName("refresh_token")
    private String refreshToken;

    public RefreshRequest() {
    }

    public RefreshRequest(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
