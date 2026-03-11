package com.networkanalyzer.app.network.models;

import com.google.gson.annotations.SerializedName;

public class GenericResponse {

    @SerializedName("success")
    private boolean success;

    @SerializedName("message")
    private String message;

    public GenericResponse() {
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
