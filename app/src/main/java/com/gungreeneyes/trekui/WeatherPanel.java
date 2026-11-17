package com.gungreeneyes.trekui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home panel with live weather: current conditions and a three-day forecast from Open-Meteo
 * ({@link Weather}). The location is the device's (coarse location, asked only when chosen) or a
 * searched city. The forecast JSON is cached in preferences (also read by the Pipe Lounge scenes)
 * and refreshed at most every {@link #MAX_AGE} ms while the home screen is visible.
 *
 * <p>All network and geocoding work runs on {@link Weather#IO}; results are applied on the main
 * thread only while the activity is still alive.
 */
class WeatherPanel extends Panel {
    /** A cached forecast younger than this is shown without refetching. */
    private static final long MAX_AGE = 30 * 60 * 1000L;
    /** Automatic retries after a failure wait at least this long. */
    private static final long RETRY_MS = 5 * 60 * 1000L;
    /** A manual refresh within this time of the last one is ignored (unless data is stale). */
    private static final long MANUAL_DEBOUNCE_MS = 15_000L;
    /** A last-known position younger than this is used without asking for a new fix. */
    private static final long FRESH_FIX_MS = 3 * 3600 * 1000L;
    /** Icon sizes and the day-name column width, in dp at the design scale. */
    private static final float ICON_DP = 64, DAY_ICON_DP = 22, DAY_COLUMN_DP = 58;
    private final ScreenMetrics metrics;
    private final Prefs p;
    private boolean busy;
    private long lastAttempt;

    WeatherPanel(MainActivity a, int accent) {
        super(a, accent);
        p = Prefs.get(a);
        metrics = ScreenMetrics.of(a);
    }

    /** False once the activity is finishing or destroyed: late background results are dropped. */
    private boolean alive() { return !a.isFinishing() && !a.isDestroyed(); }

    /** Runs on the main thread if the activity is still alive. */
    private void onUi(final Runnable r) {
        a.runOnUiThread(new Runnable() { @Override public void run() { if (alive()) r.run(); } });
    }

    /** A stored coordinate, or NaN when missing or unreadable. */
    private double coord(String key) {
        String s = p.s(key, null);
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    @Override String id() { return Prefs.W_WEATHER; }
    @Override String title() { return "WEATHER"; }

    @Override
    void build() {
        render();
        card.body.setOnClickListener(Ui.feedback(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (p.i("wxMode", Prefs.WX_NONE) == Prefs.WX_NONE) chooseLocation();
                else refresh(true);
            }
        }));
    }

    @Override String[] extraMenu() {
        return new String[]{"REFRESH NOW", "CHANGE LOCATION", p.fahrenheit() ? "USE °C" : "USE °F"};
    }

    @Override void onExtraMenu(int w) {
        if (w == 0) refresh(true);
        else if (w == 1) chooseLocation();
        else {
            p.put("wxF", !p.fahrenheit());
            p.put("wxTime", 0L);
            refresh(true);
        }
    }

    @Override void resume() { refresh(false); }
    @Override void tick() { refresh(false); }

    // ---- location ------------------------------------------------------------------------------

    /** Asks whether to use the device location or search for a city. */
    void chooseLocation() {
        a.showing(ConsoleDialog.options(a, "WEATHER LOCATION", new String[]{"USE DEVICE LOCATION", "SEARCH FOR A CITY"},
                new ConsoleDialog.OnPick() {
                    @Override public void pick(int w) {
                        if (w == 0) useDevice();
                        else searchCity();
                    }
                }));
    }

    private boolean hasLocationPerm() {
        return a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void useDevice() {
        p.put("wxMode", Prefs.WX_DEVICE);
        if (!hasLocationPerm()) {
            a.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, MainActivity.REQ_LOCATION);
            return;
        }
        locate();
    }

    @Override
    void onPermissionResult(int code, boolean granted) {
        if (code != MainActivity.REQ_LOCATION) return;
        if (granted) locate();
        else {
            p.put("wxMode", Prefs.WX_NONE);
            a.toast("LOCATION DENIED • YOU CAN SEARCH FOR A CITY INSTEAD");
            render();
        }
    }

    /**
     * Uses the freshest last-known position at once (any age, so something shows), and asks for
     * a current fix when it is older than {@link #FRESH_FIX_MS}.
     */
    @SuppressWarnings({"deprecation", "MissingPermission"}) // requestSingleUpdate only below API 30; permission checked
    private void locate() {
        final LocationManager lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !hasLocationPerm()) return;
        Location best = null;
        String[] providers = Build.VERSION.SDK_INT >= 31
                ? new String[]{LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String pr : providers) {
            try {
                Location l = lm.getLastKnownLocation(pr);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) {
            }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < FRESH_FIX_MS) {
            gotLocation(best);
            return;
        }
        if (best != null) gotLocation(best);
        card.status.setText("ACQUIRING POSITION…");
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                : (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) ? LocationManager.FUSED_PROVIDER
                : LocationManager.GPS_PROVIDER;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, a.getMainExecutor(), new java.util.function.Consumer<Location>() {
                    @Override public void accept(Location l) {
                        if (!alive()) return;
                        if (l != null) gotLocation(l);
                        else if (p.s("wxLat", null) == null) locationFailed();
                    }
                });
            } else {
                lm.requestSingleUpdate(provider, new LocationListener() {
                    @Override public void onLocationChanged(Location l) { if (alive()) gotLocation(l); }
                    @Override public void onStatusChanged(String s, int i, Bundle b) { }
                    @Override public void onProviderEnabled(String s) { }
                    @Override public void onProviderDisabled(String s) { }
                }, Looper.getMainLooper());
            }
        } catch (Exception e) {
            if (best == null) locationFailed();
        }
    }

    private void locationFailed() {
        Sfx.play(a, Sfx.ERROR);
        card.status.setText("POSITION UNAVAILABLE");
        a.toast("COULD NOT GET LOCATION • CHECK LOCATION IS ON, OR SEARCH FOR A CITY");
    }

    /** Stores a new position, then names it (reverse geocoding) and refreshes the forecast. */
    private void gotLocation(final Location l) {
        if (!Weather.validCoordinates(l.getLatitude(), l.getLongitude())) return;
        p.put("wxLat", String.valueOf(l.getLatitude()));
        p.put("wxLon", String.valueOf(l.getLongitude()));
        p.put("wxTime", 0L);
        final String fallback = String.format(Locale.US, "%.2f, %.2f", l.getLatitude(), l.getLongitude());
        final Context app = a.getApplicationContext();
        if (!Geocoder.isPresent()) { named(fallback); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                new Geocoder(app, Locale.getDefault()).getFromLocation(l.getLatitude(), l.getLongitude(), 1,
                        new Geocoder.GeocodeListener() {
                            @Override public void onGeocode(List<Address> r) { namedFromUi(placeName(r, fallback)); }
                            @Override public void onError(String message) { namedFromUi(fallback); }
                        });
            } catch (RuntimeException e) {
                named(fallback);
            }
            return;
        }
        Weather.IO.execute(new Runnable() {
            @Override public void run() {
                List<Address> r = null;
                try {
                    r = legacyGeocode(app, l);
                } catch (java.io.IOException | RuntimeException ignored) {
                    // no geocoder backend or no network: use the coordinates as the name
                }
                namedFromUi(placeName(r, fallback));
            }
        });
    }

    @SuppressWarnings("deprecation") // the blocking form is the only one before API 33
    private static List<Address> legacyGeocode(Context c, Location l) throws java.io.IOException {
        return new Geocoder(c, Locale.getDefault()).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
    }

    /** "Locality, Region" of the first address, or the fallback. */
    private static String placeName(List<Address> r, String fallback) {
        if (r == null || r.isEmpty()) return fallback;
        Address ad = r.get(0);
        String name = ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea();
        if (name != null && ad.getAdminArea() != null) name += ", " + ad.getAdminArea();
        return name != null ? name : fallback;
    }

    private void namedFromUi(final String name) {
        onUi(new Runnable() { @Override public void run() { named(name); } });
    }

    private void named(String name) {
        p.put("wxName", name);
        refresh(true);
    }

    /** Prompts for a city name and offers the geocoder's matches. */
    private void searchCity() {
        a.showing(ConsoleDialog.prompt(a, "SEARCH CITY", "", "e.g. NAMPA", new ConsoleDialog.OnText() {
            @Override public void text(String typed) {
                final String q = typed == null ? "" : typed.trim();
                if (q.isEmpty()) return;
                card.status.setText("SEARCHING…");
                Weather.IO.execute(new Runnable() {
                    @Override public void run() {
                        List<Weather.Place> found = null;
                        try { found = Weather.search(q); } catch (java.io.IOException | org.json.JSONException | RuntimeException ignored) { }
                        final List<Weather.Place> f = found;
                        onUi(new Runnable() { @Override public void run() { showResults(q, f); } });
                    }
                });
            }
        }));
    }

    private void showResults(String q, final List<Weather.Place> found) {
        card.status.setText("");
        if (found == null) { Sfx.play(a, Sfx.ERROR); a.toast("SEARCH FAILED • CHECK YOUR CONNECTION"); return; }
        if (found.isEmpty()) { Sfx.play(a, Sfx.DENY); a.toast("NO LOCATION MATCHES “" + q.toUpperCase(Locale.getDefault()) + "”"); return; }
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(i).name.toUpperCase(Locale.getDefault());
        a.showing(ConsoleDialog.options(a, "SELECT LOCATION", names, new ConsoleDialog.OnPick() {
            @Override public void pick(int w) {
                Weather.Place pl = found.get(w);
                p.put("wxMode", Prefs.WX_CITY);
                p.put("wxLat", String.valueOf(pl.lat));
                p.put("wxLon", String.valueOf(pl.lon));
                p.put("wxName", pl.name);
                p.put("wxTime", 0L);
                Sfx.play(a, Sfx.CONFIRM);
                refresh(true);
            }
        }));
    }

    // ---- fetch & render ------------------------------------------------------------------------

    /**
     * Fetches a new forecast when the cache is stale, the units changed or (force) the person
     * asked; otherwise just redraws. Never runs two fetches at once.
     */
    void refresh(boolean force) {
        int mode = p.i("wxMode", Prefs.WX_NONE);
        String lat = p.s("wxLat", null), lon = p.s("wxLon", null);
        final double la = coord("wxLat"), lo = coord("wxLon");
        if (mode == Prefs.WX_NONE || lat == null || lon == null || !Weather.validCoordinates(la, lo)) { render(); return; }
        long age = System.currentTimeMillis() - p.l("wxTime", 0);
        boolean unitsChanged = p.b("wxCacheF", true) != p.fahrenheit();
        if (!force && age < MAX_AGE && !unitsChanged) { render(); return; }
        if (busy) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastAttempt < RETRY_MS && p.s("wxCache", null) != null) { render(); return; }
        if (force && now - lastAttempt < MANUAL_DEBOUNCE_MS && age < MAX_AGE && !unitsChanged) return;
        lastAttempt = now;
        if (mode == Prefs.WX_DEVICE && hasLocationPerm() && age > MAX_AGE * 4) {
            // Re-acquire position occasionally (cheap: last-known first).
            locate();
        }
        busy = true;
        final boolean manual = force;
        if (manual) Sfx.play(a, Sfx.SCAN_START);
        card.status.setText("UPDATING…");
        final String reqLat = lat, reqLon = lon;
        final boolean f = p.fahrenheit();
        Weather.IO.execute(new Runnable() {
            @Override public void run() {
                String json = null;
                try {
                    json = Weather.get(Weather.forecastUrl(la, lo, f));
                    Weather.parse(json, f);
                } catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                    json = null;
                }
                final String j = json;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) { busy = false; return; }
                        busy = false;
                        if (!reqLat.equals(p.s("wxLat", null)) || !reqLon.equals(p.s("wxLon", null)) || f != p.fahrenheit()) {
                            refresh(true); // location or units changed mid-request
                            return;
                        }
                        if (j != null) {
                            p.put("wxCache", j);
                            p.put("wxCacheF", f);
                            p.put("wxTime", System.currentTimeMillis());
                        }
                        render();
                        if (j == null) card.status.setText("SUBSPACE LINK OFFLINE");
                        if (manual) Sfx.play(a, j != null ? Sfx.SCAN_DONE : Sfx.ERROR);
                    }
                });
            }
        });
    }

    private String deg(double v) {
        return Weather.degrees(v);
    }

    /** Rebuilds the panel body from the cached forecast (or the set-location prompt). */
    private void render() {
        LinearLayout b = card.body;
        b.removeAllViews();
        int mode = p.i("wxMode", Prefs.WX_NONE);
        if (mode == Prefs.WX_NONE) {
            TextView t = Ui.text(a, "NO LOCATION SET. Weather uses Open-Meteo (free, no account). "
                    + "Choose your device location or search for a city.", 15, pal.textHi);
            t.setLineSpacing(0, 1.1f);
            b.addView(t);
            TextView btn = Ui.button(a, "SET LOCATION", accent, Ui.PILL, new View.OnClickListener() {
                @Override public void onClick(View v) { chooseLocation(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 52));
            lp.topMargin = Ui.dp(a, Ui.M);
            b.addView(btn, lp);
            card.status.setText("OFFLINE");
            return;
        }
        String cache = p.s("wxCache", null);
        Weather.Data d = null;
        if (cache != null) {
            try { d = Weather.parse(cache, p.b("wxCacheF", true)); } catch (Exception ignored) { }
        }
        String place = p.s("wxName", "").toUpperCase(Locale.getDefault());
        if (d == null) {
            b.addView(Ui.text(a, busy ? "CONTACTING WEATHER SERVICE…" : "NO DATA YET • TAP TO RETRY", 15, pal.textHi));
            card.status.setText(place);
            return;
        }
        String unit = d.fahrenheit ? "F" : "C";
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        WxIcon icon = new WxIcon(a, Weather.kind(d.code), d.day, pal);
        int iconPx = Math.max(Ui.dp(a, ICON_DP), metrics.scaledPx(ICON_DP));
        row.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        TextView temp = Ui.bold(a, deg(d.temp) + unit, 46, pal.textHi);
        temp.setPadding(Ui.dp(a, Ui.M), 0, Ui.dp(a, Ui.M), 0);
        row.addView(temp);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.oneLine(Ui.bold(a, Weather.describe(d.code), 17, accent)));
        TextView det = Ui.text(a, "FEELS " + deg(d.feels) + "  •  WIND " + Math.round(d.wind) + Weather.windUnit(d.fahrenheit)
                + (d.humidity >= 0 ? "\nHUMIDITY " + d.humidity + "%" : ""), 13, pal.textLo);
        det.setPadding(0, Ui.dp(a, Ui.XS), 0, 0);
        col.addView(det);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        b.addView(row);

        int dayIcon = Math.max(Ui.dp(a, DAY_ICON_DP), metrics.scaledPx(DAY_ICON_DP));
        for (int i = 0; i < d.days.size() && i < Weather.FORECAST_DAYS; i++) {
            Weather.Day day = d.days.get(i);
            String name = Weather.dayName(day.date, i, Locale.getDefault());
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, Ui.dp(a, Ui.XS), 0, 0);
            r.addView(Ui.bold(a, name, 15, pal.c(i + 1)), new LinearLayout.LayoutParams(Math.max(Ui.dp(a, DAY_COLUMN_DP), metrics.scaledPx(DAY_COLUMN_DP)), -2));
            r.addView(new WxIcon(a, Weather.kind(day.code), true, pal), new LinearLayout.LayoutParams(dayIcon, dayIcon));
            TextView desc = Ui.oneLine(Ui.text(a, "  " + Weather.describe(day.code), 14, pal.textHi));
            r.addView(desc, new LinearLayout.LayoutParams(0, -2, 1));
            String pop = day.pop >= 0 ? day.pop + "%  " : "";
            r.addView(Ui.text(a, pop, 13, pal.c(2)));
            r.addView(Ui.bold(a, deg(day.hi) + " / " + deg(day.lo), 15, pal.textHi));
            b.addView(r);
        }
        long t = p.l("wxTime", 0);
        String when = t > 0 ? new SimpleDateFormat(a.use24h() ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(t)).toUpperCase(Locale.getDefault()) : "";
        card.status.setText(busy ? "UPDATING…" : place + (when.isEmpty() ? "" : " • " + when));
    }

    /** Simple drawn console-palette weather pictograms (sun/moon, cloud, rain, snow, storm, fog), sized to the view. */
    static class WxIcon extends View {
        private final int kind;
        private final boolean day;
        private final Palette pal;
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        WxIcon(Context c, int kind, boolean day, Palette pal) {
            super(c);
            this.kind = kind;
            this.day = day;
            this.pal = pal;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight());
            float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
            cv.save();
            cv.translate(ox, oy);
            boolean cloud = kind != Weather.K_CLEAR;
            if (kind == Weather.K_CLEAR || kind == Weather.K_PARTLY) {
                float cx = kind == Weather.K_CLEAR ? s * 0.5f : s * 0.64f, cy = kind == Weather.K_CLEAR ? s * 0.5f : s * 0.36f;
                float rad = kind == Weather.K_CLEAR ? s * 0.22f : s * 0.17f;
                pt.setColor(pal.c(4));
                if (day) {
                    cv.drawCircle(cx, cy, rad, pt);
                    pt.setStrokeWidth(s * 0.06f);
                    pt.setStrokeCap(Paint.Cap.ROUND);
                    for (int i = 0; i < 8; i++) {
                        double ang = i * Math.PI / 4;
                        cv.drawLine(cx + (float) Math.cos(ang) * rad * 1.45f, cy + (float) Math.sin(ang) * rad * 1.45f,
                                cx + (float) Math.cos(ang) * rad * 1.9f, cy + (float) Math.sin(ang) * rad * 1.9f, pt);
                    }
                } else {
                    pt.setColor(pal.c(1));
                    cv.drawCircle(cx, cy, rad * 1.2f, pt);
                    pt.setColor(Skin.bg());
                    cv.drawCircle(cx + rad * 0.55f, cy - rad * 0.35f, rad * 1.0f, pt);
                }
            }
            if (cloud) {
                float top = kind == Weather.K_PARTLY ? s * 0.42f : s * 0.22f;
                pt.setColor(kind == Weather.K_STORM ? Ui.darken(pal.c(1), 0.75f) : pal.c(1));
                float h = s * 0.32f;
                cv.drawCircle(s * 0.36f, top + h * 0.55f, h * 0.45f, pt);
                cv.drawCircle(s * 0.58f, top + h * 0.38f, h * 0.6f, pt);
                r.set(s * 0.14f, top + h * 0.5f, s * 0.86f, top + h * 1.05f);
                cv.drawRoundRect(r, h * 0.28f, h * 0.28f, pt);
                float base = top + h * 1.2f;
                pt.setStrokeCap(Paint.Cap.ROUND);
                pt.setStrokeWidth(s * 0.06f);
                if (kind == Weather.K_RAIN) {
                    pt.setColor(pal.c(2));
                    for (int i = 0; i < 3; i++) cv.drawLine(s * (0.3f + i * 0.2f), base + s * 0.04f, s * (0.25f + i * 0.2f), base + s * 0.2f, pt);
                } else if (kind == Weather.K_SNOW) {
                    pt.setColor(0xFFFFFFFF);
                    for (int i = 0; i < 3; i++) cv.drawCircle(s * (0.3f + i * 0.2f), base + s * 0.12f, s * 0.045f, pt);
                } else if (kind == Weather.K_STORM) {
                    pt.setColor(pal.c(4));
                    path.reset();
                    path.moveTo(s * 0.52f, base - s * 0.02f);
                    path.lineTo(s * 0.38f, base + s * 0.16f);
                    path.lineTo(s * 0.5f, base + s * 0.16f);
                    path.lineTo(s * 0.42f, base + s * 0.32f);
                    path.lineTo(s * 0.64f, base + s * 0.1f);
                    path.lineTo(s * 0.52f, base + s * 0.1f);
                    path.close();
                    cv.drawPath(path, pt);
                } else if (kind == Weather.K_FOG) {
                    pt.setColor(pal.c(2));
                    for (int i = 0; i < 2; i++) cv.drawLine(s * 0.18f, base + s * (0.06f + i * 0.12f), s * 0.82f, base + s * (0.06f + i * 0.12f), pt);
                }
            }
            cv.restore();
        }
    }
}
