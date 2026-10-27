package com.applikationsprogramvara.osmviewer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Own pins on the map: set with a long press on the map or by entering coordinates, named, renamed and
 * deleted by tapping them. Stored in the app settings ("pins", JSON).
 */
public class PinManager {

    public static class Pin {
        public double lat, lon;
        public String name;

        Pin(double lat, double lon, String name) {
            this.lat = lat;
            this.lon = lon;
            this.name = name == null ? "" : name;
        }

        public GeoPoint point() {
            return new GeoPoint(lat, lon);
        }

        /** name, or the coordinates if it has none */
        public String label() {
            return name.isEmpty() ? formatCoordinates(lat, lon) : name;
        }
    }

    public interface JumpCallback {
        void jump(GeoPoint point);
    }

    private static final String PREF = "pins";

    private final SharedPreferences prefs;
    private final MapView map;
    private final JumpCallback jump;
    private final List<Pin> pins = new ArrayList<>();
    private final PinOverlay overlay;

    public PinManager(SharedPreferences prefs, MapView map, JumpCallback jump) {
        this.prefs = prefs;
        this.map = map;
        this.jump = jump;
        load();
        overlay = new PinOverlay();
    }

    public Overlay getOverlay() {
        return overlay;
    }

    public List<Pin> getPins() {
        return pins;
    }

    private void load() {
        pins.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                pins.add(new Pin(o.getDouble("lat"), o.getDouble("lon"), o.optString("name", "")));
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Pin p : pins) {
                JSONObject o = new JSONObject();
                o.put("lat", p.lat);
                o.put("lon", p.lon);
                o.put("name", p.name);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(PREF, a.toString()).apply();
        map.invalidate();
    }

    // ------------------------------------------------------------------ dialogs

    /** New pin at the given point (long press) or, with null, at entered coordinates. */
    public void showNewPinDialog(Context context, @Nullable GeoPoint at) {
        showDialog(context, null, at);
    }

    /** Rename, move (coordinates) or delete a pin. */
    public void showEditDialog(Context context, Pin pin) {
        showDialog(context, pin, null);
    }

