package com.hoptrace.app;

public final class HopResult {
    public final int ttl;
    public final String ip;
    public final double rttMs;
    public final String type;
    public volatile GeoInfo geoInfo;
    public volatile String geoError;

    public HopResult(int ttl, String ip, double rttMs, String type) {
        this.ttl = ttl;
        this.ip = ip == null ? "" : ip;
        this.rttMs = rttMs;
        this.type = type == null ? "TIMEOUT" : type;
    }

    public boolean timedOut() {
        return ip.isEmpty() || "TIMEOUT".equals(type);
    }
}
