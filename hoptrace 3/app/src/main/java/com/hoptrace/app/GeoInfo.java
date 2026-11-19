package com.hoptrace.app;

public final class GeoInfo {
    public final String city;
    public final String region;
    public final String country;
    public final String organization;
    public final double latitude;
    public final double longitude;

    public GeoInfo(String city, String region, String country, String organization,
                   double latitude, double longitude) {
        this.city = city == null ? "" : city;
        this.region = region == null ? "" : region;
        this.country = country == null ? "" : country;
        this.organization = organization == null ? "" : organization;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String displayLocation() {
        StringBuilder out = new StringBuilder();
        append(out, city);
        append(out, region);
        append(out, country);
        return out.length() == 0 ? "Location unavailable" : out.toString();
    }

    private static void append(StringBuilder out, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append(", ");
        out.append(value.trim());
    }

    public boolean hasCoordinates() {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }
}
