package com.applikationsprogramvara.osmviewer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.util.Xml;

import androidx.annotation.Nullable;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A GPX file (tracks, routes, waypoints) drawn as an overlay: tracks and routes as violet lines with a white
 * edge, waypoints as small dots with their names.
 */
public class GpxOverlay extends Overlay {

    private static final int LINE_COLOR = Color.rgb(138, 43, 226);

    /** content of a GPX file */
    public static class Gpx {
        @Nullable public String name;
        public final List<List<GeoPoint>> lines = new ArrayList<>();
        public final List<GeoPoint> waypoints = new ArrayList<>();
        public final List<String> waypointNames = new ArrayList<>();

        @Nullable
        public BoundingBox bounds() {
            List<GeoPoint> all = new ArrayList<>(waypoints);
            for (List<GeoPoint> l : lines) all.addAll(l);
            return all.isEmpty() ? null : BoundingBox.fromGeoPoints(all);
        }
    }

    /** Reads tracks (trk/trkseg/trkpt), routes (rte/rtept) and waypoints (wpt) of a GPX file. */
    public static Gpx read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return read(in);
        }
    }

    public static Gpx read(InputStream in) throws IOException {
        Gpx gpx = new Gpx();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, null);
            List<GeoPoint> line = null;
            GeoPoint wpt = null;
            String wptName = null;
            String parent = ""; // trk, rte, wpt or metadata: whose <name> is read
            boolean sawGpx = false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = local(p.getName());
                    switch (tag) {
                        case "gpx": sawGpx = true; break;
                        case "metadata": case "trk": parent = tag; break;
                        case "rte": parent = tag; line = new ArrayList<>(); break;
                        case "trkseg": line = new ArrayList<>(); break;
                        case "trkpt": case "rtept": {
                            GeoPoint g = point(p);
                            if (g != null && line != null) line.add(g);
                            break;
                        }
                        case "wpt":
                            wpt = point(p);
                            wptName = null;
                            parent = "wpt";
                            break;
                        case "name": {
                            String text = p.nextText();
                            text = text == null ? null : text.trim();
                            if ("wpt".equals(parent)) wptName = text;
                            else if (gpx.name == null && text != null && !text.isEmpty()
                                    && ("metadata".equals(parent) || "trk".equals(parent) || "rte".equals(parent)))
                                gpx.name = text;
                            break;
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = local(p.getName());
                    switch (tag) {
                        case "trkseg": case "rte":
                            if (line != null && line.size() >= 2) gpx.lines.add(line);
                            line = null;
                            if ("rte".equals(tag)) parent = "";
                            break;
                        case "trk": case "metadata": parent = ""; break;
                        case "wpt":
                            if (wpt != null) {
                                gpx.waypoints.add(wpt);
                                gpx.waypointNames.add(wptName);
                            }
                            wpt = null;
                            parent = "";
                            break;
                    }
                }
            }
            if (!sawGpx) throw new IOException("keine GPX-Datei");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("GPX-Datei kann nicht gelesen werden: " + e.getMessage());
        }
        if (gpx.lines.isEmpty() && gpx.waypoints.isEmpty())
            throw new IOException("Die GPX-Datei enthält keine Tracks, Routen oder Wegpunkte");
        return gpx;
    }

    private static String local(String name) {
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    @Nullable
    private static GeoPoint point(XmlPullParser p) {
        try {
            double lat = Double.parseDouble(p.getAttributeValue(null, "lat"));
            double lon = Double.parseDouble(p.getAttributeValue(null, "lon"));
            return new GeoPoint(lat, lon);
        } catch (Exception e) {
            return null;
        }
    }

    /** Name in the GPX file (metadata, first track or route), null if there is none. Reads only the start. */
    @Nullable
    public static String readName(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n = in.read(buf);
            if (n <= 0) return null;
            String head = new String(buf, 0, n, "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<(?:\\w+:)?(?:metadata|trk|rte)\\b[^>]*>\\s*(?:<[^>]*>\\s*)*?<(?:\\w+:)?name>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+)")
                    .matcher(head);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing

    private final Gpx gpx;
    private final List<Polyline> edges = new ArrayList<>();
    private final List<Polyline> lines = new ArrayList<>();
    private final Paint dotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final Point pixel = new Point();

    public GpxOverlay(Gpx gpx, MapView map) {
        this.gpx = gpx;
        density = map.getContext().getResources().getDisplayMetrics().density;
        for (List<GeoPoint> pts : gpx.lines) {
            Polyline edge = new Polyline(map);
            edge.setPoints(pts);
            edge.getOutlinePaint().setColor(Color.WHITE);
            edge.getOutlinePaint().setStrokeWidth(6.5f * density);
            edge.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
            edge.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
            edge.setInfoWindow(null);
            edges.add(edge);
            Polyline line = new Polyline(map);
            line.setPoints(pts);
            line.getOutlinePaint().setColor(LINE_COLOR);
            line.getOutlinePaint().setStrokeWidth(4f * density);
            line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
            line.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
            line.setInfoWindow(null);
            lines.add(line);
        }
        dotFill.setColor(LINE_COLOR);
        dotStroke.setColor(Color.WHITE);
        dotStroke.setStyle(Paint.Style.STROKE);
        dotStroke.setStrokeWidth(2f * density);
        label.setColor(Color.rgb(70, 20, 120));
        label.setTextSize(13f * density);
        label.setFakeBoldText(true);
        labelHalo.set(label);
        labelHalo.setColor(Color.WHITE);
        labelHalo.setStyle(Paint.Style.STROKE);
        labelHalo.setStrokeWidth(3f * density);
    }

    public Gpx getGpx() {
        return gpx;
    }

    /** 0..1, from the setting "transparency of overlays" */
    public void setOpacity(float opacity) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, opacity)));
        for (Polyline p : edges) p.getOutlinePaint().setAlpha(a);
        for (Polyline p : lines) p.getOutlinePaint().setAlpha(a);
        dotFill.setAlpha(a);
        dotStroke.setAlpha(a);
        label.setAlpha(a);
        labelHalo.setAlpha(a);
    }

    @Override
    public void draw(Canvas c, Projection pj) {
        for (Polyline p : edges) p.draw(c, pj);
        for (Polyline p : lines) p.draw(c, pj);
        float r = 4.5f * density;
        for (int i = 0; i < gpx.waypoints.size(); i++) {
            pj.toPixels(gpx.waypoints.get(i), pixel);
            c.drawCircle(pixel.x, pixel.y, r, dotFill);
            c.drawCircle(pixel.x, pixel.y, r, dotStroke);
            String name = gpx.waypointNames.get(i);
            if (name != null && !name.isEmpty() && pj.getZoomLevel() >= 12) {
                float x = pixel.x + r + 3 * density, y = pixel.y + label.getTextSize() * 0.35f;
                c.drawText(name, x, y, labelHalo);
                c.drawText(name, x, y, label);
            }
        }
    }

    @Override
    public void onDetach(MapView mapView) {
        for (Polyline p : edges) p.onDetach(mapView);
        for (Polyline p : lines) p.onDetach(mapView);
        super.onDetach(mapView);
    }
}
