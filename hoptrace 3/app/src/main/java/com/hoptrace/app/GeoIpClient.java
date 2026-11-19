package com.hoptrace.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** IPinfo City-level lookup with a seven-day on-device cache. */
public final class GeoIpClient {
    private static final String CACHE_PREFS = "geo_cache_v1";
    private static final long CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7);

    private GeoIpClient() {}

    public static GeoInfo lookup(Context context, String ip, String token) throws Exception {
        if (!isPublicAddress(ip)) return null;
        SharedPreferences cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        String cacheKey = "ip_" + ip.replace(':', '_').replace('.', '_');
        String cached = cache.getString(cacheKey, null);
        if (cached != null) {
            try {
                JSONObject saved = new JSONObject(cached);
                if (System.currentTimeMillis() - saved.optLong("cached_at") < CACHE_TTL_MS) {
                    return fromJson(saved);
                }
            } catch (Exception ignored) {
                // Corrupt/stale cache: fetch a fresh record.
            }
        }

        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException("Add an IPinfo token in Settings to show hop locations.");
        }

        String encodedIp = URLEncoder.encode(ip, StandardCharsets.UTF_8.name());
        String encodedToken = URLEncoder.encode(token.trim(), StandardCharsets.UTF_8.name());
        URL url = new URL("https://ipinfo.io/" + encodedIp + "/json?token=" + encodedToken);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "HopTrace-Android/1.0");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                String message = "IPinfo returned HTTP " + code;
                try { message = new JSONObject(body).optString("error", message); } catch (Exception ignored) {}
                throw new IllegalStateException(message);
            }
            JSONObject json = new JSONObject(body);
            if (json.has("bogon") && json.optBoolean("bogon")) return null;
            String[] coordinates = json.optString("loc", "").split(",");
            if (coordinates.length != 2) throw new IllegalStateException("IPinfo did not return city coordinates for this address.");
            double lat = Double.parseDouble(coordinates[0]);
            double lon = Double.parseDouble(coordinates[1]);
            GeoInfo info = new GeoInfo(json.optString("city"), json.optString("region"),
                    json.optString("country"), json.optString("org"), lat, lon);
            JSONObject stored = new JSONObject();
            stored.put("city", info.city);
            stored.put("region", info.region);
            stored.put("country", info.country);
            stored.put("organization", info.organization);
            stored.put("latitude", info.latitude);
            stored.put("longitude", info.longitude);
            stored.put("cached_at", System.currentTimeMillis());
            cache.edit().putString(cacheKey, stored.toString()).apply();
            return info;
        } finally {
            connection.disconnect();
        }
    }

    private static GeoInfo fromJson(JSONObject json) {
        return new GeoInfo(json.optString("city"), json.optString("region"),
                json.optString("country"), json.optString("organization"),
                json.optDouble("latitude", Double.NaN), json.optDouble("longitude", Double.NaN));
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    /** Returns false for private, loopback, link-local, multicast and documentation ranges. */
    public static boolean isPublicAddress(String value) {
        if (value == null || value.trim().isEmpty() || value.contains("%")) return false;
        try {
            InetAddress address = InetAddress.getByName(value);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                    address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                    address.isMulticastAddress()) return false;
            byte[] b = address.getAddress();
            if (address instanceof Inet4Address) {
                int a = b[0] & 0xff, c = b[1] & 0xff, d = b[2] & 0xff;
                if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
                if (a == 100 && c >= 64 && c <= 127) return false;       // shared CGNAT
                if (a == 169 && c == 254) return false;
                if (a == 172 && c >= 16 && c <= 31) return false;
                if (a == 192 && c == 168) return false;
                if (a == 192 && c == 0 && d == 0) return false;
                if (a == 192 && c == 0 && d == 2) return false;          // TEST-NET-1
                if (a == 192 && c == 88 && d == 99) return false;
                if (a == 198 && (c == 18 || c == 19)) return false;
                if (a == 198 && c == 51 && d == 100) return false;      // TEST-NET-2
                if (a == 203 && c == 0 && d == 113) return false;        // TEST-NET-3
                return true;
            }
            if (address instanceof Inet6Address) {
                // Only global-unicast 2000::/3 is eligible for an external lookup.
                if ((b[0] & 0xe0) != 0x20) return false;
                // Documentation prefix 2001:db8::/32.
                if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 &&
                        (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return false;
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
