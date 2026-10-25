package com.applikationsprogramvara.osmviewer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.List;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.tileprovider.tilesource.XYTileSource;

public class MapsCatalog {

    private static final OnlineTileSourceBase HTTP_OPENTOPOMAP = new XYTileSource("OpenTopoMap http",
            0, 17, 256, ".png", new String[]{
                    "http://a.tile.opentopomap.org/",
                    "http://b.tile.opentopomap.org/",
                    "http://c.tile.opentopomap.org/"},
            "Kartendaten: © OpenStreetMap-Mitwirkende, SRTM | Kartendarstellung: © OpenTopoMap (CC-BY-SA)");

    // OpenCycleMap needs a (free) Thunderforest API key, entered in the settings
    static final OnlineTileSourceBase OPENCYCLEMAP = new ThunderforestTileSource("OpenCycleMap", "cycle");

    // CyclOSM: free cycling map without key
    private static final OnlineTileSourceBase CYCLOSM = new XYTileSource("CyclOSM",
            0, 20, 256, ".png", new String[] {
                    "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
                    "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
                    "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/" },
            "© CyclOSM, Daten © OpenStreetMap-Mitwirkende");

    // OpenStreetMap's own tile servers block this app, so they are not offered.
    // First category = default: OpenTopoMap
    private static final OnlineTileSourceBase[][] MAP_SOURCES = new OnlineTileSourceBase[][] {
            {
                TileSourceFactory.OpenTopo,
                HTTP_OPENTOPOMAP,
            }, {
                OPENCYCLEMAP,
                CYCLOSM,
            }
    };

    private static final int[] MAP_ICONS = new int[] {
            R.drawable.ic_map_topo,
            R.drawable.ic_map_usual
    };

    public static final OnlineTileSourceBase WAY_MARKED_TRAILS_HIKING = new XYTileSource("WayMarkedTrails Hiking",
            0, 18, 256, ".png",
            new String[] { "https://tile.waymarkedtrails.org/hiking/" });

    public static final OnlineTileSourceBase WAY_MARKED_TRAILS_HIKING_HTTP = new XYTileSource("WayMarkedTrails Hiking http",
            0, 18, 256, ".png",
            new String[] { "https://tile.waymarkedtrails.org/hiking/" });

    public static final OnlineTileSourceBase WAY_MARKED_TRAILS_CYCLING = new XYTileSource("WayMarkedTrails Cycling",
            0, 18, 256, ".png",
            new String[] { "https://tile.waymarkedtrails.org/cycling/" });

    public static final OnlineTileSourceBase WAY_MARKED_TRAILS_CYCLING_HTTP = new XYTileSource("WayMarkedTrails Cycling http",
            0, 18, 256, ".png",
            new String[] { "https://tile.waymarkedtrails.org/cycling/" });

    public static final OnlineTileSourceBase[] OVERLAY_SOURCES = new OnlineTileSourceBase[] {
            WAY_MARKED_TRAILS_HIKING,
            WAY_MARKED_TRAILS_HIKING_HTTP,
            WAY_MARKED_TRAILS_CYCLING,
            WAY_MARKED_TRAILS_CYCLING_HTTP,
    };

    /** Category index used for offline map files (after the online categories). */
    public static final int OFFLINE_INDEX = MAP_SOURCES.length;
    private static final String PREF_OFFLINE_FILE = "offlineMapFile";
    private static final String PREF_OVERLAY_FILE = "offlineOverlayFile";

    private final SharedPreferences prefs;
    private final CallbackSetMapSource setMap;
    private final CallbackSetOverlaySource setOverlay;
    private int mapIndex1;
    private int mapIndex2;
    private int overlayIndex;

    @Nullable private OfflineMapManager offline;
    @Nullable private CallbackSetOfflineMap setOfflineMap;
    @Nullable private Runnable requestImport;
    @Nullable private CallbackSetOfflineOverlay setOfflineOverlay;

    public interface CallbackSetOfflineOverlay {
        void set(@Nullable File file);
    }

    /** Enables offline overlays (transparent MBTiles over the map), chosen in the map menu. */
    public void setOfflineOverlayCallback(CallbackSetOfflineOverlay callback) {
        this.setOfflineOverlay = callback;
    }

