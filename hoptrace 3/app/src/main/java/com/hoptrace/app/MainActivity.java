package com.hoptrace.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty";
    private static final int BLUE = Color.rgb(36, 92, 219);
    private static final int DARK = Color.rgb(23, 32, 51);
    private static final int MUTED = Color.rgb(104, 115, 138);
    private static final int LINE = Color.rgb(230, 234, 241);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService traceExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService geoExecutor = Executors.newFixedThreadPool(3);
    private final List<HopResult> hopResults = new ArrayList<>();
    private final Set<String> requestedGeoIps = new HashSet<>();

    private TextInputEditText targetInput;
    private ChipGroup familyChips;
    private ChipGroup probesChips;
    private Slider maxHopsSlider;
    private Slider timeoutSlider;
    private TextView maxHopsValue;
    private TextView timeoutValue;
    private TextView statusText;
    private TextView summaryText;
    private TextView resultsTitle;
    private LinearLayout hopsContainer;
    private FrameLayout mapContainer;
    private MapView mapView;
    private MapLibreMap map;
    private GeoJsonSource routeSource;
    private GeoJsonSource pointsSource;
    private boolean mapReady = false;
    private boolean isRunning = false;
    private long traceStartedAt;
    private int finishedHopCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildScreen(savedInstanceState);
    }

    private void buildScreen(Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 248, 252));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("HopTrace");
        toolbar.setSubtitle("ICMP route explorer");
        toolbar.setTitleTextColor(DARK);
        toolbar.setSubtitleTextColor(MUTED);
        toolbar.setBackgroundColor(Color.rgb(247, 248, 252));
        toolbar.setElevation(0f);
        toolbar.getMenu().add("IPinfo API").setIcon(android.R.drawable.ic_menu_manage)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(item -> {
            showApiSettings();
            return true;
        });
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return windowInsets;
        });

        TextView headline = text("Trace the path. See every hop.", 23, DARK, true);
        content.addView(headline, lp(0, 0, 0, 5));
        TextView intro = text("Run real ICMP traceroute over IPv4 or IPv6, then map public router IPs.",
                13, MUTED, false);
        content.addView(intro, lp(0, 0, 0, 18));

        MaterialCardView inputCard = card();
        LinearLayout inputBody = vertical(16);
        inputBody.setPadding(dp(16), dp(16), dp(16), dp(16));
        inputCard.addView(inputBody);
        content.addView(inputCard, lp(0, 0, 0, 14));

        TextInputLayout targetLayout = new TextInputLayout(this);
        targetLayout.setHint("Hostname or IP address");
        targetLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        targetInput = new TextInputEditText(targetLayout.getContext());
        targetInput.setSingleLine(true);
        targetInput.setText("1.1.1.1");
        targetInput.setTextSize(16);
        targetInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        targetInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        targetLayout.addView(targetInput, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputBody.addView(targetLayout, matchWrap());

        TextView familyLabel = text("IP VERSION", 11, MUTED, true);
        familyBody(inputBody, familyLabel);

        familyChips = new ChipGroup(this);
        familyChips.setSingleSelection(true);
        familyChips.setSelectionRequired(true);
        familyChips.setChipSpacingHorizontal(dp(8));
        Chip auto = chip("Auto", true);
        Chip ipv4 = chip("IPv4", true);
        Chip ipv6 = chip("IPv6", true);
        familyChips.addView(auto);
        familyChips.addView(ipv4);
        familyChips.addView(ipv6);
        auto.setChecked(true);
        inputBody.addView(familyChips, matchWrap());

        MaterialCardView optionsCard = card();
        LinearLayout optionsBody = vertical(12);
        optionsBody.setPadding(dp(16), dp(14), dp(16), dp(14));
        optionsCard.addView(optionsBody);
        content.addView(optionsCard, lp(0, 0, 0, 14));

        LinearLayout maxRow = new LinearLayout(this);
        maxRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView maxTitle = text("Maximum hops", 14, DARK, true);
        maxRow.addView(maxTitle, new LinearLayout.LayoutParams(0, dp(24), 1f));
        maxHopsValue = text("24", 13, BLUE, true);
        maxRow.addView(maxHopsValue);
        optionsBody.addView(maxRow, matchWrap());
        maxHopsSlider = new Slider(this);
        maxHopsSlider.setValueFrom(5f);
        maxHopsSlider.setValueTo(40f);
        maxHopsSlider.setStepSize(1f);
        maxHopsSlider.setValue(24f);
        maxHopsSlider.addOnChangeListener((slider, value, fromUser) -> maxHopsValue.setText(String.valueOf((int) value)));
        optionsBody.addView(maxHopsSlider, matchWrap());

        LinearLayout timeoutRow = new LinearLayout(this);
        timeoutRow.setGravity(Gravity.CENTER_VERTICAL);
        timeoutRow.addView(text("Timeout per probe", 14, DARK, true),
                new LinearLayout.LayoutParams(0, dp(24), 1f));
        timeoutValue = text("750 ms", 13, BLUE, true);
        timeoutRow.addView(timeoutValue);
        optionsBody.addView(timeoutRow, matchWrap());
        timeoutSlider = new Slider(this);
        timeoutSlider.setValueFrom(500f);
        timeoutSlider.setValueTo(2000f);
        timeoutSlider.setStepSize(250f);
        timeoutSlider.setValue(750f);
        timeoutSlider.addOnChangeListener((slider, value, fromUser) -> timeoutValue.setText((int) value + " ms"));
        optionsBody.addView(timeoutSlider, matchWrap());

        TextView probesLabel = text("PROBES PER HOP", 11, MUTED, true);
        optionsBody.addView(probesLabel, lp(0, 4, 0, 0));
        probesChips = new ChipGroup(this);
        probesChips.setSingleSelection(true);
        probesChips.setSelectionRequired(true);
        Chip oneProbe = chip("1 probe", true);
        Chip threeProbes = chip("3 probes", true);
        probesChips.addView(oneProbe);
        probesChips.addView(threeProbes);
        oneProbe.setChecked(true);
        optionsBody.addView(probesChips, matchWrap());

        MaterialButton traceButton = new MaterialButton(this);
        traceButton.setText("Start traceroute");
        traceButton.setTextSize(15);
        traceButton.setIconResource(android.R.drawable.ic_media_play);
        traceButton.setIconPadding(dp(8));
        traceButton.setCornerRadius(dp(14));
        content.addView(traceButton, lp(0, 2, 0, 10));
        traceButton.setOnClickListener(v -> startTrace(traceButton));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        CircularProgressIndicator progress = new CircularProgressIndicator(this);
        progress.setIndicatorSize(dp(18));
        progress.setTrackThickness(dp(2));
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
        statusText = text("Ready when you are", 13, MUTED, false);
        LinearLayout.LayoutParams statusTextLp = new LinearLayout.LayoutParams(0, dp(24), 1f);
        statusTextLp.leftMargin = dp(8);
        statusRow.addView(statusText, statusTextLp);
        content.addView(statusRow, lp(2, 0, 0, 12));
        traceButton.setTag(progress);

        MaterialCardView summaryCard = card();
        LinearLayout summaryBody = vertical(4);
        summaryBody.setPadding(dp(16), dp(12), dp(16), dp(12));
        summaryText = text("No trace yet · Results will appear here", 13, MUTED, false);
        summaryBody.addView(summaryText, matchWrap());
        summaryCard.addView(summaryBody);
        content.addView(summaryCard, lp(0, 0, 0, 14));

        LinearLayout mapHeading = new LinearLayout(this);
        mapHeading.setGravity(Gravity.CENTER_VERTICAL);
        mapHeading.addView(text("Hop map", 17, DARK, true), new LinearLayout.LayoutParams(0, dp(28), 1f));
        mapHeading.addView(text("APPROXIMATE IP GEOLOCATION", 9, MUTED, true));
        content.addView(mapHeading, lp(2, 0, 0, 8));

        MaterialCardView mapCard = card();
        LinearLayout mapBody = vertical(0);
        mapBody.setPadding(dp(4), dp(4), dp(4), dp(4));
        mapContainer = new FrameLayout(this);
        mapContainer.setBackgroundColor(Color.rgb(237, 241, 247));
        TextView mapPlaceholder = text("Run a trace to plot public hops", 13, MUTED, false);
        mapPlaceholder.setGravity(Gravity.CENTER);
        mapContainer.addView(mapPlaceholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapBody.addView(mapContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(248)));
        mapCard.addView(mapBody);
        content.addView(mapCard, lp(0, 0, 0, 10));

        TextView mapNote = text("Pins use city-level IP data, not the router's verified physical address. Some networks hide hops or return no reply.",
                11, MUTED, false);
        content.addView(mapNote, lp(2, 0, 0, 18));

        LinearLayout hopsHeading = new LinearLayout(this);
        hopsHeading.setGravity(Gravity.CENTER_VERTICAL);
        hopsHeading.addView(text("Hop details", 17, DARK, true), new LinearLayout.LayoutParams(0, dp(28), 1f));
        resultsTitle = text("0 hops", 12, MUTED, true);
        hopsHeading.addView(resultsTitle);
        content.addView(hopsHeading, lp(0, 0, 0, 8));

        hopsContainer = vertical(8);
        content.addView(hopsContainer, matchWrap());

        TextView footer = text("Geolocation by IPinfo · Map data © OpenStreetMap contributors · Rendered with MapLibre",
                10, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, lp(0, 20, 0, 0));

        targetInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                traceButton.performClick();
                return true;
            }
            return false;
        });

        setupMap(savedInstanceState);
    }

    private void setupMap(Bundle state) {
        mapView = new MapView(this);
        mapView.onCreate(state);
        mapContainer.removeAllViews();
        mapContainer.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            map.setCameraPosition(new CameraPosition.Builder()
                    .target(new LatLng(20.0, 0.0)).zoom(1.4).build());
            map.setStyle(new Style.Builder().fromUri(MAP_STYLE), style -> {
                routeSource = new GeoJsonSource("hoptrace-route-source",
                        FeatureCollection.fromFeatures(new Feature[0]));
                pointsSource = new GeoJsonSource("hoptrace-points-source",
                        FeatureCollection.fromFeatures(new Feature[0]));
                style.addSource(routeSource);
                style.addSource(pointsSource);
                LineLayer lineLayer = new LineLayer("hoptrace-route-line", "hoptrace-route-source")
                        .withProperties(
                                PropertyFactory.lineColor(BLUE),
                                PropertyFactory.lineWidth(2.5f),
                                PropertyFactory.lineOpacity(0.78f),
                                PropertyFactory.lineCap("round"),
                                PropertyFactory.lineJoin("round"));
                style.addLayer(lineLayer);
                CircleLayer pointLayer = new CircleLayer("hoptrace-hop-points", "hoptrace-points-source")
                        .withProperties(
                                PropertyFactory.circleRadius(9f),
                                PropertyFactory.circleColor(BLUE),
                                PropertyFactory.circleStrokeColor(Color.WHITE),
                                PropertyFactory.circleStrokeWidth(2f));
                style.addLayer(pointLayer);
                SymbolLayer labelLayer = new SymbolLayer("hoptrace-hop-labels", "hoptrace-points-source")
                        .withProperties(
                                PropertyFactory.textField("{label}"),
                                PropertyFactory.textSize(10f),
                                PropertyFactory.textColor(Color.WHITE),
                                PropertyFactory.textAllowOverlap(true),
                                PropertyFactory.textIgnorePlacement(true));
                style.addLayer(labelLayer);
                mapReady = true;
                updateMap();
            });
        });
    }

    private void startTrace(MaterialButton traceButton) {
        if (isRunning) return;
        String host = targetInput.getText() == null ? "" : targetInput.getText().toString().trim();
        if (host.isEmpty()) {
            targetInput.setError("Enter a hostname or IP address");
            return;
        }
        targetInput.setError(null);
        int familyChipId = familyChips.getCheckedChipId();
        Chip selectedChip = familyChips.findViewById(familyChipId);
        String family = selectedChip == null ? "Auto" : selectedChip.getText().toString();
        boolean ipv6;
        try {
            if ("Auto".equals(family)) {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (addresses.length == 0) throw new IllegalArgumentException("No IP address found for this host.");
                ipv6 = addresses[0] instanceof Inet6Address;
            } else {
                ipv6 = "IPv6".equals(family);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not resolve host: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if ("IPv4".equals(family)) ipv6 = false;
        if ("IPv6".equals(family)) ipv6 = true;

        int maxHops = (int) maxHopsSlider.getValue();
        int timeoutMs = (int) timeoutSlider.getValue();
        Chip probesChip = probesChips.findViewById(probesChips.getCheckedChipId());
        int probes = probesChip != null && probesChip.getText().toString().startsWith("3") ? 3 : 1;
        String ipinfoToken = getSharedPreferences("settings", MODE_PRIVATE).getString("ipinfo_token", "");

        isRunning = true;
        traceStartedAt = System.currentTimeMillis();
        hopResults.clear();
        requestedGeoIps.clear();
        finishedHopCount = 0;
        renderHops();
        statusText.setText("Tracing " + host + " over " + (ipv6 ? "IPv6" : "IPv4") + "…");
        summaryText.setText("Sending ICMP probes · Max " + maxHops + " hops");
        traceButton.setEnabled(false);
        traceButton.setText("Tracing…");
        CircularProgressIndicator progress = (CircularProgressIndicator) traceButton.getTag();
        progress.setVisibility(View.VISIBLE);

        final boolean useIpv6 = ipv6;
        traceExecutor.execute(() -> {
            try {
                String raw = NativeTraceroute.run(host, useIpv6, maxHops, timeoutMs, probes);
                JSONObject result = new JSONObject(raw);
                if (result.has("error")) throw new IllegalStateException(result.optString("error"));
                JSONArray hops = result.optJSONArray("hops");
                List<HopResult> parsed = new ArrayList<>();
                if (hops != null) {
                    for (int i = 0; i < hops.length(); i++) {
                        JSONObject item = hops.getJSONObject(i);
                        parsed.add(new HopResult(item.optInt("ttl"), item.optString("ip"),
                                item.isNull("rtt_ms") ? -1 : item.optDouble("rtt_ms", -1),
                                item.optString("type", "TIMEOUT")));
                    }
                }
                boolean reached = result.optBoolean("reached", false);
                mainHandler.post(() -> {
                    hopResults.clear();
                    hopResults.addAll(parsed);
                    isRunning = false;
                    finishedHopCount = parsed.size();
                    long elapsed = System.currentTimeMillis() - traceStartedAt;
                    statusText.setText(reached ? "Destination reached" : "Trace complete · destination not confirmed");
                    summaryText.setText(parsed.size() + " hops · " + (reached ? "destination reached" : "not reached") +
                            " · " + formatElapsed(elapsed));
                    traceButton.setEnabled(true);
                    traceButton.setText("Run again");
                    progress.setVisibility(View.GONE);
                    resultsTitle.setText(parsed.size() + " hops");
                    renderHops();
                    updateMap();
                    queueGeoLookups(ipinfoToken);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isRunning = false;
                    traceButton.setEnabled(true);
                    traceButton.setText("Start traceroute");
                    progress.setVisibility(View.GONE);
                    statusText.setText("Trace failed");
                    summaryText.setText(e.getMessage() == null ? "Traceroute failed." : e.getMessage());
                    Toast.makeText(this, summaryText.getText(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void queueGeoLookups(String token) {
        for (HopResult hop : hopResults) {
            if (hop.ip.isEmpty() || !GeoIpClient.isPublicAddress(hop.ip) || !requestedGeoIps.add(hop.ip)) continue;
            geoExecutor.execute(() -> {
                try {
                    GeoInfo info = GeoIpClient.lookup(getApplicationContext(), hop.ip, token);
                    hop.geoInfo = info;
                } catch (Exception e) {
                    hop.geoError = e.getMessage();
                }
                mainHandler.post(() -> {
                    // The same router can appear at multiple TTLs. Reuse the one
                    // lookup result for every occurrence without extra API calls.
                    for (HopResult candidate : hopResults) {
                        if (hop.ip.equals(candidate.ip)) {
                            candidate.geoInfo = hop.geoInfo;
                            candidate.geoError = hop.geoError;
                        }
                    }
                    renderHops();
                    updateMap();
                });
            });
        }
    }

    private void renderHops() {
        if (hopsContainer == null) return;
        hopsContainer.removeAllViews();
        if (hopResults.isEmpty()) {
            TextView empty = text("No hop data yet. Enter a destination above and start a trace.", 13, MUTED, false);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            hopsContainer.addView(empty, matchWrap());
            resultsTitle.setText("0 hops");
            return;
        }
        resultsTitle.setText(hopResults.size() + " hops");
        for (HopResult hop : hopResults) {
            MaterialCardView itemCard = card();
            itemCard.setCardElevation(0f);
            itemCard.setStrokeColor(LINE);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView number = text(String.format(Locale.US, "%02d", hop.ttl), 12, BLUE, true);
            number.setGravity(Gravity.CENTER);
            number.setBackground(roundRect(Color.rgb(235, 241, 255), dp(10)));
            row.addView(number, new LinearLayout.LayoutParams(dp(38), dp(36)));

            LinearLayout details = vertical(3);
            LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            detailsLp.leftMargin = dp(12);
            if (hop.timedOut()) {
                details.addView(text("*  Request timed out", 13, MUTED, true), matchWrap());
                details.addView(text("No ICMP response from this hop", 11, MUTED, false), matchWrap());
            } else {
                details.addView(text(hop.ip, 13, DARK, true), matchWrap());
                if (hop.geoInfo != null) {
                    details.addView(text(hop.geoInfo.displayLocation(), 11, MUTED, false), matchWrap());
                    if (!hop.geoInfo.organization.isEmpty()) {
                        details.addView(text(hop.geoInfo.organization, 10, MUTED, false), matchWrap());
                    }
                } else if (hop.geoError != null) {
                    String message = hop.geoError.contains("IPinfo token") ? "Add IPinfo token to map public hops" : "Location lookup unavailable";
                    details.addView(text(message, 11, MUTED, false), matchWrap());
                } else if (!GeoIpClient.isPublicAddress(hop.ip)) {
                    details.addView(text("Private / non-global address · not queried", 11, MUTED, false), matchWrap());
                } else {
                    details.addView(text("Looking up approximate IP location…", 11, MUTED, false), matchWrap());
                }
            }
            row.addView(details, detailsLp);

            String rtt = hop.rttMs < 0 ? "—" : new DecimalFormat("0.0").format(hop.rttMs) + " ms";
            TextView timing = text(rtt, 11, hop.timedOut() ? MUTED : BLUE, true);
            timing.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(timing, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            itemCard.addView(row);
            itemCard.setOnClickListener(v -> focusHop(hop));
            hopsContainer.addView(itemCard, matchWrap());
        }
    }

    private void updateMap() {
        if (!mapReady || routeSource == null || pointsSource == null) return;
        List<Point> points = new ArrayList<>();
        List<Feature> pointFeatures = new ArrayList<>();
        for (HopResult hop : hopResults) {
            if (hop.geoInfo == null || !hop.geoInfo.hasCoordinates()) continue;
            Point point = Point.fromLngLat(hop.geoInfo.longitude, hop.geoInfo.latitude);
            points.add(point);
            Feature feature = Feature.fromGeometry(point);
            feature.addNumberProperty("ttl", hop.ttl);
            feature.addStringProperty("label", String.valueOf(hop.ttl));
            feature.addStringProperty("ip", hop.ip);
            pointFeatures.add(feature);
        }
        pointsSource.setGeoJson(FeatureCollection.fromFeatures(pointFeatures.toArray(new Feature[0])));
        if (points.size() >= 2) {
            Feature route = Feature.fromGeometry(LineString.fromLngLats(points));
            routeSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{route}));
        } else {
            routeSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        }
        if (!points.isEmpty()) {
            try {
                if (points.size() == 1) {
                    Point only = points.get(0);
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(only.latitude(), only.longitude()), 4.2), 650);
                } else {
                    LatLngBounds.Builder builder = new LatLngBounds.Builder();
                    for (Point point : points) builder.include(new LatLng(point.latitude(), point.longitude()));
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), dp(32)), 800);
                }
            } catch (Exception ignored) {
                Point last = points.get(points.size() - 1);
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(last.latitude(), last.longitude()), 3.0), 650);
            }
        }
    }

    private void focusHop(HopResult hop) {
        if (map == null || hop.geoInfo == null || !hop.geoInfo.hasCoordinates()) return;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(hop.geoInfo.latitude, hop.geoInfo.longitude), 5.0), 500);
    }

    private void showApiSettings() {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint("IPinfo access token");
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String existing = getSharedPreferences("settings", MODE_PRIVATE).getString("ipinfo_token", "");
        input.setText(existing);
        layout.addView(input, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout wrapper = vertical(10);
        wrapper.setPadding(dp(20), dp(8), dp(20), 0);
        wrapper.addView(layout, matchWrap());
        TextView info = text("Use an IPinfo City-level token. The app caches each public IP lookup for 7 days. The token is stored only on this device.",
                12, MUTED, false);
        wrapper.addView(info, matchWrap());
        new MaterialAlertDialogBuilder(this)
                .setTitle("IP geolocation")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear token", (dialog, which) -> {
                    getSharedPreferences("settings", MODE_PRIVATE).edit().remove("ipinfo_token").apply();
                    Toast.makeText(this, "IPinfo token cleared", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("ipinfo_token", value).apply();
                    Toast.makeText(this, value.isEmpty() ? "Token removed" : "IPinfo token saved", Toast.LENGTH_SHORT).show();
                    requestedGeoIps.clear();
                    if (!hopResults.isEmpty()) queueGeoLookups(value);
                })
                .show();
    }

    private void familyBody(LinearLayout parent, TextView label) {
        parent.addView(label, lp(0, 10, 0, 2));
    }

    private Chip chip(String label, boolean checkable) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(checkable);
        chip.setChipCornerRadius(dp(18));
        chip.setTextSize(13);
        chip.setChipStrokeWidth(dp(1));
        chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(LINE));
        chip.setChipBackgroundColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(231, 238, 255), Color.WHITE}));
        chip.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{BLUE, DARK}));
        return chip;
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(1));
        card.setCardBackgroundColor(Color.WHITE);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(LINE);
        return card;
    }

    private LinearLayout vertical(int spacingDp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams lp(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatElapsed(long ms) {
        if (ms < 1000) return ms + " ms";
        return new DecimalFormat("0.0").format(ms / 1000.0) + " s";
    }

    @Override protected void onStart() { super.onStart(); if (mapView != null) mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); if (mapView != null) mapView.onResume(); }
    @Override protected void onPause() { if (mapView != null) mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { if (mapView != null) mapView.onStop(); super.onStop(); }
    @Override protected void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) mapView.onDestroy();
        traceExecutor.shutdownNow();
        geoExecutor.shutdown();
        super.onDestroy();
    }
}