    private void showDialog(Context context, @Nullable Pin pin, @Nullable GeoPoint at) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (22 * dp), (int) (8 * dp), (int) (22 * dp), 0);

        EditText name = new EditText(context);
        name.setSingleLine(true);
        name.setHint(R.string.pin_name_hint);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (pin != null) name.setText(pin.name);
        box.addView(name);

        TextView coordLabel = new TextView(context);
        coordLabel.setText(R.string.pin_coordinates_label);
        coordLabel.setAlpha(0.7f);
        coordLabel.setPadding(0, (int) (12 * dp), 0, 0);
        box.addView(coordLabel);

        EditText coords = new EditText(context);
        coords.setSingleLine(true);
        coords.setHint(R.string.pin_coordinates_hint);
        coords.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (pin != null) coords.setText(formatCoordinates(pin.lat, pin.lon));
        else if (at != null) coords.setText(formatCoordinates(at.getLatitude(), at.getLongitude()));
        box.addView(coords);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(pin == null ? R.string.pin_new_title : R.string.pin_edit_title)
                .setView(box)
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_cancel, null);
        if (pin != null)
            builder.setNeutralButton(R.string.btn_delete, (d, w) -> {
                pins.remove(pin);
                save();
            });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            (pin == null && at == null ? coords : name).requestFocus();
            if (dialog.getWindow() != null)
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            // checks the coordinates before closing
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                GeoPoint p = parseCoordinates(coords.getText().toString());
                if (p == null) {
                    Toast.makeText(context, R.string.pin_coordinates_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                String n = name.getText().toString().trim();
                boolean moved;
                if (pin == null) {
                    pins.add(new Pin(p.getLatitude(), p.getLongitude(), n));
                    moved = at == null;
                } else {
                    moved = Math.abs(pin.lat - p.getLatitude()) > 1e-7 || Math.abs(pin.lon - p.getLongitude()) > 1e-7;
                    pin.lat = p.getLatitude();
                    pin.lon = p.getLongitude();
                    pin.name = n;
                }
                save();
                dialog.dismiss();
                if (moved) jump.jump(p); // entered coordinates: show the pin
            });
        });
        dialog.show();
    }

    // ------------------------------------------------------------------ coordinates

    public static String formatCoordinates(double lat, double lon) {
        return String.format(Locale.ROOT, "%.6f, %.6f", lat, lon);
    }

    private static final Pattern PART = Pattern.compile(
            "([NSEWO])?\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(?:°\\s*(?:(\\d+(?:\\.\\d+)?)\\s*'\\s*)?(?:(\\d+(?:\\.\\d+)?)\\s*\"\\s*)?)?\\s*([NSEWO])?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Reads coordinates as decimal degrees ("-42.9624, 146.7279", "-42.9624 146.7279", "42.9624 S 146.7279 E")
     * or degrees, minutes, seconds ("42°57'44.8\"S 146°43'40.6\"E", also with minutes only), also a geo: link.
     * Latitude first, unless the letters say otherwise. Null if not readable.
     */
    @Nullable
    public static GeoPoint parseCoordinates(String text) {
        if (text == null) return null;
        String s = text.trim()
                .replace('′', '\'').replace('’', '\'').replace('´', '\'')
                .replace('″', '"').replace('”', '"').replace("''", "\"")
                .replace('º', '°').replace('˚', '°');
        if (s.toLowerCase(Locale.ROOT).startsWith("geo:")) s = s.substring(4).split("[?;]")[0];
        // decimal commas ("42,9624 146,7279") only without other commas between the numbers
        if (!s.contains(".") && s.matches(".*\\d,\\d.*") && !s.matches(".*\\d,\\s+\\D?.*\\d.*"))
            s = s.replaceAll("(\\d),(\\d)", "$1.$2");
        List<Double> values = new ArrayList<>();
        List<Character> hemis = new ArrayList<>();
        Matcher m = PART.matcher(s);
        while (m.find() && values.size() < 3) {
            if (m.group(2) == null) continue;
            double v = Double.parseDouble(m.group(2));
            double sign = v < 0 || m.group(2).startsWith("-") ? -1 : 1;
            v = Math.abs(v);
            if (m.group(3) != null) v += Double.parseDouble(m.group(3)) / 60;
            if (m.group(4) != null) v += Double.parseDouble(m.group(4)) / 3600;
            String h = m.group(1) != null ? m.group(1) : m.group(5);
            char hc = h == null ? 0 : Character.toUpperCase(h.charAt(0));
            if (hc == 'S' || hc == 'W') sign = -1;
            values.add(sign * v);
            hemis.add(hc);
        }
        if (values.size() != 2) return null;
        double lat = values.get(0), lon = values.get(1);
        char h0 = hemis.get(0);
        if (h0 == 'E' || h0 == 'W' || h0 == 'O') { // longitude given first
            lat = values.get(1);
            lon = values.get(0);
        }
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new GeoPoint(lat, lon);
    }

    // ------------------------------------------------------------------ overlay

    private class PinOverlay extends Overlay {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Point pixel = new Point();
        private final Path path = new Path();
        private final float dp;

        PinOverlay() {
            dp = map.getContext().getResources().getDisplayMetrics().density;
            fill.setColor(Color.rgb(220, 30, 60));
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * dp);
            inner.setColor(Color.WHITE);
            text.setColor(Color.rgb(150, 0, 30));
            text.setTextSize(14 * dp);
            text.setFakeBoldText(true);
            halo.set(text);
            halo.setColor(Color.WHITE);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(3.5f * dp);
        }

        @Override
        public void draw(Canvas c, Projection pj) {
            float r = 8 * dp, h = 24 * dp;
            for (Pin p : pins) {
                pj.toPixels(p.point(), pixel);
                float x = pixel.x, y = pixel.y, cy = y - h + r;
                path.reset();
                path.moveTo(x, y);
                path.lineTo(x - r * 0.75f, cy + r * 0.6f);
                path.addCircle(x, cy, r, Path.Direction.CW);
                path.moveTo(x, y);
                path.lineTo(x + r * 0.75f, cy + r * 0.6f);
                path.lineTo(x - r * 0.75f, cy + r * 0.6f);
                path.close();
                c.drawPath(path, fill);
                c.drawCircle(x, cy, r, stroke);
                c.drawCircle(x, cy, r * 0.38f, inner);
                if (!p.name.isEmpty()) {
                    float tx = x + r + 3 * dp, ty = cy + text.getTextSize() * 0.35f;
                    c.drawText(p.name, tx, ty, halo);
                    c.drawText(p.name, tx, ty, text);
                }
            }
        }

        @Nullable
        private Pin hit(MotionEvent e, MapView mapView) {
            Projection pj = mapView.getProjection();
            float best = 26 * dp;
            Pin found = null;
            for (Pin p : pins) {
                pj.toPixels(p.point(), pixel);
                float dx = e.getX() - pixel.x, dy = e.getY() - (pixel.y - 14 * dp);
                float d = (float) Math.hypot(dx, dy);
                if (d < best) {
                    best = d;
                    found = p;
                }
            }
            return found;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {
            Pin p = hit(e, mapView);
            if (p == null) return false;
            showEditDialog(mapView.getContext(), p);
            return true;
        }

        @Override
        public boolean onLongPress(MotionEvent e, MapView mapView) {
            Pin p = hit(e, mapView);
            if (p != null) {
                showEditDialog(mapView.getContext(), p);
                return true;
            }
            IGeoPoint g = mapView.getProjection().fromPixels((int) e.getX(), (int) e.getY());
            showNewPinDialog(mapView.getContext(), new GeoPoint(g.getLatitude(), g.getLongitude()));
            return true;
        }
    }
}