    private void loadOfflineOverlay() {
        if (offline == null || setOfflineOverlay == null) return;
        File f = offline.find(prefs.getString(PREF_OVERLAY_FILE, null));
        setOfflineOverlay.set(f != null && OfflineMapManager.isOverlay(f) ? f : null);
    }

    /** Shows the given overlay file (null = none) and remembers it. */
    public void selectOfflineOverlay(@Nullable File file) {
        if (file == null) prefs.edit().remove(PREF_OVERLAY_FILE).apply();
        else prefs.edit().putString(PREF_OVERLAY_FILE, file.getName()).apply();
        if (setOfflineOverlay != null) setOfflineOverlay.set(file);
    }

    public MapsCatalog(SharedPreferences prefs, @Nullable CallbackSetMapSource setMap, @Nullable CallbackSetOverlaySource setOverlay) {
        this.prefs = prefs;
        this.setMap = setMap;
        this.setOverlay = setOverlay;
    }

    /** Enables the offline category. Without this call the catalog behaves as before (e.g. in settings). */
    public void setOffline(OfflineMapManager offline, CallbackSetOfflineMap setOfflineMap, Runnable requestImport) {
        this.offline = offline;
        this.setOfflineMap = setOfflineMap;
        this.requestImport = requestImport;
    }

    public interface CallbackSetOfflineMap {
        void set(File file, boolean jumpToMap);
    }

    private int categoryCount() {
        return MAP_SOURCES.length + (offline != null && offline.hasMaps() ? 1 : 0);
    }

    public void load() {
        mapIndex1 = prefs.getInt("mapSourceIndex", 0);
        load(mapIndex1);
        loadOverlay();
        loadOfflineOverlay();
    }

    public void load(int index1) {
        if (index1 == OFFLINE_INDEX) {
            if (loadOffline())
                return;
            // offline map not available (deleted, storage removed, settings screen): fall back to first category
            index1 = 0;
            mapIndex1 = 0;
        }
        mapIndex2 = prefs.getInt("mapSourceIndex2_" + index1, 0);
        mapSetSource(index1);
    }

    private boolean loadOffline() {
        if (offline == null || setOfflineMap == null)
            return false;
        File file = offline.find(prefs.getString(PREF_OFFLINE_FILE, null));
        if (file == null) {
            List<File> files = offline.listBase();
            if (files.isEmpty())
                return false;
            file = files.get(0);
            prefs.edit().putString(PREF_OFFLINE_FILE, file.getName()).apply();
        }
        setOfflineMap.set(file, false);
        return true;
    }

    /** Activates the given offline map file and remembers it. */
    public void selectOffline(File file, boolean jumpToMap) {
        if (setOfflineMap == null)
            return;
        mapIndex1 = OFFLINE_INDEX;
        prefs.edit()
                .putInt("mapSourceIndex", OFFLINE_INDEX)
                .putString(PREF_OFFLINE_FILE, file.getName())
                .apply();
        setOfflineMap.set(file, jumpToMap);
    }

    /** Switches back to the first online category, e.g. when the offline map cannot be opened. */
    public void fallbackToOnline() {
        mapIndex1 = 0;
        prefs.edit().putInt("mapSourceIndex", 0).apply();
        load(0);
    }

    // ------------------------------------------------------------------ map menu

    /** One selectable map: an offline file or an online source. */
    private static class MapEntry {
        final String key;          // "file:<name>" or "online:<source name>"
        final String defaultName;
        final String details;
        final File file;           // offline only
        final int index1, index2;  // online only
        boolean overlay;           // offline overlay ("overlay:none" = no overlay)

        MapEntry(String key, String defaultName, String details, File file, int index1, int index2) {
            this.key = key; this.defaultName = defaultName; this.details = details;
            this.file = file; this.index1 = index1; this.index2 = index2;
        }
    }

