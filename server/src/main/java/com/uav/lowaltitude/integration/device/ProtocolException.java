package com.uav.lowaltitude.integration.device;

public class ProtocolException extends RuntimeException {

    private final String code;

    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