    private String currentKey() {
        if (mapIndex1 == OFFLINE_INDEX)
            return "file:" + prefs.getString(PREF_OFFLINE_FILE, "");
        int i1 = Math.min(mapIndex1, MAP_SOURCES.length - 1);
        int i2 = Math.min(prefs.getInt("mapSourceIndex2_" + i1, 0), MAP_SOURCES[i1].length - 1);
        return "online:" + MAP_SOURCES[i1][i2].name();
    }

    private String displayName(MapEntry e) {
        String custom = prefs.getString("mapName_" + e.key, null);
        return custom != null && !custom.trim().isEmpty() ? custom : e.defaultName;
    }

    private List<MapEntry> offlineEntries() {
        List<MapEntry> list = new java.util.ArrayList<>();
        if (offline != null)
            for (File f : offline.listBase())
                list.add(new MapEntry("file:" + f.getName(), OfflineMapManager.readTitle(f), offline.describe(f), f, -1, -1));
        return list;
    }

    private List<MapEntry> overlayEntries(Context context) {
        List<MapEntry> list = new java.util.ArrayList<>();
        if (offline == null) return list;
        List<File> files = offline.listOverlays();
        if (files.isEmpty()) return list;
        MapEntry none = new MapEntry("overlay:none", context.getString(R.string.overlay_none), "", null, -1, -1);
        none.overlay = true;
        list.add(none);
        for (File f : files) {
            MapEntry e = new MapEntry("file:" + f.getName(), OfflineMapManager.readTitle(f), offline.describe(f), f, -1, -1);
            e.overlay = true;
            list.add(e);
        }
        return list;
    }

    private String currentOverlayKey() {
        String f = prefs.getString(PREF_OVERLAY_FILE, null);
        return f == null ? "overlay:none" : "file:" + f;
    }

    private List<MapEntry> onlineEntries() {
        List<MapEntry> list = new java.util.ArrayList<>();
        for (int i1 = 0; i1 < MAP_SOURCES.length; i1++)
            for (int i2 = 0; i2 < MAP_SOURCES[i1].length; i2++) {
                String name = MAP_SOURCES[i1][i2].name();
                if (name.endsWith(" http")) continue; // fallbacks for very old devices, not worth a menu entry
                list.add(new MapEntry("online:" + name, name, "online", null, i1, i2));
            }
        return list;
    }

    private void select(MapEntry e) {
        if (e.overlay) {
            selectOfflineOverlay(e.file);
        } else if (e.file != null) {
            selectOffline(e.file, true);
        } else {
            mapIndex1 = e.index1;
            mapIndex2 = e.index2;
            prefs.edit()
                    .putInt("mapSourceIndex", e.index1)
                    .putInt("mapSourceIndex2_" + e.index1, e.index2)
                    .apply();
            mapSetSource(e.index1);
        }
    }

    /** Map chooser: tap a map to show it, pencil to rename (or delete an offline map), button to add a map file. */
    public void showMapMenu(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout list = new android.widget.LinearLayout(context);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dlg_select_map_title)
                .setView(scroll)
                .setNeutralButton(R.string.btn_add_map, (d, w) -> {
                    if (requestImport != null) requestImport.run();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .create();

        String current = currentKey();
        List<MapEntry> offlineMaps = offlineEntries();

        addHeader(list, context.getString(R.string.header_offline_maps), dp);
        if (offlineMaps.isEmpty()) {
            android.widget.TextView hint = new android.widget.TextView(context);
            hint.setText(R.string.hint_no_offline_maps);
            hint.setPadding((int) (24 * dp), (int) (8 * dp), (int) (24 * dp), (int) (12 * dp));
            hint.setAlpha(0.7f);
            list.addView(hint);
        }
        for (MapEntry e : offlineMaps)
            addRow(list, dialog, e, e.key.equals(current), dp);

        List<MapEntry> overlays = overlayEntries(context);
        if (!overlays.isEmpty()) {
            addHeader(list, context.getString(R.string.header_overlays), dp);
            String currentOverlay = currentOverlayKey();
            for (MapEntry e : overlays)
                addRow(list, dialog, e, e.key.equals(currentOverlay), dp);
        }

        if (offline != null) {
            addHeader(list, context.getString(R.string.header_online_maps), dp);
            for (MapEntry e : onlineEntries())
                addRow(list, dialog, e, e.key.equals(current), dp);
        }

        dialog.show();
    }

    private void addHeader(android.widget.LinearLayout list, String text, float dp) {
        android.widget.TextView header = new android.widget.TextView(list.getContext());
        header.setText(text.toUpperCase(java.util.Locale.getDefault()));
        header.setTextSize(13);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setAlpha(0.6f);
        header.setPadding((int) (24 * dp), (int) (14 * dp), (int) (24 * dp), (int) (4 * dp));
        list.addView(header);
    }

    private void addRow(android.widget.LinearLayout list, AlertDialog dialog, MapEntry e, boolean selected, float dp) {
        Context context = list.getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (16 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
        row.setMinimumHeight((int) (56 * dp));
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        android.widget.RadioButton radio = new android.widget.RadioButton(context);
        radio.setChecked(selected);
        radio.setClickable(false);
        radio.setFocusable(false);
        row.addView(radio);

        android.widget.LinearLayout texts = new android.widget.LinearLayout(context);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        texts.setPadding((int) (8 * dp), 0, (int) (8 * dp), 0);
        android.widget.TextView name = new android.widget.TextView(context);
        name.setText(displayName(e));
        name.setTextSize(18);
        texts.addView(name);
        android.widget.TextView details = new android.widget.TextView(context);
        details.setText(e.details);
        details.setTextSize(13);
        details.setAlpha(0.6f);
        texts.addView(details);
        row.addView(texts, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageButton edit = new android.widget.ImageButton(context);
        edit.setImageResource(R.drawable.ic_edit);
        edit.setContentDescription(context.getString(R.string.dlg_rename_title));
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        edit.setBackgroundResource(tv.resourceId);
        edit.setColorFilter(name.getCurrentTextColor());
        edit.setAlpha(0.7f);
        int pad = (int) (12 * dp);
        edit.setPadding(pad, pad, pad, pad);
        edit.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(context, e);
        });
        if (!"overlay:none".equals(e.key))
            row.addView(edit);

        row.setOnClickListener(v -> {
            dialog.dismiss();
            select(e);
        });
        list.addView(row);
    }

    private void showRenameDialog(Context context, MapEntry e) {
        float dp = context.getResources().getDisplayMetrics().density;
        android.widget.EditText input = new android.widget.EditText(context);
        input.setSingleLine(true);
        input.setText(displayName(e));
        input.setSelectAllOnFocus(true);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), 0);
        frame.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.dlg_rename_title)
                .setView(frame)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty() || text.equals(e.defaultName))
                        prefs.edit().remove("mapName_" + e.key).apply();
                    else
                        prefs.edit().putString("mapName_" + e.key, text).apply();
                    showMapMenu(context);
                })
                .setNegativeButton(R.string.btn_cancel, (d, w) -> showMapMenu(context));
        if (e.file != null)
            builder.setNeutralButton(R.string.btn_delete, (d, w) -> confirmDelete(context, e));
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            if (dialog.getWindow() != null)
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        dialog.show();
    }

    private void confirmDelete(Context context, MapEntry e) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.dlg_offline_delete_title)
                .setMessage(context.getString(R.string.dlg_offline_delete_confirm, displayName(e)))
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    // close the archive first if this map is currently shown
                    if (mapIndex1 == OFFLINE_INDEX && e.file.getName().equals(prefs.getString(PREF_OFFLINE_FILE, "")))
                        fallbackToOnline();
                    if (e.file.getName().equals(prefs.getString(PREF_OVERLAY_FILE, "")))
                        selectOfflineOverlay(null);
                    for (File part : offline != null ? offline.parts(e.file) : java.util.Collections.singletonList(e.file))
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                    prefs.edit().remove("mapName_" + e.key).apply();
                    showMapMenu(context);
                })
                .setNegativeButton(R.string.btn_cancel, (d, w) -> showMapMenu(context))
                .show();
    }

    public void loadOverlay() {
        overlayIndex = prefs.getInt("overlaySourceIndex", 0);
        setOverlay(prefs.getBoolean("OverlayDisplayed", false));
    }

    public void nextMap() {
        mapIndex1++;
        if (mapIndex1 >= categoryCount())
            mapIndex1 = 0;

        prefs.edit().putInt("mapSourceIndex", mapIndex1).apply();
        load(mapIndex1);
    }

    public void selectSource(Context context) {
        if (mapIndex1 == OFFLINE_INDEX && offline != null) {
            showMapMenu(context);
            return;
        }

        if (mapIndex1 >= MAP_SOURCES.length)
            mapIndex1 = 0;

        selectSource(context, mapIndex1);
    }

    public void selectSource(Context context, int index1) {
        MenuUtils.MenuBuilder menu = new MenuUtils.MenuBuilder(context)
                .setTitle(R.string.dlg_select_map_source_title);

        for (int i = 0; i < MAP_SOURCES[index1].length; i++) {
            int i2 = i;
            menu.add(MAP_SOURCES[index1][i].toString(), () -> {
                mapIndex2 = i2;
                prefs.edit().putInt("mapSourceIndex2_" + index1, mapIndex2).apply();
                mapSetSource(index1);
            });
        }

        menu.show2(mapIndex2);
    }

    private void mapSetSource(int index1) {
        // setting min/max zooms is not implemented inside mapview. pros: user can zoom in further with "pixel effect", cons: user is not aware of zoom limits
//        map.setMinZoomLevel((double) mapSource[mapSourceIndex].getMinimumZoomLevel());
//        map.setMaxZoomLevel((double) mapSource[mapSourceIndex].getMaximumZoomLevel()); // also setting lower max zoom cause harsh zoom out w/o animation

        if (index1 >= MAP_SOURCES.length)
            index1 = 0;
        if (mapIndex2 >= MAP_SOURCES[index1].length)
            mapIndex2 = 0;

        if (setMap != null)
            setMap.set(
                    MAP_SOURCES[index1][mapIndex2],
                    MapsCatalog.MAP_ICONS[index1],
                    index1
            );

    }

    public interface CallbackSetMapSource {
        void set(ITileSource source, int image, int index);
    }

    public void setOverlay(boolean show) {
        if (overlayIndex >= OVERLAY_SOURCES.length)
            overlayIndex = 0;

        if (setOverlay != null)
            setOverlay.set(OVERLAY_SOURCES[overlayIndex], show);

        prefs.edit().putBoolean("OverlayDisplayed", show).apply();
    }

    /**
     * Long press on the overlay button: offline overlays (own MBTiles) first, then the online overlays.
     * One overlay at a time: choosing one switches the other kind off.
     */
    public void selectOverlay(Context context) {
        MenuUtils.MenuBuilder menu = new MenuUtils.MenuBuilder(context)
                .setTitle(R.string.dlg_select_overlay_source_title);
        int checked = -1, item = 0;

        List<File> files = offline != null ? offline.listOverlays() : java.util.Collections.emptyList();
        String activeFile = prefs.getString(PREF_OVERLAY_FILE, null);
        boolean onlineShown = prefs.getBoolean("OverlayDisplayed", false);

        menu.add(context.getString(R.string.overlay_none), () -> {
            selectOfflineOverlay(null);
            setOverlay(false);
        });
        if (activeFile == null && !onlineShown) checked = item;
        item++;

        for (File f : files) {
            menu.add(OfflineMapManager.readTitle(f) + " " + context.getString(R.string.overlay_offline_suffix), () -> {
                setOverlay(false);
                selectOfflineOverlay(f);
            });
            if (f.getName().equals(activeFile)) checked = item;
            item++;
        }

        for (int i = 0; i < OVERLAY_SOURCES.length; i++) {
            int i2 = i;
            menu.add(OVERLAY_SOURCES[i].toString() + " " + context.getString(R.string.overlay_online_suffix), () -> {
                selectOfflineOverlay(null);
                overlayIndex = i2;
                prefs.edit().putInt("overlaySourceIndex", overlayIndex).apply();
                setOverlay(true);
            });
            if (checked < 0 && onlineShown && i == overlayIndex) checked = item;
            item++;
        }

        menu.show2(checked);
    }

    public interface CallbackSetOverlaySource {
        void set(ITileSource source, boolean show);
    }


}
