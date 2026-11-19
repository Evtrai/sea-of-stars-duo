package com.evtrai.sosduo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;

/**
 * Bottom screen of the Thor Edition. Everything is drawn on a 413x360 logical canvas (the game's own
 * UI scale: 640x360 at 3x) and blown up 3x with nearest-neighbour, using the game's fonts and sprites
 * from files/duocache (extracted from the running game by the native side). Transparent pixels show
 * the engine layer underneath: the live area view rendered by the game itself.
 */
public class DuoView extends View {
    static final int LW = 413, LH = 360, S = 3;
    // map window (logical px) and where its centre sits relative to the screen centre, in real pixels
    static final int MAP_X = 162, MAP_Y = 29, MAP_W = LW - 168, MAP_H = LH - 35;
    static final int VIEW_OX = (MAP_X + MAP_W / 2) * S - 620, VIEW_OY = (MAP_Y + 12 + MAP_H / 2) * S - 540;
    static final int NAVY = 0xE6141829, LINE = 0xFF5C6A8A, GOLD = 0xFFE9C46A, TEXT = 0xFFF1F3FA, DIM = 0xFF9AA4C2;

    static final int G_PANEL = 0xFF1F2534, G_SEL = 0xFF254A75, G_COST = 0xFF17304F, G_LABEL = 0xFF818B98, G_TEXT = 0xFFCFD3D9,
            G_EDGE = 0xFF6C7A90, G_CYAN = 0xFF8FDCFF;
    private final File cache;
    private final Bitmap frame = Bitmap.createBitmap(LW, LH, Bitmap.Config.ARGB_8888);
    private final Canvas g = new Canvas(frame);
    private final Paint blit = new Paint(), fill = new Paint(), spr = new Paint(), tintP = new Paint(), greyP = new Paint();
    private final Rect src = new Rect(), dst = new Rect();
    private final HashMap<String, Spr> sprites = new HashMap<>();
    private final HashMap<String, Long> missing = new HashMap<>();
    private Font big, small;
    private String lastJson = "";
    private JSONObject snap = new JSONObject();
    private long lastScan, lastList;
    private String[] cacheNames = new String[0];

    // ---- charted map (the game has no dungeon maps): walkable floor around the party, sampled from the level's own
    // navigation mesh by the native side every few seconds, kept per level. Cell = 0.5 world units, value = height * 2.
    static final float CELL = 0.5f;
    private final File mapDir, filesDir;
    private String mapLevel = "";
    private final HashMap<Long, Short> cells = new HashMap<>();
    private JSONArray mapObjects = new JSONArray();
    private String activity = "", objective = "";
    private boolean mapDirty; private long mapSaved, scanAsked, scanSeen, fullScanAt;
    private JSONObject wmap; private long wmSeen, wmAsked;
    private Bitmap mapBmp; private boolean mapRedraw = true; private int mapBuiltH = Integer.MIN_VALUE;
    private int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    private void loadMap(String level) {
        saveMap();
        cells.clear(); mapLevel = level; mapObjects = new JSONArray(); fullScanAt = 0; mapRedraw = true;
        minX = minZ = Integer.MAX_VALUE; maxX = maxZ = Integer.MIN_VALUE;
        File f = new File(mapDir, level + ".map");
        if (!f.exists()) return;
        try {
            ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
            while (bb.remaining() >= 10) { int cx = bb.getInt(), cz = bb.getInt(); put(cx, cz, bb.getShort(), true); }
        } catch (Exception e) { Log.w("SosDuo", "map load", e); }
        mapDirty = false;
    }

    private void put(int cx, int cz, short h, boolean overwrite) {
        Short old = cells.get(key(cx, cz));
        if (old != null && (!overwrite || old == h)) return;
        cells.put(key(cx, cz), h);
        minX = Math.min(minX, cx); maxX = Math.max(maxX, cx); minZ = Math.min(minZ, cz); maxZ = Math.max(maxZ, cz);
        mapDirty = true; mapRedraw = true;
    }

    private void saveMap() {
        if (!mapDirty || mapLevel.isEmpty()) return;
        try {
            mapDir.mkdirs();
            ByteBuffer bb = ByteBuffer.allocate(cells.size() * 10).order(ByteOrder.LITTLE_ENDIAN);
            for (java.util.Map.Entry<Long, Short> e : cells.entrySet()) { bb.putInt((int) (e.getKey() >> 32)); bb.putInt((int) (long) e.getKey()); bb.putShort(e.getValue()); }
            Files.write(new File(mapDir, mapLevel + ".map").toPath(), bb.array());
            mapDirty = false; mapSaved = System.currentTimeMillis();
        } catch (Exception e) { Log.w("SosDuo", "map save", e); }
    }

    /** Ask for a floor scan every 3 s (a full one with the level objects every 30 s) and merge the result. */
    private void chart() {
        String level = snap.optString("level");
        if (level.isEmpty() || snap.optJSONArray("pos") == null) return;
        if (!level.equals(mapLevel)) loadMap(level);
        long now = System.currentTimeMillis();
        File f = new File(filesDir, "duo_scan.json");
        if (f.exists() && f.lastModified() != scanSeen) {
            scanSeen = f.lastModified();
            try {
                JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath())));
                if (level.equals(j.optString("level"))) {
                    JSONArray p = j.optJSONArray("pos"), rows = j.optJSONArray("rows");
                    float step = (float) j.optDouble("step", 0.5); int half = j.optInt("half");
                    float px = (float) p.optDouble(0), py = (float) p.optDouble(1), pz = (float) p.optDouble(2);
                    int baseH = Math.round(py * 2);
                    for (int r = 0; rows != null && r < rows.length(); r++) {
                        String row = rows.optString(r); int iz = half - r;
                        for (int c = 0; c < row.length(); c++) {
                            char ch = row.charAt(c);
                            if (ch == '.') continue;
                            int cx = Math.round((px + (c - half) * step) / CELL), cz = Math.round((pz + iz * step) / CELL);
                            put(cx, cz, (short) (baseH + (ch - 'M')), true);
                        }
                    }
                    if (j.has("objects")) mapObjects = j.optJSONArray("objects");
                    activity = j.optString("activity");
                    JSONArray ob = j.optJSONArray("objectives");
                    objective = ob != null && ob.length() > 0 ? ob.optString(0) : "";
                }
            } catch (Exception e) { Log.w("SosDuo", "scan read", e); }
        }
        JSONArray wp = snap.optJSONArray("pos");
        int wx = Math.round((float) wp.optDouble(0) / CELL), wz = Math.round((float) wp.optDouble(2) / CELL);
        short wh = (short) Math.round((float) wp.optDouble(1) * 2);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (dx * dx + dz * dz <= 10) put(wx + dx, wz + dz, wh, false);
        if (now - scanAsked > 3000) {
            scanAsked = now;
            boolean full = now - fullScanAt > 30000;
            if (full) fullScanAt = now;
            try { HostActivity.nativeAction(full ? "scan:level 16" : "scan:nav 16"); } catch (Throwable ignored) { }
        }
        if (mapDirty && now - mapSaved > 20000) saveMap();
    }

    private boolean nearCharted(int cx, int cz) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) if (cells.containsKey(key(cx + dx, cz + dz))) return true;
        return false;
    }
    private boolean floorAt(int x, int z, int h) { Short v = cells.get(key(x, z)); return v != null && Math.abs(v - h) <= 4; }

    /** Parchment panel: charted floor centred on the party (1 px per cell), current storey inked, other storeys faint. */
    private void drawMap(int x, int y, int w, int h) {
        Spr paper = sprite("panel-texture-background");
        if (paper != null) nine(paper, x, y, w, h, 0); else rect(x, y, w, h, 0xFFE4D6B0);
        String area = snap.optString("area");
        big.draw(area, x + 10, y + 18, 0xFF5A3D1E);
        Spr ul = sprite("map-name-underline");
        if (ul != null) drawPart(ul, 0, 0, ul.b.getWidth(), ul.b.getHeight(), x + 10, y + 21, Math.max(20, big.width(area)), ul.b.getHeight(), spr);
        JSONArray pos = snap.optJSONArray("pos");
        if (pos == null || cells.isEmpty()) return;
        int ix = x + 8, iy = y + 28, iw = w - 16, ih = h - 36;
        float fx = (float) pos.optDouble(0) / CELL, fz = (float) pos.optDouble(2) / CELL;
        int ph = Math.round((float) pos.optDouble(1) * 2);
        // The whole charted level is drawn once into a bitmap (rebuilt only when it grows or the party changes storey)
        // and scrolled under the party marker, so walking stays smooth.
        if (mapBmp == null || mapRedraw || Math.abs(ph - mapBuiltH) >= 2) {
            int bw = maxX - minX + 3, bh = maxZ - minZ + 3;
            int[] px = new int[bw * bh];
            for (java.util.Map.Entry<Long, Short> e : cells.entrySet()) {
                if (Math.abs(e.getValue() - ph) > 4) continue;                      // other storeys are left off
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                boolean edge = !floorAt(cx - 1, cz, ph) || !floorAt(cx + 1, cz, ph) || !floorAt(cx, cz - 1, ph) || !floorAt(cx, cz + 1, ph);
                px[(maxZ - cz + 1) * bw + (cx - minX + 1)] = edge ? 0xFF6B4A2A : 0xFFCDB583;
            }
            mapBmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
            mapRedraw = false; mapBuiltH = ph;
        }
        float ox = fx - iw / 2f, oz = fz + ih / 2f;                 // cell at the inner panel's top-left; z grows upwards
        g.save();
        g.clipRect(ix, iy, ix + iw, iy + ih);
        g.drawBitmap(mapBmp, ix + Math.round(minX - 1 - ox), iy + Math.round(oz - (maxZ + 1)), spr);
        g.restore();
        // Rooms are separate islands in the level; the game moves the party between them through doorways and stairs.
        // Each such link is drawn as a dotted path from the doorway to where it leads, once either end has been charted.
        for (int i = 0; i < mapObjects.length(); i++) {
            JSONObject o = mapObjects.optJSONObject(i);
            JSONArray p = o.optJSONArray("p"), d = o.optJSONArray("d");
            if (p == null || d == null || !o.optBoolean("on", true)) continue;
            // only real doorways and stairs ("TEL_..."), not the level's fall-recovery teleporters
            if (!o.optString("n").startsWith("TEL_") || o.optString("n").contains("Teleporteur")) continue;
            float ax = (float) p.optDouble(0) / CELL, az = (float) p.optDouble(2) / CELL, bx2 = (float) d.optDouble(0) / CELL, bz = (float) d.optDouble(2) / CELL;
            if (!nearCharted(Math.round(ax), Math.round(az)) && !nearCharted(Math.round(bx2), Math.round(bz))) continue;
            float len = (float) Math.hypot(bx2 - ax, bz - az);
            // every doorway gets a small door mark; only short hops between neighbouring rooms get the dotted path
            // (long ones lead to other floors or far rooms and would cut straight across the map)
            int dmx = ix + Math.round(ax - ox), dmy = iy + Math.round(oz - az);
            if (nearCharted(Math.round(ax), Math.round(az)) && dmx > ix + 3 && dmx < ix + iw - 4 && dmy > iy + 3 && dmy < iy + ih - 4) {
                rect(dmx - 2, dmy - 2, 5, 5, 0xFF5A3D1E); rect(dmx - 1, dmy - 1, 3, 3, 0xFFF2C869);
            }
            if (len < 2 || len > 70) continue;
            for (float t = 0; t <= len; t += 1f) {
                if (((int) t / 2) % 2 == 1) continue;                                   // dotted
                int mx2 = ix + Math.round(ax + (bx2 - ax) * t / len - ox), my2 = iy + Math.round(oz - (az + (bz - az) * t / len));
                if (mx2 < ix || mx2 >= ix + iw - 1 || my2 < iy || my2 >= iy + ih - 1) continue;
                rect(mx2, my2, 2, 2, 0xFF8B6B45);
            }
        }
        // the level's real save points, camps and exits, once their spot has been charted
        for (int i = 0; i < mapObjects.length(); i++) {
            JSONObject o = mapObjects.optJSONObject(i);
            JSONArray p = o.optJSONArray("p");
            if (p == null || !o.optBoolean("on", true)) continue;
            int cx = Math.round((float) p.optDouble(0) / CELL), cz = Math.round((float) p.optDouble(2) / CELL);
            boolean seen = false;
            for (int dx = -4; dx <= 4 && !seen; dx++) for (int dz = -4; dz <= 4; dz++) if (cells.containsKey(key(cx + dx, cz + dz))) { seen = true; break; }
            int mx = ix + Math.round(cx - ox), my = iy + Math.round(oz - cz);
            if (!seen || mx < ix + 4 || mx > ix + iw - 4 || my < iy + 4 || my > iy + ih - 4) continue;
            String c = o.optString("c");
            if (c.equals("Savepoint")) { rect(mx - 3, my - 3, 7, 7, 0xFF1D4FA8); rect(mx - 2, my - 2, 5, 5, 0xFF8FDCFF); rect(mx - 1, my - 1, 3, 3, 0xFFFFFFFF); }
            else if (c.equals("CampingSpot")) {
                Spr fire = sprite("btn-mobile-icon-43px-firecamp");
                if (fire != null) drawPart(fire, 0, 0, fire.b.getWidth(), fire.b.getHeight(), mx - 7, my - 7, 14, 14, spr); else rect(mx - 2, my - 2, 5, 5, 0xFFC4502A);
            } else if (c.equals("LevelLoadingZone")) {
                rect(mx - 2, my - 2, 5, 5, 0xFF5A3D1E);
                String to = o.optString("to");
                if (!to.isEmpty()) small.draw(to, Math.max(ix, Math.min(ix + iw - small.width(to), mx - small.width(to) / 2)), my + 11, 0xFF5A3D1E);
            }
        }
        // the party
        JSONArray party = snap.optJSONArray("party");
        String lead = party != null && party.length() > 0 ? party.optJSONObject(0).optString("id").toLowerCase(Locale.ROOT) : "";
        Spr me = party != null && party.length() > 0 ? defSprite(party.optJSONObject(0).optString("id"), "mapIcon") : null;
        if (me == null) me = sprite("map-icn-" + lead);
        if (me == null) me = sprite("map-icn-valere");
        int mx = ix + iw / 2, my = iy + ih / 2;
        if (me != null) draw(me, mx - me.b.getWidth() / 2, my - me.b.getHeight() + 5); else rect(mx - 2, my - 2, 5, 5, 0xFF1D4FA8);
    }

    private static double[] solve3(double[][] a, double[] b) {
        double d = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        if (Math.abs(d) < 1e-12) return null;
        double[] x = new double[3];
        for (int k = 0; k < 3; k++) {
            double[][] t = new double[3][3];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) t[r][c] = c == k ? b[r] : a[r][c];
            x[k] = (t[0][0] * (t[1][1] * t[2][2] - t[1][2] * t[2][1]) - t[0][1] * (t[1][0] * t[2][2] - t[1][2] * t[2][0]) + t[0][2] * (t[1][0] * t[2][1] - t[1][1] * t[2][0])) / d;
        }
        return x;
    }
    private boolean worldLayout;
    // touch on the overworld chart: drag to look around, tap a place to see its name; it recentres after a few seconds
    private float panX, panY, downX, downY, lastX, lastY; private boolean dragging; private long touchAt; private int picked = -1;
    private int wmOx, wmOy; private JSONArray wmButtons;

    // quick command bar: rectangles drawn this frame and the action each one sends
    private final java.util.ArrayList<int[]> qRects = new java.util.ArrayList<>(); private final java.util.ArrayList<String> qActs = new java.util.ArrayList<>();
    private int qPressed = -1; private long qPressedAt;

    private void quickBar(int x, int y, int w) {
        qRects.clear(); qActs.clear();
        JSONObject tools = snap.optJSONObject("tools");
        if (tools == null || !tools.optBoolean("ui")) return;
        String[][] all = {{"PARTY", "tab:1", "menu-icn-character-" + leadId()}, {"ITEMS", "tab:2", "btn-mobile-icon-43px-inventory"},
                {"RELICS", "tab:3", "up-mobile-full"}, {"MAP", "press:worldMapBtn", "btn-mobile-icon-43px-worldmap"}};
        java.util.ArrayList<String[]> defs = new java.util.ArrayList<>();
        for (String[] d : all) {
            if (d[0].equals("MAP") && !tools.optBoolean("map")) continue;        // only what the game currently offers
            defs.add(d);
        }
        section(x, y, w, 44, "COMMANDS");
        int bw = (w - 8 - (defs.size() - 1) * 2) / defs.size(), bx = x + 4, by = y + 8;
        for (String[] d : defs) {
            int idx = qRects.size();
            boolean down = idx == qPressed && System.currentTimeMillis() - qPressedAt < 250;
            plate(bx, by, bw, 32, down ? G_SEL : 0xFF2A3145);
            Spr ic = sprite(d[2]);
            if (ic != null) { int iw = ic.b.getWidth(), ih = ic.b.getHeight(), sc = Math.max(iw, ih) > 22 ? 2 : 1; drawPart(ic, 0, 0, iw, ih, bx + bw / 2 - iw / sc / 2, by + 12 - ih / sc / 2, iw / sc, ih / sc, spr); }
            small.draw(d[0], bx + bw / 2 - small.width(d[0]) / 2, by + 29, down ? 0xFFFFFFFF : G_TEXT);
            qRects.add(new int[]{bx, by, bw, 32}); qActs.add(d[1]);
            bx += bw + 2;
        }
    }
    private String leadId() { JSONArray p = snap.optJSONArray("party"); return p != null && p.length() > 0 ? p.optJSONObject(0).optString("id").toLowerCase(Locale.ROOT) : ""; }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        float qx = e.getX() / S, qy = e.getY() / S;
        if (menuShown) return menuTouch(e, qx, qy);
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
            for (int i = hRects.size() - 1; i >= 0; i--) { int[] r = hRects.get(i); if (qx >= r[0] && qx < r[0] + r[2] && qy >= r[1] && qy < r[1] + r[3]) { hActs.get(i).run(); invalidate(); return true; } }
        for (int i = 0; i < qRects.size(); i++) {
            int[] r = qRects.get(i);
            if (qx < r[0] || qx > r[0] + r[2] || qy < r[1] || qy > r[1] + r[3]) continue;
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                qPressed = i; qPressedAt = System.currentTimeMillis();
                if (qActs.get(i).startsWith("tab:")) openTab(Integer.parseInt(qActs.get(i).substring(4)));
                else try { HostActivity.nativeAction(qActs.get(i)); } catch (Throwable ignored) { }
                invalidate();
            }
            return true;
        }
        if (!worldLayout) return false;
        float lx = e.getX() / S, ly = e.getY() / S;
        if (lx < MAP_X || ly < MAP_Y) return e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN;
        touchAt = System.currentTimeMillis();
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: downX = lastX = lx; downY = lastY = ly; dragging = false; break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (Math.abs(lx - downX) + Math.abs(ly - downY) > 4) dragging = true;
                if (dragging) { panX -= lx - lastX; panY -= ly - lastY; }
                lastX = lx; lastY = ly; break;
            case android.view.MotionEvent.ACTION_UP:
                if (!dragging && wmButtons != null) {            // tap: which place is under the finger (nearest centre wins)
                    float mx = lx - MAP_X + wmOx, my = ly - MAP_Y + wmOy; int best = -1; float bd = 1e9f;
                    for (int i = 0; i < wmButtons.length(); i++) {
                        JSONObject b = wmButtons.optJSONObject(i); JSONArray r = b.optJSONArray("r");
                        float x0 = (float) r.optDouble(0), y0 = -(float) r.optDouble(1), w = (float) r.optDouble(2), h = (float) r.optDouble(3);
                        if (mx < x0 - 6 || mx > x0 + w + 6 || my < y0 - 6 || my > y0 + h + 6 || b.optString("name").isEmpty()) continue;
                        float d = Math.abs(mx - (x0 + w / 2)) + Math.abs(my - (y0 + h / 2));
                        if (d < bd) { bd = d; best = i; }
                    }
                    picked = best;
                }
                break;
        }
        invalidate();
        return true;
    }
    // Chart offset for the centre of a map cursor. Re-measured 2026-11-17 against the game's objective star on Watcher
    // Island (star vs the totem beside the island, same art on both screens). The 11-16 values (92, 17) had been fitted
    // to that star in the belief that it marked the party. Unverified on other islands.
    static int wmMode = 0, wmDx = 90, wmDy = 30;
    private float fitA, fitB0; private boolean fitOk; private String fitLogged = "";
    private String fitSaved = "";
    private float campMx, campMy;            // where the party marker was when camp was set up

    /**
     * Overworld: the game's own illustrated world map (the art and layout of its map screen), shown 1:1 and scrolled so
     * the party's marker is in view. Fog pieces stay on until the game has lifted them; nothing is shown if the map
     * item has not been obtained. Returns false when the map cannot be shown (then the caller draws a plain panel).
     */
    private boolean drawWorld() {
        long now = System.currentTimeMillis();
        final boolean camping = snap.optBoolean("camp");      // at camp the chart and marker stay as they were on arriving
        if (!camping && now - wmAsked > 3000) { wmAsked = now; try { HostActivity.nativeAction("scan:worldmap"); } catch (Throwable ignored) { } }
        File f = new File(filesDir, "duo_worldmap.json");
        if (!camping && f.exists() && f.lastModified() != wmSeen) {
            wmSeen = f.lastModified();
            try { wmap = new JSONObject(new String(Files.readAllBytes(f.toPath()))); } catch (Exception e) { Log.w("SosDuo", "worldmap read", e); }
        }
        worldLayout = false;
        if (wmap == null || !wmap.optBoolean("unlocked")) return false;
        JSONArray secs = wmap.optJSONArray("sections");
        JSONObject sec = null;
        for (int i = 0; secs != null && i < secs.length(); i++) {
            JSONObject c = secs.optJSONObject(i);
            if (c.optBoolean("here") && !c.optString("section").startsWith("planet")) { sec = c; break; }
        }
        if (sec == null) return false;
        String sn = sec.optString("section");
        Spr bg = sprite(sn.startsWith("worldMap1") ? "WorldMap1-Background" : sn.startsWith("worldMap2") ? "WorldMap2-Background" : "SeaOfNightmare_Background");
        if (bg == null) return false;
        worldLayout = true;
        int vx0 = MAP_X, vy0 = MAP_Y, vw = MAP_W, vh = MAP_H, mw = bg.b.getWidth(), mh = bg.b.getHeight();
        JSONArray me = sec.optJSONArray("me");
        // The game reports the party's spot measured from the chart's bottom-left corner (wmMode 0). Other readings can be
        // tried live with the debug command "java:wm <mode> <dx> <dy>" while the exact convention is being confirmed.
        float ex = (float) me.optDouble(0), ey = (float) me.optDouble(1);
        // Smooth marker: fit the nearby entrances (world position -> chart position, nearer ones weigh more) and apply
        // that to the party's live position, so the marker glides with you and is not tied to the 8 s scan.
        JSONArray an = sec.optJSONArray("anchors"), lp = snap.optJSONArray("pos");
        // ("pc", the game's cursor placed on its hidden map screen, came out ~30 px off the live one, so it is not used)
        if (an != null && an.length() >= 3 && lp != null && !camping) {
            double px = lp.optDouble(0), pz = lp.optDouble(2);
            double[][] m = new double[3][3]; double[] bxv = new double[3], byv = new double[3];
            for (int i = 0; i < an.length(); i++) {
                JSONArray a = an.optJSONArray(i);
                double wx = a.optDouble(0), wz = a.optDouble(1), w = 1.0 / ((wx - px) * (wx - px) + (wz - pz) * (wz - pz) + 25.0);
                double[] v = {wx, wz, 1};
                for (int r = 0; r < 3; r++) { for (int c = 0; c < 3; c++) m[r][c] += w * v[r] * v[c]; bxv[r] += w * v[r] * a.optDouble(2); byv[r] += w * v[r] * a.optDouble(3); }
            }
            for (int r = 0; r < 3; r++) m[r][r] += 1e-6;
            double[] sx = solve3(m, bxv), sy = solve3(m, byv);
            if (sx != null && sy != null) {
                double fxm = sx[0] * px + sx[1] * pz + sx[2], fym = sy[0] * px + sy[1] * pz + sy[2];
                if (Math.abs(fxm - ex) < 60 && Math.abs(fym - ey) < 60) { ex = (float) fxm; ey = (float) fym; }   // sanity: stay near the reported spot
            }
        }
        // Where cursor coordinates land on this chart: fitted from the places on it (each has its spot in cursor
        // coordinates, "p", and its picture's rectangle), middle value of all of them. Falls back to the Watcher Island
        // measurement when a chart has too few places.
        JSONArray fitB = sec.optJSONArray("buttons");
        java.util.ArrayList<Float> fa = new java.util.ArrayList<>(), fb2 = new java.util.ArrayList<>();
        for (int i = 0; fitB != null && i < fitB.length(); i++) {
            JSONObject b = fitB.optJSONObject(i); JSONArray r = b.optJSONArray("r"), pp = b.optJSONArray("p");
            if (!b.optBoolean("hasp") || r == null || pp == null) continue;
            fa.add((float) (r.optDouble(0) + r.optDouble(2) / 2 - pp.optDouble(0)));
            fb2.add((float) (-r.optDouble(1) + r.optDouble(3) / 2 + pp.optDouble(1)));
        }
        java.util.Collections.sort(fa); java.util.Collections.sort(fb2);
        fitOk = fa.size() >= 3;
        fitA = fitOk ? fa.get(fa.size() / 2) : wmDx; fitB0 = fitOk ? fb2.get(fb2.size() / 2) : mh + wmDy;
        // Exact offset for this chart, measured from the game's own cursor while its map was open (the scan marks it
        // "fitLive"); remembered per chart in files/duo_fit_<chart>.txt. Until that has happened once, the button fit is used.
        JSONArray gfit = wmap.optJSONArray("fit");
        File ff = new File(filesDir, "duo_fit_" + sn + ".txt");
        try {
            if (gfit != null && wmap.optBoolean("fitLive") && sn.equals(wmap.optString("fitSection")) && Math.abs(gfit.optDouble(0) - fitA) < 30 && Math.abs(gfit.optDouble(1) - fitB0) < 30) {
                String v = gfit.optDouble(0) + " " + gfit.optDouble(1);
                if (!v.equals(fitSaved)) { fitSaved = v; Files.write(ff.toPath(), v.getBytes()); Log.i("SosDuo", "chart fit saved " + sn + " " + v); }
            }
            if (ff.exists()) { String[] v = new String(Files.readAllBytes(ff.toPath())).trim().split(" "); fitA = Float.parseFloat(v[0]); fitB0 = Float.parseFloat(v[1]); }
        } catch (Exception ignored) { }
        if (fitOk && !sn.equals(fitLogged)) { fitLogged = sn; Log.i("SosDuo", "chart fit " + sn + " a=" + fitA + " b=" + fitB0 + " (mh " + mh + ", n " + fa.size() + ") spread a " + fa.get(0) + ".." + fa.get(fa.size() - 1) + " b " + fb2.get(0) + ".." + fb2.get(fb2.size() - 1)); }
        float mx = wmMode == 1 ? ex - 18.5f : wmMode == 2 ? ex : wmMode == 3 ? ex + mw / 2f : ex;
        float my = wmMode == 1 ? 348.5f - ey : wmMode == 2 ? -ey : wmMode == 3 ? mh / 2f - ey : mh - ey;
        if (wmMode == 0) { mx = ex + fitA; my = fitB0 - ey; } else { mx += wmDx; my += wmDy; }
        if (camping && campMx > 0) { mx = campMx; my = campMy; } else if (!camping) { campMx = mx; campMy = my; }
        if (System.currentTimeMillis() - touchAt > 6000 && !dragging) { panX *= 0.7f; panY *= 0.7f; if (Math.abs(panX) + Math.abs(panY) < 1) { panX = panY = 0; picked = -1; } }
        int ox = Math.max(0, Math.min(mw - vw, Math.round(mx + panX) - vw / 2)), oy = Math.max(0, Math.min(mh - vh, Math.round(my + panY) - vh / 2));
        panX = ox + vw / 2 - mx; panY = oy + vh / 2 - my;      // keep the pan inside the chart
        if (Math.abs(panX) < 1 && System.currentTimeMillis() - touchAt > 6000) panX = 0;
        if (Math.abs(panY) < 1 && System.currentTimeMillis() - touchAt > 6000) panY = 0;
        wmOx = ox; wmOy = oy;
        g.save();
        g.clipRect(vx0, vy0, vx0 + vw, vy0 + vh);
        g.drawBitmap(bg.b, vx0 - ox, vy0 - oy, spr);
        JSONArray bs = sec.optJSONArray("buttons");
        wmButtons = bs;
        for (int i = 0; bs != null && i < bs.length(); i++) {
            JSONObject b = bs.optJSONObject(i);
            JSONArray r = b.optJSONArray("r");
            Spr sp = sprite(b.optString("sprite"));
            if (sp != null && b.optBoolean("on", true)) g.drawBitmap(sp.b, vx0 - ox + Math.round((float) r.optDouble(0)), vy0 - oy - Math.round((float) r.optDouble(1)), spr);
        }
        JSONArray fogs = sec.optJSONArray("fog");
        for (int i = 0; fogs != null && i < fogs.length(); i++) {
            JSONObject fg = fogs.optJSONObject(i);
            if (fg.optBoolean("lifted")) continue;
            JSONArray r = fg.optJSONArray("r");
            Spr sp = sprite(fg.optString("sprite"));
            if (sp != null) g.drawBitmap(sp.b, vx0 - ox + Math.round((float) r.optDouble(0)), vy0 - oy - Math.round((float) r.optDouble(1)), spr);
        }
        JSONArray party = snap.optJSONArray("party");
        String lead = party != null && party.length() > 0 ? party.optJSONObject(0).optString("id").toLowerCase(Locale.ROOT) : "";
        Spr head = party != null && party.length() > 0 ? defSprite(party.optJSONObject(0).optString("id"), "mapIcon") : null;
        if (head == null) head = sprite("map-icn-" + lead);
        if (head == null) head = sprite("map-icn-valere");
        int hx = vx0 - ox + Math.round(mx), hy = vy0 - oy + Math.round(my);
        // the objective's gold star, as on the game's map; where it sits on top of the party the two take turns, as there
        JSONArray objs = sec.optJSONArray("obj");
        Spr star = sprite("map-icn-objective");
        boolean overlap = false, starTurn = (System.currentTimeMillis() / 700) % 2 == 0;
        for (int i = 0; objs != null && i < objs.length(); i++) {
            JSONArray o = objs.optJSONArray(i);
            int sx = vx0 - ox + Math.round((float) o.optDouble(0) + fitA), sy = vy0 - oy + Math.round(fitB0 - (float) o.optDouble(1));
            boolean near = Math.abs(sx - hx) < 14 && Math.abs(sy - hy) < 14;
            overlap |= near;
            if (near && !starTurn) continue;
            if (star != null) draw(star, sx - star.b.getWidth() / 2, sy - star.b.getHeight() / 2);
            else { rect(sx - 1, sy - 6, 3, 9, 0xFFF2C14E); rect(sx - 4, sy - 3, 9, 3, 0xFFF2C14E); }
        }
        if (!(overlap && starTurn)) {
            if (head != null) draw(head, hx - head.b.getWidth() / 2, hy - head.b.getHeight() / 2); else rect(hx - 2, hy - 2, 5, 5, 0xFF1D4FA8);
        }
        g.restore();
        rect(vx0, vy0 + vh, vw, 1, G_EDGE); rect(vx0, vy0 + vh + 1, vw, 1, 0xFF05070F);
        // name of the island / region the party is on, titled like the game's own map screen
        String here = "";
        for (int i = 0; bs != null && i < bs.length(); i++) if (bs.optJSONObject(i).optBoolean("here")) { here = plain(bs.optJSONObject(i).optString("name")); break; }
        if (picked >= 0 && bs != null && picked < bs.length()) {      // a tapped place: the game's hand cursor beside it, its name as the title
            JSONObject pb = bs.optJSONObject(picked); JSONArray r = pb.optJSONArray("r");
            here = plain(pb.optString("name"));
            int hx2 = vx0 - ox + Math.round((float) r.optDouble(0)) - 18, hy2 = vy0 - oy - Math.round((float) r.optDouble(1)) + Math.round((float) r.optDouble(3)) / 2 - 8;
            if (hy2 > vy0 && hy2 < vy0 + vh - 16) draw(sprite("HandCursor"), Math.max(vx0, hx2), hy2);
        }
        if (!here.isEmpty()) {
            int tw = big.width(here), tx = vx0 + (vw - tw) / 2;
            big.drawOutline(here, tx, vy0 + 16, 0xFFFFFFFF);
            Spr ul = sprite("map-name-underline");
            if (ul != null) drawPart(ul, 0, 0, ul.b.getWidth(), ul.b.getHeight(), tx - 6, vy0 + 20, tw + 12, ul.b.getHeight(), spr);
        }
        return true;
    }

    /** Overworld: plain parchment with the region name until the game's illustrated map is wired in. */
    private void drawWorldWindow(int x, int y, int w, int h) {
        Spr paper = sprite("panel-texture-background");
        if (paper != null) nine(paper, x, y, w, h, 0); else rect(x, y, w, h, 0xFFE4D6B0);
        String area = snap.optString("area");
        big.draw(area, x + 10, y + 18, 0xFF5A3D1E);
        Spr ul = sprite("map-name-underline");
        if (ul != null) drawPart(ul, 0, 0, ul.b.getWidth(), ul.b.getHeight(), x + 10, y + 21, Math.max(20, big.width(area)), ul.b.getHeight(), spr);
    }

    /** Current story objective, as the game names it. */
    private void drawObjective(int x, int y, int w) {
        if (activity.isEmpty() && objective.isEmpty()) return;
        java.util.List<String> lines = wrap(big, activity.isEmpty() ? objective : activity, w - 14);
        int h = 16 + lines.size() * 13 + (objective.isEmpty() || activity.isEmpty() ? 0 : 11);
        box(x, y, w, h);
        small.draw("OBJECTIVE", x + 7, y + 10, GOLD);
        int ty = y + 24;
        for (String l : lines) { big.draw(l, x + 7, ty, 0xFFFFFFFF); ty += 13; }
        if (!objective.isEmpty() && !activity.isEmpty()) { draw(sprite("general-arrow-mini"), x + 7, ty - 8); small.draw(objective, x + 14, ty - 3, G_TEXT); }
    }

    // ================================================================ companion menus (Party / Items / Relics)
    // The game's own menu screens, laid out for the bottom screen. Data comes from files/duo_menu.json (written by the
    // native side with the game's getters); every change is sent as a "do:" action that runs the game's own call.
    private int tab;                       // 0 map, 1 party, 2 items, 3 relics
    private boolean menuShown;
    private JSONObject md = new JSONObject();
    private int mdSeqSeen = -1; private long mdAsked, toastAt; private String toast = "";
    private int selChar, cardTab, selSkill = -1, selItem = -1, selRelic = -1, selChoice = -1, swapFirst = -1;
    private String pickSlot;               // equipment slot whose choices are open
    private boolean swapMode;
    private String targetFor;              // pending "do:use <ref>" / "do:skill <char> <i>" waiting for a target
    private final java.util.ArrayList<int[]> hRects = new java.util.ArrayList<>();
    private final java.util.ArrayList<Runnable> hActs = new java.util.ArrayList<>();
    private final java.util.ArrayList<Object[]> sAreas = new java.util.ArrayList<>();          // {int[] rect, String id, Integer max}
    private final HashMap<String, Float> scrolls = new HashMap<>();
    private Runnable downAct; private String dragList; private float mDownX, mDownY, mLastY; private boolean mDrag;

    private void send(String a) { try { HostActivity.nativeAction(a); } catch (Throwable ignored) { } }
    void openTab(int t) {
        tab = t; pickSlot = null; targetFor = null; swapMode = false; swapFirst = -1; selChoice = -1; selSkill = -1;
        if (t != 0) { send("md:close"); send("md:refresh"); mdAsked = System.currentTimeMillis(); }
        invalidate();
    }
    private void hit(int x, int y, int w, int h, Runnable r) { hRects.add(new int[]{x, y, w, h}); hActs.add(r); }
    /** Scrollable area: returns the current offset in pixels (clamped) and remembers the area for dragging. */
    private int scroll(String id, int x, int y, int w, int h, int content) {
        int max = Math.max(0, content - h);
        Float v = scrolls.get(id);
        float f = v == null ? 0 : Math.max(0, Math.min(max, v));
        scrolls.put(id, f);
        sAreas.add(new Object[]{new int[]{x, y, w, h}, id, max});
        if (max > 0) {                                           // slim scroll thumb on the right edge
            int th = Math.max(8, h * h / content), ty = y + Math.round((h - th) * f / max);
            rect(x + w - 2, y, 2, h, 0xFF05070F); rect(x + w - 2, ty, 2, th, G_EDGE);
        }
        return Math.round(f);
    }
    private boolean menuTouch(android.view.MotionEvent e, float x, float y) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mDownX = x; mDownY = mLastY = y; mDrag = false; downAct = null; dragList = null;
                for (int i = hRects.size() - 1; i >= 0; i--) { int[] r = hRects.get(i); if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) { downAct = hActs.get(i); break; } }
                for (Object[] a : sAreas) { int[] r = (int[]) a[0]; if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) dragList = (String) a[1]; }
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (Math.abs(y - mDownY) > 5) mDrag = true;
                if (mDrag && dragList != null) { Float v = scrolls.get(dragList); scrolls.put(dragList, (v == null ? 0 : v) - (y - mLastY)); invalidate(); }
                mLastY = y;
                break;
            case android.view.MotionEvent.ACTION_UP:
                if (!mDrag && downAct != null) { downAct.run(); invalidate(); }
                downAct = null;
                break;
        }
        return true;
    }
    private void loadMd() {
        long now = System.currentTimeMillis();
        if (now - mdAsked > 2500) { mdAsked = now; send("md:refresh"); }
        loadMdFile();
    }
    private void loadMdFile() {
        long now = System.currentTimeMillis();
        int seq = snap.optInt("mdSeq", 0);
        if (seq == mdSeqSeen) return;
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(new File(filesDir, "duo_menu.json").toPath())));
            if (j.optInt("seq") < seq) return;                   // file not written yet
            mdSeqSeen = seq;
            String m = j.optString("msg");
            if (!m.isEmpty()) { toast = m; toastAt = now; }
            md = j;
        } catch (Exception ignored) { }
    }
    private void button(String label, int x, int y, int w, int h, boolean on, int color, Runnable r) {
        plate(x - 1, y - 1, w + 2, h + 2, 0xFF05070F); plate(x, y, w, h, on ? G_SEL : 0xFF2A3145);
        small.draw(label, x + (w - small.width(label)) / 2, y + h / 2 + 3, color);
        if (r != null) hit(x, y, w, h, r);
    }
    private void icon(String name, int x, int y) {               // sprite centred in a 16x16 cell
        Spr s = name == null || name.isEmpty() ? null : sprite(name);
        if (s == null) return;
        int w = s.b.getWidth(), h = s.b.getHeight(), sc = Math.max(w, h) > 26 ? 2 : 1;
        drawPart(s, 0, 0, w, h, x + 8 - w / sc / 2, y + 8 - h / sc / 2, w / sc, h / sc, spr);
    }
    /** A sprite named by the character's own definition in the snapshot ("face" = HUD portrait, "mapIcon" = map head). */
    private Spr defSprite(String id, String key) {
        JSONArray p = snap.optJSONArray("party");
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject m = p.optJSONObject(i);
            if (id.equals(m.optString("id")) && !m.optString(key).isEmpty()) return sprite(m.optString(key));
        }
        return null;
    }
    private Spr faceOf(String id) {
        JSONObject faces = snap.optJSONObject("faces");
        String c = id.toLowerCase(Locale.ROOT);
        Spr f = faces != null && faces.has(id) ? sprite(faces.optString(id)) : null;
        if (f == null) f = defSprite(id, "face");
        if (f == null) f = spritePrefix("team-hud-portrait-" + c);
        if (f == null) f = sprite("menu-icn-character-" + c);
        return f;
    }
    private String fit(Font f, String t, int w) { while (t.length() > 3 && f.width(t) > w) t = t.substring(0, t.length() - 1); return t; }
    private void descBox(int x, int y, int w, int h, String text) {
        box(x, y, w, h);
        java.util.List<String> l = wrap(big, plain(text), w - 14);
        for (int k = 0; k < l.size() && k < (h - 6) / 13; k++) big.draw(l.get(k), x + 7, y + 16 + k * 13, 0xFFFFFFFF);
    }
    /** Party rows (portrait, name, HP/MP), 31 px each; a tap runs pick(index). */
    private void partyRows(int x, int y, int w, JSONArray chars, int selected, int marked, java.util.function.IntConsumer pick) {
        int n = chars == null ? 0 : chars.length();
        int pitch = Math.min(31, 204 / Math.max(1, n)), h = pitch - 4;          // 31 px rows up to six members, tighter beyond
        for (int i = 0; i < n; i++) {
            JSONObject c = chars.optJSONObject(i);
            int ry = y + i * pitch;
            boolean fight = c.optBoolean("combat", true);
            plate(x - 1, ry + 1, w + 2, h + 2, i == marked ? GOLD : 0xFF05070F);
            if (i == selected) selRow(x, ry + 2, w, h); else plate(x, ry + 2, w, h, 0xFF2A3145);
            Spr f = faceOf(c.optString("id"));
            if (f != null) {
                g.save(); g.clipRect(x, ry + 2, x + 32, ry + 2 + h);
                g.drawBitmap(f.b, x + 2, ry + 1 + h - f.b.getHeight(), fight ? spr : greyP);
                g.restore();
            }
            int nameY = ry + (pitch >= 29 ? 12 : 10), statY = ry + h - 4;
            small.draw(fit(small, plain(c.optString("name")).toUpperCase(Locale.ROOT), w - 80), x + 34, nameY, fight ? 0xFFFFFFFF : G_TEXT);
            if (!fight) small.drawRight("RESERVE", x + w - 4, nameY, G_LABEL);
            small.draw("HP " + c.optInt("hp") + "/" + c.optInt("hpMax"), x + 34, statY, c.optInt("hp") * 10 < c.optInt("hpMax") * 3 ? 0xFFFF7B6B : G_LABEL);
            small.drawRight("MP " + c.optInt("mp") + "/" + c.optInt("mpMax"), x + w - 4, statY, G_LABEL);
            final int idx = i;
            hit(x, ry + 2, w, h, () -> pick.accept(idx));
        }
    }
    private String selItemRef = "";
    private static int catRank(String c) { return c.equals("Snack") ? 0 : c.equals("KeyItem") ? 1 : c.equals("CharacterWeapon") ? 2 : c.equals("CharacterArmor") ? 3 : c.equals("CharacterTrinket") ? 4 : c.equals("Ingredient") ? 5 : c.equals("RecipeUnlockItem") ? 6 : 7; }
    private static String catLabel(String c) { return c.equals("Snack") ? "FOOD" : c.equals("KeyItem") ? "KEY ITEM" : c.equals("CharacterWeapon") ? "WEAPON" : c.equals("CharacterArmor") ? "ARMOR" : c.equals("CharacterTrinket") ? "TRINKET" : c.equals("Ingredient") ? "INGREDIENT" : c.equals("RecipeUnlockItem") ? "RECIPE" : c.equals("SheetMusicItem") ? "SHEET MUSIC" : "VALUABLE"; }
    private static String slotLabel(String k) { return k.equals("weapon") ? "WEAPON" : k.equals("armor") ? "ARMOR" : k.equals("group") ? "GROUP TRINKET" : "TRINKET"; }

    // One navigation list for the whole bottom screen, laid out like the game's own menu (Equip / Skills / Items /
    // Order / Relics, with Map added in front): always in the same place, the open screen highlighted with the game's
    // blue bar and hand cursor.
    static int padParty;                   // "java:pad N": repeat the last member up to N, to try layouts with a big party
    private static JSONArray padded(JSONArray a) {
        if (a == null || a.length() == 0 || padParty <= a.length()) return a;
        JSONArray o = new JSONArray();
        try {
            for (int i = 0; i < a.length(); i++) o.put(a.get(i));
            while (o.length() < padParty) { JSONObject c = new JSONObject(a.optJSONObject(a.length() - 1).toString()); c.put("combat", false); o.put(c); }
        } catch (Exception ignored) { }
        return o;
    }
    static float xpScroll = 0.05f;          // starfield scroll, in texture widths per second ("java:xps <v>" to tune)
    static final String[] NAV = {"Map", "Equip", "Skills", "Items", "Order", "Relics"};
    static final String[] HINT = {"", "View stats and change equipment.", "View skills and combos.", "View and use items.", "Change the party order.", "Browse and toggle relics."};
    static final int CX = 160, CW = 247, CT = 30;      // content panel: left, width, top (bottom edge = 354)

    /** The game's selection bar: bright blue edge, blue fill fading darker to the right. */
    private void selRow(int x, int y, int w, int h) {
        rect(x, y, w, h, 0xFF5AB4F5);
        int n = 8, iw = w - 2;
        for (int k = 0; k < n; k++) {
            float t = k / (float) (n - 1);
            int r = Math.round(0x2F + (0x1A - 0x2F) * t), gg = Math.round(0x6F + (0x3A - 0x6F) * t), b = Math.round(0xC2 + (0x72 - 0xC2) * t);
            rect(x + 1 + iw * k / n, y + 1, iw * (k + 1) / n - iw * k / n, h - 2, 0xFF000000 | (r << 16) | (gg << 8) | b);
        }
    }
    /** Column-title band at the top of a list panel, as in the game's item and relic lists. */
    private void band(int x, int y, int w, String left, String right) {
        rect(x + 2, y, w - 4, 12, 0xFF263650);
        small.draw(left, x + 9, y + 9, G_TEXT);
        if (right != null) small.drawRight(right, x + w - 10, y + 9, G_TEXT);
    }
    private void drawNav() {
        if (!menuShown) {
            long now = System.currentTimeMillis();
            if (now - mdAsked > 6000) { mdAsked = now; send("md:refresh"); }
            loadMdFile();
            if (!toast.isEmpty() && now - toastAt < 2500) { int tw = big.width(toast) + 16; box(6, 132, Math.min(150, tw), 20); big.draw(fit(big, toast, 136), 14, 147, 0xFFFFD27A); }
        }
        // two columns of big cells (72x30, about a fingertip on the Thor) instead of six thin rows
        box(6, CT, 150, 96);
        Spr hand = sprite("HandCursor");
        for (int i = 0; i < NAV.length; i++) {
            int cx = 9 + (i % 2) * 72, cy = CT + 3 + (i / 2) * 30;
            boolean on = i == tab;
            // greyed out exactly when the game's own menu greys the entry (or would not open at all)
            final boolean off = i != 0 && md.has("ok") && (!md.optBoolean("ok", true) && !md.optBoolean("gameMenu") && tab == 0 || i == 4 && !md.optBoolean("orderOk", true) || i == 5 && !md.optBoolean("relicsOk", true));
            if (on) { selRow(cx, cy + 1, 72, 28); if (hand != null) draw(hand, cx - 8, cy + 7); }
            big.draw(NAV[i], cx + 22, cy + 19, off ? 0xFF5E6778 : on ? 0xFFFFFFFF : G_TEXT);
            final int t = i;
            hit(cx, cy, 72, 30, () -> {
                if (off) { toast = t == 4 ? "The order cannot be changed right now" : t == 5 ? "No relics yet" : "Not available right now"; toastAt = System.currentTimeMillis(); }
                else if (t != tab) openTab(t);
            });
        }
    }
    /** At camp the game's own menu lists only the members still in the walking party (the others sit by the fire). */
    private JSONArray atCamp(JSONArray a) {
        if (a == null || !snap.optBoolean("camp")) return a;
        JSONArray o = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optBoolean("present", true)) o.put(a.optJSONObject(i));
        return o.length() > 0 ? o : a;
    }
    private void pickChar(JSONArray chars, int i) {
        String id = chars.optJSONObject(i).optString("id");
        if (targetFor != null) { send(targetFor + " " + id); targetFor = null; }
        else if (tab == 4) {
            if (swapFirst < 0) swapFirst = i;
            else { if (swapFirst != i) send("do:swap " + chars.optJSONObject(swapFirst).optString("id") + " " + id); swapFirst = -1; }
        } else if (tab == 1 || tab == 2) { selChar = i; pickSlot = null; selSkill = -1; selChoice = -1; send("md:close"); }
    }

    private void drawMenus() {
        loadMd();
        final JSONArray chars = padded(atCamp(md.optJSONArray("chars")));
        if (chars == null) { box(CX, CT, CW, 324); small.draw("...", CX + 10, CT + 16, DIM); return; }
        if (selChar >= chars.length()) selChar = 0;
        // left, under the navigation: the party in order; it is also where a target or a swap is picked
        section(6, 134, 150, 220, targetFor != null ? "USE ON" : tab == 4 ? "TAP TWO TO SWAP" : "PARTY");
        partyRows(11, 141, 140, chars, (tab == 1 || tab == 2) && targetFor == null ? selChar : -1, tab == 4 ? swapFirst : -1, i -> pickChar(chars, i));
        if (tab == 1 || tab == 2) drawCharTab(chars); else if (tab == 3) drawItemsTab(); else if (tab == 4) drawOrderTab(chars); else drawRelicsTab();
        if (!toast.isEmpty() && System.currentTimeMillis() - toastAt < 2500) {
            int tw = big.width(toast) + 16;
            box(CX + (CW - tw) / 2, CT + 4, tw, 20); big.draw(toast, CX + (CW - tw) / 2 + 8, CT + 19, 0xFFFFD27A);
        }
    }

    /** Equip (tab 1) and Skills (tab 2): the selected character's sheet = the game's card on top, slots or a list below. */
    private void drawCharTab(final JSONArray chars) {
        final JSONObject c = chars.optJSONObject(selChar);
        final String cid = c.optString("id");
        final int x = CX, w = CW;
        box(x, CT, w, 324);
        draw(sprite("panel-ornament-2-single-t1"), x + 2, CT + 2);
        JSONObject ch = pickSlot == null ? null : md.optJSONObject("choices");
        final JSONArray list = ch != null && cid.equals(ch.optString("char")) && pickSlot.equals(ch.optString("slot")) ? ch.optJSONArray("list") : null;
        JSONObject cur = null, pick = null;
        JSONArray slots = c.optJSONArray("slots");
        for (int i = 0; pickSlot != null && slots != null && i < slots.length(); i++) if (pickSlot.equals(slots.optJSONObject(i).optString("k"))) cur = slots.optJSONObject(i);
        boolean hasCur = cur != null && !cur.optString("ref").isEmpty(), canRemove = hasCur && !pickSlot.equals("weapon") && !pickSlot.equals("armor");
        final int off = canRemove ? 1 : 0;
        if (tab == 1 && list != null && selChoice - off >= 0 && selChoice - off < list.length()) pick = list.optJSONObject(selChoice - off);
        drawCard(c, cid, x, w, pick != null && !pick.optBoolean("cur") ? pick : null, cur);
        final int by = CT + 138, ly = CT + 152, lh = 110, dy = CT + 266, dh = 54;
        if (tab == 1 && pickSlot == null) {
            band(x, by, w, "EQUIPMENT", null);
            for (int i = 0; slots != null && i < slots.length() && i < 6; i++) {
                final JSONObject s = slots.optJSONObject(i);
                final String k = s.optString("k");
                int ry = CT + 153 + i * 29;
                icon(s.optString("icon"), x + 12, ry + 5);
                small.draw(slotLabel(k), x + 34, ry + 9, G_LABEL);
                String sn = s.optString("name");
                big.draw(sn.isEmpty() ? "-----" : fit(big, plain(sn), w - 64), x + 34, ry + 22, sn.isEmpty() ? G_LABEL : 0xFFFFFFFF);
                draw(sprite("general-arrow-mini"), x + w - 16, ry + 9);
                if (i > 0) rect(x + 8, ry - 2, w - 16, 1, 0xFF2C3850);
                hit(x + 3, ry, w - 6, 27, () -> { pickSlot = k; selChoice = -1; scrolls.put("choices", 0f); send("md:equip " + cid + " " + k); });
            }
        } else if (tab == 1) {
            final String slotNow = pickSlot;
            band(x, by, w, slotLabel(pickSlot), null);
            button("BACK", x + w - 68, by - 3, 62, 18, false, G_TEXT, () -> { pickSlot = null; selChoice = -1; send("md:close"); });
            int n = (list == null ? 0 : list.length()) + off;
            int so = scroll("choices", x + 3, ly, w - 6, lh, n * 22);
            g.save(); g.clipRect(x + 3, ly, x + w - 3, ly + lh);
            for (int i = 0; i < n; i++) {
                int ry = ly + i * 22 - so;
                if (ry + 22 <= ly || ry >= ly + lh) continue;
                if (i == selChoice) selRow(x + 4, ry, w - 11, 21);
                if (i < off) small.draw("TAKE OFF", x + 34, ry + 14, i == selChoice ? 0xFFFFFFFF : G_TEXT);
                else {
                    JSONObject it = list.optJSONObject(i - off);
                    icon(it.optString("icon"), x + 12, ry + 3);
                    big.draw(fit(big, plain(it.optString("name")), w - 114), x + 34, ry + 15, 0xFFFFFFFF);
                    String st = it.has("atk") ? "ATK " + it.optInt("atk") + (it.optInt("matk") != 0 ? "  M " + it.optInt("matk") : "") : it.has("def") ? "DEF " + it.optInt("def") + "  M " + it.optInt("mdef") : "";
                    if (it.optBoolean("cur")) st = "EQUIPPED";
                    small.drawRight(st, x + w - 14, ry + 14, it.optBoolean("cur") ? G_CYAN : G_TEXT);
                }
                final int idx = i;
                if (ry >= ly && ry + 21 <= ly + lh) hit(x + 4, ry, w - 11, 21, () -> selChoice = idx);
            }
            g.restore();
            if (list == null) small.draw("...", x + 12, ly + 12, DIM);
            else if (n == 0) small.draw("NOTHING TO EQUIP", x + 12, ly + 12, G_LABEL);
            String d = selChoice >= 0 && selChoice < off ? "Take off " + plain(cur.optString("name")) + "." : pick != null ? pick.optString("desc") : hasCur ? cur.optString("desc") : "";
            boolean btn = selChoice >= 0 && selChoice < off || pick != null && !pick.optBoolean("cur");
            descBoxW(x + 5, dy, w - 10, dh, d, btn ? 74 : 0);
            if (selChoice >= 0 && selChoice < off) button("TAKE OFF", x + w - 74, dy + dh - 27, 64, 22, true, 0xFFFFFFFF, () -> { send("do:equip " + cid + " " + slotNow + " -"); pickSlot = null; selChoice = -1; });
            else if (pick != null && !pick.optBoolean("cur")) {
                final String ref = pick.optString("ref");
                button("EQUIP", x + w - 74, dy + dh - 27, 64, 22, true, 0xFFFFFFFF, () -> { send("do:equip " + cid + " " + slotNow + " " + ref); pickSlot = null; selChoice = -1; });
            }
        } else {
            band(x, by, w, "SKILL", "COST");
            // the game keeps skills, combos and the ultimate apart; same here, as titled groups in one scrolling list
            JSONArray sk = c.optJSONArray("skills");
            final int n = sk == null ? 0 : sk.length();
            final int[] cats = {1, 3, 2};
            final String[] catNames = {"SKILLS", "COMBOS", "ULTIMATE"};
            java.util.ArrayList<Integer> rowsIdx = new java.util.ArrayList<>();      // >= 0 = index into sk, < 0 = -(group + 1) header
            for (int gi = 0; gi < cats.length; gi++) {
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    if (sk.optJSONObject(i).optInt("cat", 1) != cats[gi]) continue;
                    if (!any) { rowsIdx.add(-(gi + 1)); any = true; }
                    rowsIdx.add(i);
                }
            }
            for (int i = 0; i < n; i++) { int ct = sk.optJSONObject(i).optInt("cat", 1); if (ct != 1 && ct != 2 && ct != 3) rowsIdx.add(i); }
            int total = 0;
            for (int r : rowsIdx) total += r < 0 ? 14 : 22;
            int so = scroll("skills", x + 3, ly, w - 6, lh, total);
            g.save(); g.clipRect(x + 3, ly, x + w - 3, ly + lh);
            int ry = ly - so;
            for (int r : rowsIdx) {
                if (r < 0) {
                    if (ry + 14 > ly && ry < ly + lh) { rect(x + 8, ry + 11, w - 20, 1, 0xFF2C3850); small.draw(catNames[-r - 1], x + 10, ry + 9, GOLD); }
                    ry += 14;
                    continue;
                }
                JSONObject it = sk.optJSONObject(r);
                if (ry + 22 > ly && ry < ly + lh) {
                    if (r == selSkill) selRow(x + 4, ry, w - 11, 21);
                    icon(it.optString("icon"), x + 12, ry + 3);
                    big.draw(fit(big, plain(it.optString("n")), w - 90), x + 34, ry + 15, 0xFFFFFFFF);
                    int cp = it.optInt("cp");
                    if (it.optInt("cat") == 3 && cp > 0) {                    // combos cost combo points: one small diamond each
                        for (int k = 0; k < cp && k < 3; k++) { int dx = x + w - 20 - k * 9; rect(dx, ry + 8, 5, 5, 0xFF05070F); rect(dx + 1, ry + 9, 3, 3, GOLD); }
                    } else if (it.optInt("cost") > 0) small.drawRight(it.optInt("cost") + " MP", x + w - 14, ry + 14, it.optInt("cost") > c.optInt("mp") ? 0xFFFF7B6B : G_CYAN);
                    final int idx = r;
                    if (ry >= ly && ry + 21 <= ly + lh) hit(x + 4, ry, w - 11, 21, () -> { selSkill = idx; targetFor = null; });
                }
                ry += 22;
            }
            g.restore();
            if (n == 0) small.draw("NO SKILLS YET", x + 12, ly + 12, G_LABEL);
            JSONObject s = selSkill >= 0 && selSkill < n ? sk.optJSONObject(selSkill) : null;
            boolean usable = s != null && s.optBoolean("field");
            descBoxW(x + 5, dy, w - 10, dh, s == null ? "" : s.optString("desc"), usable ? 74 : 0);
            if (usable) {
                final String act = "do:skill " + cid + " " + s.optInt("i");
                final boolean aoe = s.optBoolean("aoe");
                if (targetFor != null) button("CANCEL", x + w - 74, dy + dh - 27, 64, 22, false, G_TEXT, () -> targetFor = null);
                else button("USE", x + w - 74, dy + dh - 27, 64, 22, true, 0xFFFFFFFF, () -> { if (aoe) send(act + " " + cid); else targetFor = act; });
            }
        }
    }
    /** Description box whose text keeps clear of a button in its bottom-right corner. */
    private void descBoxW(int x, int y, int w, int h, String text, int reserve) {
        box(x, y, w, h);
        java.util.List<String> l = wrap(big, plain(text), w - 14 - reserve);
        for (int k = 0; k < l.size() && k < (h - 6) / 13; k++) big.draw(l.get(k), x + 7, y + 16 + k * 13, 0xFFFFFFFF);
    }

    /** First cached sprite whose name starts with the prefix, whatever the letter case. */
    private Spr spriteCI(String prefix) {
        long now = System.currentTimeMillis();
        if (now - lastList > 4000) { lastList = now; String[] l = cache.list(); cacheNames = l == null ? new String[0] : l; }
        String best = null, pl = prefix.toLowerCase(Locale.ROOT);
        for (String nm : cacheNames) if (nm.toLowerCase(Locale.ROOT).startsWith(pl) && nm.endsWith(".bin") && (best == null || nm.length() < best.length())) best = nm;
        return best == null ? null : sprite(best.substring(0, best.length() - 4));
    }

    /** The game's character card: big portrait with name plate and title, and the stats column (with the change a
     *  highlighted piece of gear would make). */
    private void drawCard(final JSONObject c, final String cid, final int x, final int w, JSONObject pick, JSONObject cur) {
        int col = GOLD, lv = 0; String title = "";
        JSONArray sp = snap.optJSONArray("party");
        for (int i = 0; sp != null && i < sp.length(); i++) if (cid.equals(sp.optJSONObject(i).optString("id"))) {
            lv = sp.optJSONObject(i).optInt("lv"); title = plain(sp.optJSONObject(i).optString("title"));
            try { col = android.graphics.Color.parseColor(sp.optJSONObject(i).optString("color")); } catch (Exception ignored) { }
        }
        // portrait on its gold backing; the art is taller and wider than the backing, as in the game
        final int pcx = x + 58, pb = CT + 112;
        Spr bg = sprite("portrait-background-temp"), pt = defSprite(cid, "portrait");
        if (pt == null) pt = spriteCI("dialog-portrait-" + cid);
        if (bg != null) { tintP.setColorFilter(new PorterDuffColorFilter(0xFFE2A93B, PorterDuff.Mode.MULTIPLY)); g.drawBitmap(bg.b, pcx - bg.b.getWidth() / 2, pb - bg.b.getHeight(), tintP); }   // the game tints this backing gold
        if (pt != null) { g.save(); g.clipRect(x + 3, CT + 3, x + 112, pb); draw(pt, pcx - pt.b.getWidth() / 2, pb - pt.b.getHeight()); g.restore(); }
        else { Spr f = faceOf(cid); if (f != null) drawPart(f, 0, 0, f.b.getWidth(), f.b.getHeight(), pcx - f.b.getWidth(), pb - 2 * f.b.getHeight() - 8, f.b.getWidth() * 2, f.b.getHeight() * 2, spr); }
        String nm = plain(c.optString("name"));
        int nw = big.width(nm) + 16;
        plate(pcx - nw / 2 - 1, pb - 8, nw + 2, 17, 0xFF05070F); plate(pcx - nw / 2, pb - 7, nw, 15, G_EDGE); plate(pcx - nw / 2 + 1, pb - 6, nw - 2, 13, G_PANEL);
        big.draw(nm, pcx - big.width(nm) / 2, pb + 4, col);
        if (!title.isEmpty()) { String tt = title.toUpperCase(Locale.ROOT); small.draw(tt, pcx - small.width(tt) / 2, pb + 20, G_LABEL); }
        // stats column
        String[] lab = {"Level", "HP", "MP", "ATK", "DEF", "M. ATK", "M. DEF"};
        String[] val = {String.valueOf(lv), c.optInt("hp") + "/" + c.optInt("hpMax"), c.optInt("mp") + "/" + c.optInt("mpMax"),
                String.valueOf(c.optInt("atk")), String.valueOf(c.optInt("def")), String.valueOf(c.optInt("matk")), String.valueOf(c.optInt("mdef"))};
        int sy = CT + 20;
        for (int i = 0; i < 7; i++) {
            if (i == 3) { rect(x + 124, sy - 7, w - 136, 1, 0xFF3A4660); sy += 6; }
            big.draw(lab[i], x + 124, sy, G_TEXT);
            big.drawRight(val[i], x + w - 12, sy, 0xFFFFFFFF);
            if (i >= 3 && pick != null && cur != null) {
                String key = i == 3 ? "atk" : i == 4 ? "def" : i == 5 ? "matk" : "mdef";
                int d = pick.optInt(key) - cur.optInt(key);
                if ((pick.has(key) || cur.has(key)) && d != 0) small.drawRight((d > 0 ? "+" : "") + d, x + w - 16 - big.width(val[i]), sy - 1, d > 0 ? 0xFF7BE08A : 0xFFFF7B6B);
            }
            sy += 14;
        }
    }

    private void drawItemsTab() {
        JSONArray raw = md.optJSONArray("items");
        java.util.ArrayList<JSONObject> sorted = new java.util.ArrayList<>();
        for (int i = 0; raw != null && i < raw.length(); i++) sorted.add(raw.optJSONObject(i));
        java.util.Collections.sort(sorted, (a, b) -> catRank(a.optString("cat")) != catRank(b.optString("cat")) ? catRank(a.optString("cat")) - catRank(b.optString("cat")) : a.optInt("pri") - b.optInt("pri"));
        final JSONArray items = new JSONArray(sorted);
        int n = items.length();
        selItem = -1;
        for (int i = 0; i < n; i++) if (items.optJSONObject(i).optString("ref").equals(selItemRef)) selItem = i;
        final int x = CX, w = CW, ly = CT + 16, lh = 176;
        box(x, CT, w, 196);
        band(x, CT + 2, w, "ITEM NAME", "QTY");
        int so = scroll("items", x + 3, ly, w - 6, lh, n * 22);
        g.save(); g.clipRect(x + 3, ly, x + w - 3, ly + lh);
        for (int i = 0; i < n; i++) {
            JSONObject it = items.optJSONObject(i);
            int ry = ly + i * 22 - so;
            if (ry + 22 <= ly || ry >= ly + lh) continue;
            if (i == selItem) selRow(x + 4, ry, w - 11, 21);
            icon(it.optString("icon"), x + 12, ry + 3);
            big.draw(fit(big, plain(it.optString("name")), w - 70), x + 34, ry + 15, 0xFFFFFFFF);
            small.drawRight(String.valueOf(it.optInt("qty")), x + w - 14, ry + 14, G_TEXT);
            final int idx = i;
            if (ry >= ly && ry + 21 <= ly + lh) hit(x + 4, ry, w - 11, 21, () -> { selItemRef = items.optJSONObject(idx).optString("ref"); targetFor = null; });
        }
        g.restore();
        if (n == 0) small.draw("EMPTY", x + 12, ly + 12, G_LABEL);
        box(x, 232, w, 122);
        final JSONObject it = selItem >= 0 && selItem < n ? items.optJSONObject(selItem) : null;
        if (it == null) { small.draw("TAP AN ITEM", x + 10, 248, G_LABEL); return; }
        icon(it.optString("icon"), x + 9, 237);
        big.draw(fit(big, plain(it.optString("name")), w - 44), x + 32, 250, GOLD);
        int ty = 268;
        java.util.List<String> l = wrap(big, plain(it.optString("desc")), w - 18);
        for (int k = 0; k < l.size() && k < 3; k++) { big.draw(l.get(k), x + 9, ty, 0xFFFFFFFF); ty += 13; }
        ty += 3;
        for (String fl : wrap(big, plain(it.optString("fx")), w - 18)) { if (ty > 320) break; big.draw(fl, x + 9, ty, G_CYAN); ty += 13; }
        small.draw(catLabel(it.optString("cat")), x + 10, 346, G_LABEL);
        if (!it.optBoolean("use")) return;
        if (targetFor != null) button("CANCEL", x + w - 72, 327, 64, 22, false, G_TEXT, () -> targetFor = null);
        else button("USE", x + w - 72, 327, 64, 22, true, 0xFFFFFFFF, () -> targetFor = "do:use " + it.optString("ref"));
    }

    /** Order: the party laid out like the game's menu (fighting three on the left, reserve on the right); tap two to swap. */
    private void drawOrderTab(final JSONArray chars) {
        final int x = CX, w = CW;
        box(x, CT, w, 324);
        draw(sprite("panel-ornament-2-single-t1"), x + 2, CT + 2);
        band(x, CT + 2, w, "PARTY", "RESERVE");
        for (int i = 0; i < chars.length() && i < 8; i++) {
            JSONObject c = chars.optJSONObject(i);
            int colN = i < 3 ? 0 : 1, row = i < 3 ? i : i - 3;
            int resN = Math.max(0, Math.min(chars.length(), 8) - 3), op = colN == 0 || resN <= 4 ? 60 : 246 / resN, oh = op - 4;
            int cx = x + 5 + colN * 119, cy = CT + 18 + row * op;
            if (i == swapFirst) selRow(cx, cy, 117, oh); else { rect(cx, cy, 117, oh, 0xFF2C3850); rect(cx + 1, cy + 1, 115, oh - 2, G_PANEL); }
            Spr f = faceOf(c.optString("id"));
            if (f != null) g.drawBitmap(f.b, cx + 5, cy + oh - 12 - f.b.getHeight(), c.optBoolean("combat", true) ? spr : greyP);
            big.draw(fit(big, plain(c.optString("name")), 76), cx + 36, cy + 18, 0xFFFFFFFF);
            small.draw("HP", cx + 36, cy + oh - 24, G_LABEL); small.draw(c.optInt("hp") + "/" + c.optInt("hpMax"), cx + 54, cy + oh - 24, G_TEXT);
            small.draw("MP", cx + 36, cy + oh - 12, G_LABEL); small.draw(c.optInt("mp") + "/" + c.optInt("mpMax"), cx + 54, cy + oh - 12, G_TEXT);
            final int idx = i;
            hit(cx, cy, 117, oh, () -> pickChar(chars, idx));
        }
        String hintTxt = swapFirst < 0 ? "Tap two members to swap their places." : "Now tap who " + plain(chars.optJSONObject(Math.min(swapFirst, chars.length() - 1)).optString("name")) + " trades places with.";
        java.util.List<String> hl = wrap(big, hintTxt, w - 20);
        for (int k = 0; k < hl.size() && k < 2; k++) big.draw(hl.get(k), x + 10, 300 + k * 13, G_TEXT);
        if (swapFirst >= 0) button("CANCEL", x + w - 72, 327, 64, 22, false, G_TEXT, () -> swapFirst = -1);
    }

    private void drawRelicsTab() {
        JSONArray rraw = md.optJSONArray("relics");
        java.util.ArrayList<JSONObject> rs = new java.util.ArrayList<>();
        for (int i = 0; rraw != null && i < rraw.length(); i++) rs.add(rraw.optJSONObject(i));
        java.util.Collections.sort(rs, (a, b) -> b.optInt("pri") - a.optInt("pri"));       // the game lists them highest priority first
        final JSONArray rl = new JSONArray(rs);
        int n = rl.length();
        selRelic = -1;
        for (int i = 0; i < n; i++) if (rl.optJSONObject(i).optString("ref").equals(selRelicRef)) selRelic = i;
        final int x = CX, w = CW, ly = CT + 16, lh = 176;
        box(x, CT, w, 196);
        band(x, CT + 2, w, "ITEM NAME", "ENABLED");
        int so = scroll("relics", x + 3, ly, w - 6, lh, n * 22);
        g.save(); g.clipRect(x + 3, ly, x + w - 3, ly + lh);
        for (int i = 0; i < n; i++) {
            final JSONObject it = rl.optJSONObject(i);
            int ry = ly + i * 22 - so;
            if (ry + 22 <= ly || ry >= ly + lh) continue;
            if (i == selRelic) selRow(x + 4, ry, w - 11, 21);
            icon(it.optString("icon"), x + 12, ry + 3);
            big.draw(fit(big, plain(it.optString("name")), w - 88), x + 34, ry + 15, 0xFFFFFFFF);
            boolean on = it.optBoolean("on");
            int bx = x + w - 46;
            Spr sw = sprite(on ? "relic-switch-mobile-on" : "relic-switch-mobile-off");      // the game's own switch
            if (sw != null) draw(sw, bx + 32 - sw.b.getWidth(), ry + 10 - sw.b.getHeight() / 2);
            else {
                plate(bx - 1, ry + 3, 32, 15, 0xFF05070F); plate(bx, ry + 4, 30, 13, on ? 0xFF2E7D4F : 0xFF3A3F4E);
                small.draw(on ? "ON" : "OFF", bx + 15 - small.width(on ? "ON" : "OFF") / 2, ry + 14, on ? 0xFFFFFFFF : G_LABEL);
            }
            final int idx = i;
            if (ry >= ly && ry + 21 <= ly + lh) {
                hit(x + 4, ry, w - 11, 21, () -> selRelicRef = it.optString("ref"));
                if (it.optBoolean("can")) hit(bx - 12, ry, 56, 21, () -> { selRelicRef = it.optString("ref"); send("do:relic " + it.optString("ref")); });
            }
        }
        g.restore();
        if (n == 0) small.draw("NO RELICS YET", x + 12, ly + 12, G_LABEL);
        box(x, 232, w, 122);
        band(x, 234, w, "EFFECTS", null);
        JSONObject it = selRelic >= 0 && selRelic < n ? rl.optJSONObject(selRelic) : null;
        if (it == null) { small.draw("TAP A RELIC", x + 10, 262, G_LABEL); return; }
        int ty = 264;
        // as in the game: what the relic does in the panel, its one-line flavour text in the top bar (see relicHint)
        String fullTxt = it.optString("full");
        for (String s : wrap(big, plain(fullTxt.isEmpty() ? it.optString("desc") : fullTxt), w - 18)) { if (ty > 346) break; big.draw(s, x + 9, ty, 0xFFFFFFFF); ty += 13; }
    }
    private String selRelicRef = "";
    /** Top-bar line on the Relics screen: the selected relic's short description, else the screen's own hint. */
    private String relicHint() {
        JSONArray rl = md.optJSONArray("relics");
        for (int i = 0; rl != null && i < rl.length(); i++) if (rl.optJSONObject(i).optString("ref").equals(selRelicRef) && !selRelicRef.isEmpty()) return plain(rl.optJSONObject(i).optString("desc"));
        return HINT[5];
    }

    static class Spr { Bitmap b; int l, bo, r, t; }

    public DuoView(Context c, File filesDir) {
        super(c);
        cache = new File(filesDir, "duocache");
        mapDir = new File(filesDir, "duomaps");
        this.filesDir = filesDir;
        blit.setFilterBitmap(false); spr.setFilterBitmap(false); tintP.setFilterBitmap(false); greyP.setFilterBitmap(false);
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(); cm.setSaturation(0);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix(); dim.setScale(0.6f, 0.6f, 0.6f, 1); cm.postConcat(dim);
        greyP.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
    }

    public void update(String json) {
        if (json == null || json.equals(lastJson)) { if (needAssets()) invalidate(); return; }
        lastJson = json;
        try { snap = new JSONObject(json); } catch (Exception e) { return; }
        invalidate();
    }

    private boolean needAssets() { return big == null || !missing.isEmpty(); }

    // ---------------------------------------------------------------- assets

    private static Bitmap decode(File f, int[] hdrOut) throws Exception {
        byte[] d = Files.readAllBytes(f.toPath());
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int w = bb.getInt(), h = bb.getInt();
        for (int i = 0; i < 4; i++) hdrOut[i] = bb.getInt();
        if (w <= 0 || h <= 0 || d.length < 24 + w * h * 4) return null;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {           // file rows are bottom-up, RGBA
            int o = 24 + (h - 1 - y) * w * 4;
            for (int x = 0; x < w; x++, o += 4)
                px[y * w + x] = ((d[o + 3] & 0xFF) << 24) | ((d[o] & 0xFF) << 16) | ((d[o + 1] & 0xFF) << 8) | (d[o + 2] & 0xFF);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private Spr sprite(String name) {
        Spr s = sprites.get(name);
        if (s != null) return s;
        long now = System.currentTimeMillis();
        Long m = missing.get(name);
        if (m != null && now - m < 3000) return null;
        File f = new File(cache, name + ".bin");
        if (f.exists()) {
            try {
                int[] h = new int[4];
                Bitmap b = decode(f, h);
                if (b != null) {
                    s = new Spr(); s.b = b; s.l = h[0]; s.bo = h[1]; s.r = h[2]; s.t = h[3];
                    sprites.put(name, s); missing.remove(name);
                    return s;
                }
            } catch (Exception e) { Log.w("SosDuo", "sprite " + name, e); }
        }
        missing.put(name, now);
        return null;
    }

    /** First cached sprite whose name starts with the prefix (portraits have per-chapter variants). */
    private Spr spritePrefix(String prefix) {
        long now = System.currentTimeMillis();
        if (now - lastList > 4000) { lastList = now; String[] l = cache.list(); cacheNames = l == null ? new String[0] : l; }
        String best = null;
        for (String n : cacheNames) if (n.startsWith(prefix) && n.endsWith(".bin") && (best == null || n.length() < best.length())) best = n;
        if (best == null) { missing.put(prefix + "*", now); return null; }
        missing.remove(prefix + "*");
        return sprite(best.substring(0, best.length() - 4));
    }

    class Font {
        Bitmap atlas; final SparseArray<float[]> glyphs = new SparseArray<>();
        final Paint p = new Paint();
        Font() { p.setFilterBitmap(false); }
        int width(String s) { float w = 0; for (int i = 0; i < s.length(); i++) { float[] gl = glyphs.get(s.charAt(i)); w += gl == null ? 3 : gl[8]; } return Math.round(w); }
        /** x = left, y = baseline */
        void draw(String s, int x, int y, int color) {
            p.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            float pen = x;
            for (int i = 0; i < s.length(); i++) {
                float[] gl = glyphs.get(s.charAt(i));
                if (gl == null) { pen += 3; continue; }
                int gx = (int) gl[0], gy = (int) gl[1], gw = (int) gl[2], gh = (int) gl[3];
                if (gw > 0 && gh > 0) {
                    int dx = Math.round(pen + gl[6]), dy = Math.round(y - gl[7]);
                    src.set(gx, gy, gx + gw, gy + gh); dst.set(dx, dy, dx + gw, dy + gh);
                    g.drawBitmap(atlas, src, dst, p);
                }
                pen += gl[8];
            }
        }
        void drawRight(String s, int right, int y, int color) { draw(s, right - width(s), y, color); }
        /** The game's HUD numbers and labels: text with a one-pixel dark outline. */
        void drawOutline(String s, int x, int y, int color) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) draw(s, x + dx, y + dy, 0xFF05070F);
            draw(s, x, y, color);
        }
        void drawOutlineRight(String s, int right, int y, int color) { drawOutline(s, right - width(s), y, color); }
        void drawShadow(String s, int x, int y, int color) { draw(s, x + 1, y + 1, 0xFF05070F); draw(s, x, y, color); }
    }

    private Font font(String name) {
        File tsv = new File(cache, name + ".tsv"), bin = new File(cache, name + ".bin");
        if (!tsv.exists() || !bin.exists()) return null;
        try {
            int[] h = new int[4];
            Bitmap raw = decode(bin, h);
            if (raw == null) return null;
            int w = raw.getWidth(), ht = raw.getHeight();
            int[] px = new int[w * ht];
            raw.getPixels(px, 0, w, 0, 0, w, ht);
            for (int i = 0; i < px.length; i++) px[i] = (px[i] >>> 24) > 150 ? 0xFFFFFFFF : 0;   // bitmap font: alpha only
            Font f = new Font();
            f.atlas = Bitmap.createBitmap(px, w, ht, Bitmap.Config.ARGB_8888);
            for (String line : new String(Files.readAllBytes(tsv.toPath())).split("\n")) {
                String[] c = line.trim().split("\t");
                if (c.length < 10) continue;
                float[] gl = new float[9];
                gl[0] = Float.parseFloat(c[1]); gl[2] = Float.parseFloat(c[3]); gl[3] = Float.parseFloat(c[4]);
                gl[1] = ht - (Float.parseFloat(c[2]) + gl[3]);                                    // atlas rect is bottom-up
                gl[4] = Float.parseFloat(c[5]); gl[5] = Float.parseFloat(c[6]); gl[6] = Float.parseFloat(c[7]); gl[7] = Float.parseFloat(c[8]); gl[8] = Float.parseFloat(c[9]);
                f.glyphs.put(Integer.parseInt(c[0]), gl);
            }
            return f.glyphs.size() > 20 ? f : null;
        } catch (Exception e) { Log.w("SosDuo", "font " + name, e); return null; }
    }

    // ---------------------------------------------------------------- drawing helpers

    /** Flat panel with clipped corners, like the game's party and list panels. */
    private void plate(int x, int y, int w, int h, int color) {
        rect(x + 2, y, w - 4, h, color); rect(x + 1, y + 1, w - 2, h - 2, color); rect(x, y + 2, w, h - 4, color);
    }
    /** List / info box: dark fill, light edge, black outline. */
    private void box(int x, int y, int w, int h) {
        plate(x - 1, y - 1, w + 2, h + 2, 0xFF05070F); plate(x, y, w, h, G_EDGE); plate(x + 1, y + 1, w - 2, h - 2, G_PANEL);
    }
    /** A titled section, framed like the game's list boxes (title tab on the top edge). */
    private void section(int x, int y, int w, int h, String title) {
        box(x, y, w, h);
        rect(x + 6, y - 4, small.width(title) + 4, 8, 0xFF05070F);
        small.draw(title, x + 8, y + 2, GOLD);
    }
    private void gbar(int x, int y, int w, float frac, String fillSprite, int fallback) {   // 7 px tall bar with dark outline
        plate(x, y, w, 7, 0xFF05070F);
        int fw = Math.round((w - 2) * Math.max(0, Math.min(1, frac)));
        if (fw <= 0) return;
        Spr f = sprite(fillSprite);
        if (f != null) drawPart(f, 0, 0, Math.max(1, Math.round(f.b.getWidth() * Math.min(1, frac))), f.b.getHeight(), x + 1, y + 1, fw, 5, spr);
        else rect(x + 1, y + 1, fw, 5, fallback);
    }

    private void rect(int x, int y, int w, int h, int color) { fill.setColor(color); g.drawRect(x, y, x + w, y + h, fill); }

    private void draw(Spr s, int x, int y) { if (s != null) g.drawBitmap(s.b, x, y, spr); }

    private void drawPart(Spr s, int sx, int sy, int sw, int sh, int x, int y, int w, int h, Paint p) {
        if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return;
        src.set(sx, sy, sx + sw, sy + sh); dst.set(x, y, x + w, y + h);
        g.drawBitmap(s.b, src, dst, p);
    }

    /** 9-slice with the sprite's own borders; tint 0 = as is. */
    private void nine(Spr s, int x, int y, int w, int h, int tint) {
        if (s == null) return;
        Paint p = spr;
        if (tint != 0) { tintP.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)); p = tintP; }
        int bw = s.b.getWidth(), bh = s.b.getHeight(), l = s.l, r = s.r, t = s.t, b = s.bo;
        if (l + r > w) { l = w / 2; r = w - l; }
        if (t + b > h) { t = h / 2; b = h - t; }
        int[] sxs = {0, s.l, bw - s.r}, sws = {s.l, bw - s.l - s.r, s.r}, dxs = {x, x + l, x + w - r}, dws = {l, w - l - r, r};
        int[] sys = {0, s.t, bh - s.bo}, shs = {s.t, bh - s.t - s.bo, s.bo}, dys = {y, y + t, y + h - b}, dhs = {t, h - t - b, b};
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) drawPart(s, sxs[i], sys[j], sws[i], shs[j], dxs[i], dys[j], dws[i], dhs[j], p);
    }

    private void panel(int x, int y, int w, int h) {
        rect(x + 2, y + 2, w - 4, h - 4, NAVY);
        Spr b = sprite("panel-border-rounded");
        if (b != null) nine(b, x, y, w, h, 0);
        else { rect(x, y, w, 1, LINE); rect(x, y + h - 1, w, 1, LINE); rect(x, y, 1, h, LINE); rect(x + w - 1, y, 1, h, LINE); }
    }

    private void bar(int x, int y, int w, float frac, String fillSprite, int fallback) {
        Spr bg = sprite("lifebar-background");
        if (bg != null) nine(bg, x, y, w, 6, 0); else rect(x, y, w, 6, 0xFF05070F);
        int fw = Math.round((w - 2) * Math.max(0, Math.min(1, frac)));
        if (fw <= 0) return;
        Spr f = sprite(fillSprite);
        if (f != null) drawPart(f, 0, 0, Math.max(1, Math.round(f.b.getWidth() * Math.min(1, frac))), f.b.getHeight(), x + 1, y + 1, fw, 4, spr);
        else rect(x + 1, y + 1, fw, 4, fallback);
    }

    // ---------------------------------------------------------------- screen

    @Override protected void onDraw(Canvas c) {
        if (big == null) big = font("font_copy1056-webfontBitmap");
        if (small == null) small = font("font_kroegerBitmap");
        frame.eraseColor(0);
        String state = snap.optString("state", "boot");
        boolean inGame = state.equals("field") || state.equals("battle");
        if (big == null || small == null) rect(0, 0, LW, LH, 0xFF070A14);
        else if (inGame) drawGame(state.equals("battle"));
        else if (state.equals("cutscene")) rect(0, 0, LW, LH, 0xFF070A14);   // never show the set behind a cutscene
        else {
            rect(0, 0, LW, LH, 0xFF070A14);
            String t = "SEA OF STARS";
            big.draw(t, (LW - big.width(t)) / 2, LH / 2 - 4, GOLD);
            String m = snap.optString("msg", "");
            if (!m.isEmpty()) small.draw(m, (LW - small.width(m)) / 2, LH / 2 + 12, DIM);
        }
        if (inGame) postInvalidateDelayed(state.equals("battle") ? 250 : 100);     // 10 pictures a second keeps the level badge's stars moving
        dst.set(0, 0, LW * S, LH * S);
        c.drawBitmap(frame, null, dst, blit);
        long now = System.currentTimeMillis();
        if (inGame && needAssets() && now - lastScan > 6000) { lastScan = now; try { HostActivity.nativeAction("cache:scan"); } catch (Throwable ignored) { } }
    }

    // ---- "TOP SCREEN HUD" switch (battle view). Off = the game's own party panels, combo badge, lists and info bar on the
    // top screen are made transparent (they keep working underneath; the bottom screen shows the same things). The choice
    // is remembered in files/duo_tophud_hidden and only applies during battles.
    private Boolean hudHidden; private boolean hudApplied; private long hudSentAt;
    private boolean hudHidden() {
        if (hudHidden == null) hudHidden = new File(filesDir, "duo_tophud_hidden").exists();
        return hudHidden;
    }
    private void setHudHidden(boolean hide) {
        hudHidden = hide;
        File f = new File(filesDir, "duo_tophud_hidden");
        try { if (hide) f.createNewFile(); else f.delete(); } catch (Exception ignored) { }
        hudSentAt = 0;
    }
    /** Keeps the game in step with the switch: hidden while a battle is on (re-sent now and then, lists open and close), shown otherwise. */
    private void topHudSync(boolean battle) {
        long now = System.currentTimeMillis();
        boolean want = battle && hudHidden();
        if (want && now - hudSentAt > 1500) { hudSentAt = now; hudApplied = true; send("hud:fade 0"); }
        else if (!want && hudApplied) { hudApplied = false; send("hud:show"); }
    }
    private void drawHudSwitch() {
        // deliberately small: a setting, not a control. A dim "TOP HUD" tag with a dot (lit = the top screen's HUD is on).
        final boolean shown = !hudHidden();
        int x = 6, y = 28;
        String lab = "TOP HUD";
        int w = small.width(lab) + 16;
        plate(x, y, w, 11, 0x66101626);
        small.draw(lab, x + 4, y + 8, shown ? G_LABEL : 0xFF5E6778);
        rect(x + w - 9, y + 3, 5, 5, 0xFF05070F); rect(x + w - 8, y + 4, 3, 3, shown ? G_CYAN : 0xFF3A3F4E);
        hit(x - 4, y - 2, w + 16, 22, () -> setHudHidden(shown));
    }

    private Bitmap skyBmp;
    /** Backdrop: the night sky of the game's New Game screens - deep blue fading lighter towards the bottom, scattered stars. */
    private void drawBackdrop() {
        if (skyBmp == null) {
            int[] px = new int[LW * LH];
            for (int y = 0; y < LH; y++) {
                float t = y / (float) (LH - 1);
                int r = Math.round(5 + (16 - 5) * t), gg = Math.round(11 + (34 - 11) * t), b = Math.round(27 + (56 - 27) * t);
                int c = 0xFF000000 | (r << 16) | (gg << 8) | b;
                for (int x = 0; x < LW; x++) px[y * LW + x] = c;
            }
            java.util.Random rnd = new java.util.Random(7);
            for (int i = 0; i < 150; i++) {
                int x = rnd.nextInt(LW), y = 26 + rnd.nextInt(LH - 28), k = rnd.nextInt(10);
                int c = k < 6 ? 0xFF4A6FA0 : k < 9 ? 0xFF8FC0E8 : 0xFFFFFFFF;
                px[y * LW + x] = c;
                if (k == 9 && x > 0 && x < LW - 1 && y > 27 && y < LH - 1) { px[y * LW + x - 1] = px[y * LW + x + 1] = px[(y - 1) * LW + x] = px[(y + 1) * LW + x] = 0xFF6FA6D6; }
            }
            skyBmp = Bitmap.createBitmap(px, LW, LH, Bitmap.Config.ARGB_8888);
        }
        g.drawBitmap(skyBmp, 0, 0, spr);
    }

    private void drawGame(boolean battle) {
        drawBackdrop();
        worldLayout = worldLayout && (snap.optBoolean("wm") || snap.optBoolean("camp"));
        final boolean camp = snap.optBoolean("camp");
        if (!snap.optBoolean("wm") && !camp) chart();
        menuShown = !battle && tab != 0;
        hRects.clear(); hActs.clear(); sAreas.clear();
        topHudSync(battle);
        if (battle) { qRects.clear(); qActs.clear(); drawBattle(MAP_X, MAP_Y, MAP_W, MAP_H); drawHudSwitch(); }
        else if (menuShown) { qRects.clear(); qActs.clear(); drawMenus(); }
        else if (snap.optBoolean("wm") || camp && wmap != null) { if (!drawWorld()) drawWorldWindow(MAP_X, MAP_Y, MAP_W, MAP_H); drawObjective(6, 132, 150); }
        else {
            drawMap(MAP_X, MAP_Y, MAP_W, MAP_H); drawObjective(6, 132, 150);
            JSONObject tools = snap.optJSONObject("tools");
            if (tools != null && tools.optBoolean("map")) {             // the game's own world map, opened on the top screen
                String wl = "WORLD MAP";
                int wx = MAP_X + MAP_W - 22 - small.width(wl);
                small.draw(wl, wx, MAP_Y + 16, 0xFF5A3D1E);
                draw(sprite("general-arrow-mini"), wx + small.width(wl) + 3, MAP_Y + 9);
                hit(wx - 10, MAP_Y, small.width(wl) + 32, 28, () -> send("press:worldMapBtn"));
            }
        }
        if (!battle) drawNav();
        // header
        rect(0, 0, LW, 23, NAVY); rect(0, 23, LW, 1, LINE); rect(0, 24, LW, 1, 0xFF05070F);
        big.draw(menuShown ? fit(big, tab == 5 ? relicHint() : HINT[tab], LW - 150) : snap.optString("area"), 8, 16, menuShown ? 0xFFFFFFFF : GOLD);
        int x = LW - 8;
        long pt = snap.optLong("playtime", 0);
        String time = String.format(Locale.US, "%d:%02d", pt / 3600, (pt / 60) % 60);
        if (!menuShown) {       // the game's menu bar has no clock; leaving it out keeps room for the hint line
            big.drawRight(time, x, 16, TEXT); x -= big.width(time) + 4;
            Spr clock = sprite("menu-icn-clock"); if (clock != null) { x -= 15; draw(clock, x, 4); }
            x -= 12;
        }
        long gold = Math.max(0, snap.optLong("gold", -1));      // no purse entry yet = 0, which the game shows as "0"
        if (gold >= 0) {
            String gs = String.valueOf(gold);
            big.drawRight(gs, x, 16, TEXT); x -= big.width(gs) + 3;
            Spr coin = sprite("icn-inventory-currency"); if (coin != null) { x -= 21; draw(coin, x, 1); }
        }
        // party level badge, as in the game's menu bar: 80x20, black outline, dark blue, and the XP earned towards the next
        // level shown as the game's own scrolling starfield (the game's sprites, cached once its own menu has been opened)
        int plv = snap.optInt("plv", 0);
        if (plv > 0) {
            String ls = "LEVEL " + plv;
            int bw = 80, bh = 20, bxl = x - 8 - bw, byl = 2;
            int req = snap.optInt("xpReq", -1), rem = snap.optInt("xpRem", -1);
            float fr = req > 0 && rem >= 0 ? Math.max(0, Math.min(1, 1f - rem / (float) req)) : 0;
            plate(bxl, byl, bw, bh, 0xFF000000); rect(bxl + 1, byl + 1, bw - 2, bh - 2, 0xFF0B2446);
            int fw = Math.round((bw - 2) * fr);
            if (fw > 0) {
                // the game's own two layers: a blue gradient as wide as the whole bar, cut off at the XP earned, with
                // its star strip drifting across on top (UIXPBar: fill + scrollingBackground, 0.05 strip widths a second)
                Spr pr = sprite("experience-xp-bar-mobile-progress"), st = sprite("experience-xp-bar-mobile-stars");
                g.save(); g.clipRect(bxl + 1, byl + 1, bxl + 1 + fw, byl + bh - 1);
                if (pr != null) nine(pr, bxl + 1, byl + 1, bw - 2, bh - 2, 0); else rect(bxl + 1, byl + 1, fw, bh - 2, 0xFF245A9E);
                if (st != null) {
                    int tw = st.b.getWidth();
                    int ox = (int) ((System.currentTimeMillis() % 1000000L) * xpScroll * tw / 1000f) % tw;
                    for (int tx = bxl + 1 - ox; tx < bxl + 1 + fw; tx += tw) g.drawBitmap(st.b, tx, byl + 1, spr);
                }
                g.restore();
            }
            rect(bxl + 1, byl + 1, 1, 1, 0xFF000000); rect(bxl + bw - 2, byl + 1, 1, 1, 0xFF000000); rect(bxl + 1, byl + bh - 2, 1, 1, 0xFF000000); rect(bxl + bw - 2, byl + bh - 2, 1, 1, 0xFF000000);
            small.drawOutline(ls, bxl + (bw - small.width(ls)) / 2, byl + 14, 0xFF9AA8C0);
        }
        if (menuShown) return;
        // party, bottom-left, laid out like the game's own HUD
        JSONArray party = battle ? snap.optJSONArray("party") : padded(snap.optJSONArray("party"));
        JSONObject bxp = battle ? snap.optJSONObject("bx") : null;
        JSONObject inFight = bxp == null ? null : bxp.optJSONObject("moves");
        if (inFight != null && inFight.length() > 0 && party != null) {
            JSONArray f = new JSONArray();
            for (int i = 0; i < party.length(); i++) if (inFight.has(party.optJSONObject(i).optString("id"))) f.put(party.optJSONObject(i));
            if (f.length() > 0) party = f;
        }
        int n = party == null ? 0 : party.length();
        final int rp = n > 3 ? Math.max(11, Math.min(17, 60 / (n - 3))) : 17;      // height of one reserve line in the map view
        JSONObject faces = snap.optJSONObject("faces");
        JSONObject bxo = snap.optJSONObject("bx");
        JSONObject mn = snap.optJSONObject("menu");
        JSONObject kob = bxo == null ? null : bxo.optJSONObject("ko");
        for (int i = 0; i < n; i++) {
            // same panel as the game's HUD: 110x26, each one stepped 4 px to the right, blue while that character acts
            JSONObject m = party.optJSONObject(i);
            String id = m.optString("id"), cid = id.toLowerCase(Locale.ROOT);
            // aligned panels in every layout; outside battle the first three are the fighting party, the rest reserve
            int px = 8, py = LH - 8 - (n - i) * 30;
            if (!battle) {
                if (i == 0) section(6, 190, 150, 14 + Math.min(n, 3) * 30 + Math.max(0, n - 3) * rp + 2, "PARTY");
                px = 26; py = 200 + i * 30;
                if (i >= 3) {                                   // reserve member: one compact line
                    int cy = 200 + 90 + (i - 3) * rp;
                    plate(12, cy, 138, rp - 2, G_PANEL);
                    String rid = m.optString("id"), rc = rid.toLowerCase(Locale.ROOT);
                    Spr rf = faces != null && faces.has(rid) ? sprite(faces.optString(rid)) : null;
                    if (rf == null) rf = defSprite(rid, "face");
                    if (rf == null) rf = spritePrefix("team-hud-portrait-" + rc);
                    if (rf == null) rf = sprite("menu-icn-character-" + rc);
                    if (rf != null) drawPart(rf, 0, 0, rf.b.getWidth(), rf.b.getHeight(), 14, cy + 1, 15, rp - 4, greyP);
                    small.draw(plain(m.optString("name")).toUpperCase(Locale.ROOT), 33, cy + rp - 7, G_TEXT);
                    small.drawRight("HP " + m.optInt("hp") + "  MP " + m.optInt("mp"), 146, cy + rp - 7, G_LABEL);
                    continue;
                }
            }
            boolean acting = battle && mn != null && mn.optString("actor").equals(id);
            boolean targeted = battle && bxo != null && id.equals(bxo.optString("tgtId"));
            rect(px + 2, py + 3, 110, 26, 0x66000000);
            plate(px, py, 110, 26, acting ? G_SEL : G_PANEL);
            Spr face = faces != null && faces.has(id) ? sprite(faces.optString(id)) : null;
            if (face == null) face = defSprite(id, "face");
            if (face == null) face = spritePrefix("team-hud-portrait-" + cid);
            if (face == null) face = sprite("menu-icn-character-" + cid);
            boolean used = false;
            JSONArray offA = battle && bxo != null ? bxo.optJSONArray("off") : null;
            for (int k = 0; offA != null && k < offA.length(); k++) if (id.equals(offA.optString(k))) used = true;
            if (face != null) g.drawBitmap(face.b, px + 3, py + 26 - face.b.getHeight(), used ? greyP : spr);
            int hp = m.optInt("hp"), hpMax = Math.max(1, m.optInt("hpMax")), mp = m.optInt("mp"), mpMax = Math.max(1, m.optInt("mpMax"));
            small.drawOutline("HP", px + 31, py + 13, G_LABEL);
            boolean low = hp / (float) hpMax < 0.3f;     // the game turns the bar red when HP is low
            big.drawOutlineRight(String.valueOf(hp), px + 75, py + 14, used ? G_TEXT : 0xFFFFFFFF);
            if (low) { plate(px + 27, py + 15, 50, 7, 0xFF05070F); rect(px + 28, py + 16, 48, 5, 0xFF4A1E22); int fw = Math.round(48 * hp / (float) hpMax); if (fw > 0) rect(px + 28, py + 16, fw, 5, 0xFFE0453A); }
            else gbar(px + 27, py + 15, 50, hp / (float) hpMax, "lifebar-t1", 0xFFE9B36A);
            small.drawOutline("MP", px + 81, py + 13, G_LABEL);
            big.drawOutlineRight(String.valueOf(mp), px + 107, py + 14, used ? G_TEXT : 0xFFFFFFFF);
            gbar(px + 79, py + 15, 29, mp / (float) mpMax, "mpbar-mobile-color", 0xFF9A7BE0);
            if (targeted) draw(sprite("HandCursor"), px + 112, py + 5);
            if (battle && kob != null && kob.has(id)) {
                plate(px + 26, py + 2, 82, 22, 0xE61F2534);
                big.drawOutline("KO", px + 31, py + 17, 0xFFFF7B6B);
                small.draw(kob.optInt(id) + " turns", px + 54, py + 16, G_TEXT);
            }
            int mana = m.optInt("mana", 0);
            for (int k = 0; battle && k < mana && k < 3; k++) { rect(px + 113 + k * 7, py + 17, 6, 6, 0xFF05070F); rect(px + 114 + k * 7, py + 18, 4, 4, 0xFF7FE3FF); }
        }
        if (!battle) return;
        // combo points, above the party cards
        int unit = Math.max(1, snap.optInt("comboUnit", 1000)), max = Math.max(1, snap.optInt("comboMax", 3));
        float combo = snap.optInt("combo") / (float) unit;
        JSONObject x2 = snap.optJSONObject("bx");
        int bx = 8, by = LH - 8 - n * 30 - 52;
        if (x2 != null) {
            int ix = 62;
            if (x2.optBoolean("manaOn")) {              // Live Mana lying on the field
                small.draw("LIVE MANA", ix, by + 12, 0xFF7FE3FF);
                int fm = x2.optInt("fieldMana");
                for (int k = 0; k < Math.min(fm, 9); k++) { rect(ix + k * 8, by + 16, 6, 6, 0xFF0B1020); rect(ix + 1 + k * 8, by + 17, 4, 4, 0xFF7FE3FF); }
                if (fm == 0) small.draw("none", ix, by + 22, DIM);
            }
            if (!x2.optBoolean("comboShown", true)) return;
            if (x2.optBoolean("ultShown")) {
                // ultimate gauge = the frame around the combo plate; it fills from the left corner round through the
                // bottom, pink to blue (measured from the top screen)
                draw(sprite("team-hud-ult-background-t1"), bx - 5, by - 5);
                float fr = x2.optInt("ultFill", -1) >= 0 ? x2.optInt("ultFill") / 1000f : x2.optInt("ultMax") > 0 ? x2.optInt("ult") / (float) x2.optInt("ultMax") : 0;
                fr = Math.max(0, Math.min(1, fr));
                for (int yy = 0; yy < 55 && fr > 0; yy++) for (int xx = 0; xx < 55; xx++) {
                    int dx = xx - 27, dy = yy - 27, d = Math.abs(dx) + Math.abs(dy);
                    if (d < 24 || d > 26) continue;
                    float t = dx <= 0 && dy >= 0 ? 0.25f * dy / 27f : dx >= 0 && dy > 0 ? 0.25f + 0.25f * dx / 27f : dx > 0 ? 0.5f + 0.25f * -dy / 27f : 0.75f + 0.25f * -dx / 27f;
                    if (t > fr) continue;
                    int r = Math.round(238 + (130 - 238) * t), gg = Math.round(107 + (150 - 107) * t), bb = Math.round(238 + (255 - 238) * t);
                    rect(bx - 5 + xx, by - 5 + yy, 1, 1, 0xFF000000 | (r << 16) | (gg << 8) | bb);
                }
            }
        }
        // the game's own combo badge: diamond plate, label, one small diamond per point
        draw(sprite("team-hud-ap-background-shadow"), bx + 12, by + 14);
        draw(sprite("team-hud-ap-background-t1"), bx, by);
        float part = combo - (float) Math.floor(combo);
        if (combo >= max) part = 0;
        Spr prog = sprite("team-hud-ap-progress-bar");
        if (prog != null && part > 0) { int fh = Math.max(1, Math.round(43 * part)); drawPart(prog, 0, 43 - fh, 43, fh, bx + 1, by + 1 + 43 - fh, 43, fh, spr); }
        Spr lab = sprite(combo > 0 ? "team-hud-combo-label-filling-en" : "team-hud-combo-label-empty-en");
        if (lab != null) draw(lab, bx + 3, by + 16);
        // three slots stepping down the plate's top-right edge, as on the game's own HUD (measured from the top screen)
        Spr slot = sprite("team-hud-ap-slot-t1"), full = sprite("cp-mobile-full");
        for (int k = 0; k < max; k++) {
            int sx = bx + 24 + k * 8, sy = by - 9 + k * 8;
            if (slot != null) draw(slot, sx, sy); else rect(sx + 3, sy + 3, 7, 7, 0xFF2A2F3D);
            if (combo >= k + 1) { if (full != null) draw(full, sx, sy); else rect(sx + 4, sy + 4, 5, 5, GOLD); }
        }
    }

    private java.util.List<String> wrap(Font f, String text, int width) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String para : text.replace("\r", "").split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (line.length() > 0 && f.width(line + " " + word) > width) { out.add(line.toString()); line.setLength(0); }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    /** TMP rich text -> plain: button glyphs become a one-character marker (see drawRich), other tags are dropped. */
    private static String plain(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<sprite[^>]*name=\"([^\"]+)\"[^>]*>").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, String.valueOf(buttonMark(m.group(1))));
        m.appendTail(sb);
        return sb.toString().replaceAll("<sprite[^>]*>", "\u24B6").replaceAll("<[^>]*>", "");
    }
    /** The game names its prompts "Button 1..4" (face buttons) or by trigger; shown as a round key cap with the letter. */
    private static char buttonMark(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith("1")) return '\u24B6';   // A
        if (n.endsWith("2")) return '\u24B7';   // B
        if (n.endsWith("3")) return '\u24CD';   // X
        if (n.endsWith("4")) return '\u24CE';   // Y
        return '\u24B6';
    }
    private static boolean isMark(char c) { return c == '\u24B6' || c == '\u24B7' || c == '\u24CD' || c == '\u24CE'; }
    private int richWidth(Font f, String t) { int w = 0; for (String part : t.split("(?<=[\u24B6\u24B7\u24CD\u24CE])|(?=[\u24B6\u24B7\u24CD\u24CE])")) w += part.length() == 1 && isMark(part.charAt(0)) ? 13 : f.width(part); return w; }
    /** Text with key caps: x = left, y = baseline. */
    private void drawRich(Font f, String t, int x, int y, int color) {
        for (String part : t.split("(?<=[\u24B6\u24B7\u24CD\u24CE])|(?=[\u24B6\u24B7\u24CD\u24CE])")) {
            if (part.length() == 1 && isMark(part.charAt(0))) {
                char c = part.charAt(0);
                String letter = c == '\u24B6' ? "A" : c == '\u24B7' ? "B" : c == '\u24CD' ? "X" : "Y";
                plate(x + 1, y - 10, 11, 11, 0xFF05070F); plate(x + 2, y - 9, 9, 9, 0xFFE9ECF2);
                small.draw(letter, x + 7 - small.width(letter) / 2, y - 2, 0xFF05070F);
                x += 13;
            } else { f.draw(part, x, y, color); x += f.width(part); }
        }
    }

    /** Lines like "Zale: Attack x1, Sunball x2": usable moves that hit this enemy's remaining locks. */
    private java.util.List<String[]> breakers(JSONArray locks) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
        JSONObject bx = snap.optJSONObject("bx");
        JSONObject moves = bx == null ? null : bx.optJSONObject("moves");
        JSONArray party = snap.optJSONArray("party");
        if (moves == null || party == null || locks == null) return out;
        java.util.ArrayList<String> open = new java.util.ArrayList<>();
        for (int i = 0; i < locks.length(); i++) { JSONObject l = locks.optJSONObject(i); if (l.optBoolean("r", true)) open.add(l.optString("t")); }
        if (open.isEmpty()) return out;
        for (int i = 0; i < party.length(); i++) {
            JSONObject m = party.optJSONObject(i);
            JSONArray mv = moves.optJSONArray(m.optString("id"));
            if (mv == null || m.optInt("hp") <= 0) continue;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < mv.length(); k++) {
                JSONObject mo = mv.optJSONObject(k);
                if (!mo.optBoolean("ok", true) || mo.optInt("cat") == 3) continue;
                java.util.ArrayList<String> left = new java.util.ArrayList<>(open);
                int hit = 0;
                JSONArray t = mo.optJSONArray("t");
                for (int j = 0; t != null && j < t.length(); j++) if (left.remove(t.optString(j))) hit++;
                if (hit == 0) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(mo.optInt("cat") == 0 ? "Attack" : plain(mo.optString("n"))).append(" x").append(hit);
            }
            if (sb.length() > 0) out.add(new String[]{m.optString("name"), sb.toString()});
        }
        return out;
    }

    /** Battle board: one card per enemy (cast countdown, locks, who can break them, HP) and a mirror of the open list. */
    private void drawBattle(int x, int y, int w, int h) {
        JSONArray en = snap.optJSONArray("enemies");
        JSONObject menu = snap.optJSONObject("menu");
        JSONObject bx = snap.optJSONObject("bx");
        int tgt = bx == null ? -1 : bx.optInt("tgt", -1);
        JSONArray items = menu == null ? null : menu.optJSONArray("items");
        // the game pages long lists six rows at a time ("1/2"); show the same page
        final int PAGE = 6;
        int total0 = items == null ? 0 : items.length(), sel0 = menu == null ? -1 : menu.optInt("sel", -1);
        int pages = (total0 + PAGE - 1) / PAGE, page = Math.max(0, sel0) / PAGE, first = total0 > PAGE ? page * PAGE : 0;
        int rows = Math.min(total0 - first, total0 > PAGE ? PAGE : 8);
        int sel = menu == null ? -1 : menu.optInt("sel", -1);
        // description of the highlighted skill / combo
        java.util.List<String> desc = new java.util.ArrayList<>();
        JSONArray selTypes = null;
        if (rows > 0 && sel >= 0 && sel < items.length()) {
            JSONObject it = items.optJSONObject(sel);
            // Prefer the exact text of the game's top info bar (for items that is the effect, e.g. "Ally recovers 20 HP",
            // not the menu flavour text); fall back to the entry's own description.
            String d = bx == null ? "" : plain(bx.optString("info")).trim();
            if (d.isEmpty()) d = plain(it.optString("desc"));
            if (!d.isEmpty()) desc = wrap(big, d, w - 16 - (it.optJSONArray("t") == null ? 0 : Math.min(6, it.optJSONArray("t").length()) * 19 + 6));
            if (desc.size() > 3) desc = desc.subList(0, 3);
            selTypes = it.optJSONArray("t");
        }
        int descH = desc.isEmpty() ? 0 : Math.max(26, 10 + desc.size() * 13);
        int menuH = rows == 0 ? 0 : 8 + rows * 15, limit = y + h - menuH - descH - (descH > 0 ? 12 : 0) - 6;
        int ey = y;
        String dialog = bx == null ? "" : plain(bx.optString("dialog")).trim();
        if (!dialog.isEmpty()) {
            java.util.List<String> dl = wrap(big, dialog, w - 20);
            int bh = 12 + dl.size() * 13;
            box(x, ey, w, bh);
            draw(sprite("panel-ornament-2-single-t1"), x + 2, ey + 2);
            for (int k = 0; k < dl.size(); k++) drawRich(big, dl.get(k), x + (w - richWidth(big, dl.get(k))) / 2, ey + 17 + k * 13, 0xFFFFFFFF);
            ey += bh + 4;
        }
        for (int i = 0; en != null && i < en.length(); i++) {
            JSONObject e = en.optJSONObject(i);
            if (e.optBoolean("dead")) continue;
            JSONArray locks = e.optJSONArray("locks");
            int nl = locks == null ? 0 : locks.length(), total = Math.max(nl, e.optInt("totalLocks"));
            java.util.List<String[]> who = total > 0 ? breakers(locks) : new java.util.ArrayList<String[]>();
            int ch = (total > 0 ? 56 : 32) + who.size() * 10;
            if (ey + ch > limit) break;
            panel(x, ey, w, ch);
            if (i == tgt) { nine(sprite("panel-border-rounded"), x, ey, w, ch, GOLD); draw(sprite("HandCursor"), x - 6, ey + 6); }
            int turns = e.optInt("turns", -1);
            if (turns >= 0) {
                draw(sprite("battle-locks-clock-default"), x + 7, ey + 8); draw(sprite("battle-locks-clock-outline"), x + 7, ey + 6);
                String ts = String.valueOf(turns);
                small.draw(ts, x + 7 + (17 - small.width(ts)) / 2 + 1, ey + 19, turns <= 1 ? 0xFFFF7B6B : TEXT);
            }
            big.draw(e.optString("name"), x + 30, ey + 16, turns >= 0 && turns <= 1 && total > 0 ? 0xFFFFB0A0 : TEXT);
            boolean showHp = bx != null && bx.optBoolean("showHP") && !e.optBoolean("hideHP") && e.optInt("hpMax") > 0;
            if (showHp) {
                String hs = e.optInt("hp") + "/" + e.optInt("hpMax");
                small.drawRight(hs, x + w - 8, ey + 14, DIM);
                barRed(x + 30, ey + 20, w - 38, e.optInt("hp") / (float) e.optInt("hpMax"));
            }
            if (total > 0) {      // cast strength, as the game labels it above the locks
                small.drawOutline("PWR", x + 8 + Math.min(total, 10) * 22 + 4, ey + 40, GOLD);
                small.drawOutline(Math.round(100f * nl / total) + "%", x + 8 + Math.min(total, 10) * 22 + 4, ey + 49, 0xFFFFFFFF);
            }
            for (int k = 0; k < total && k < 10; k++) {
                int lx = x + 8 + k * 22, ly = ey + 30;
                if (k < nl) { JSONObject l = locks.optJSONObject(k); lock(lx, ly, l.optString("t"), l.optBoolean("r", true)); }
                else { Spr sh = sprite("battle-locks-socket-shadow-t1"); if (sh != null) draw(sh, lx, ly); else rect(lx, ly, 19, 21, 0x66000000); }   // already broken
            }
            for (int k = 0; k < who.size(); k++) {
                int ly = ey + 62 + k * 10;
                small.draw(who.get(k)[0].toUpperCase(Locale.ROOT), x + 8, ly, GOLD);
                String txt = who.get(k)[1];
                while (txt.length() > 4 && small.width(txt) > w - 60) txt = txt.substring(0, txt.length() - 2);
                small.draw(txt, x + 52, ly, TEXT);
            }
            ey += ch + 3;
        }
        if (rows == 0) {
            String info = bx == null ? "" : plain(bx.optString("info")).trim();
            if (!info.isEmpty()) {
                java.util.List<String> il = wrap(big, info, w - 16);
                int bh = 10 + Math.min(3, il.size()) * 13;
                box(x, y + h - bh, w, bh);
                for (int k = 0; k < il.size() && k < 3; k++) big.draw(il.get(k), x + 8, y + h - bh + 17 + k * 13, 0xFFFFFFFF);
            }
            return;
        }
        int my = y + h - menuH;
        if (descH > 0) {
            // description bar with the EFFECT tab, as on the game's top bar
            int dy = my - descH - 6;
            box(x, dy, w, descH);
            int nt = selTypes == null ? 0 : Math.min(selTypes.length(), 6);
            int tx = x + w - 6 - nt * 19;
            if (nt > 0) {
                small.drawOutline("EFFECT", tx, dy - 1, G_TEXT);
                Spr strip = sprite("battle-icn-lock-strip");
                for (int k = 0; k < nt; k++) {
                    String t = selTypes.optString(k);
                    int row = t.equals("Blunt") ? 0 : t.equals("Sword") ? 1 : t.equals("Sun") ? 2 : t.equals("Moon") ? 3 : -1;
                    if (row >= 0 && strip != null) drawPart(strip, 0, row * 17, 17, 17, tx + k * 19, dy + 4, 17, 17, spr);
                    else lock(tx + k * 19 - 1, dy + 2, t, true);
                }
            }
            int ty = dy + 17;
            java.util.List<String> lines = wrap(big, String.join(" ", desc), (nt > 0 ? tx - 6 : x + w - 8) - (x + 8));
            for (int k = 0; k < lines.size() && k < 3; k++) { big.draw(lines.get(k), x + 8, ty, 0xFFFFFFFF); ty += 13; }
        }
        // the list the game has open, drawn like the game's own: title tab, 15 px rows, blue selection, cost box
        box(x, my, w, menuH);
        String kind = menu.optString("kind");
        String title = kind.equals("commands") ? null : kind.toUpperCase(Locale.ROOT);
        JSONArray party = snap.optJSONArray("party");
        for (int i = 0; title == null && party != null && i < party.length(); i++)
            if (menu.optString("actor").equals(party.optJSONObject(i).optString("id"))) title = party.optJSONObject(i).optString("name").toUpperCase(Locale.ROOT);
        int tcol = G_CYAN;
        for (int i = 0; party != null && i < party.length(); i++) {
            JSONObject pm = party.optJSONObject(i);
            if (menu.optString("actor").equals(pm.optString("id"))) try { tcol = android.graphics.Color.parseColor(pm.optString("color")); } catch (Exception ignored) { }
        }
        if (title != null) { rect(x + 6, my - 4, small.width(title) + 4, 8, 0xFF05070F); small.draw(title, x + 8, my + 2, tcol); }
        Spr hand = sprite("HandCursor");
        if (pages > 1) {
            String pg = (page + 1) + "/" + pages;
            rect(x + w - small.width(pg) - 12, my + menuH - 5, small.width(pg) + 4, 8, 0xFF05070F);
            small.draw(pg, x + w - small.width(pg) - 10, my + menuH + 1, G_TEXT);
        }
        for (int i = 0; i < rows; i++) {
            JSONObject it = items.optJSONObject(first + i);
            int ry = my + 4 + i * 15;
            boolean on = first + i == sel;
            if (on) plate(x + 3, ry, w - 6, 15, G_SEL);
            int col = !it.optBoolean("ok", true) ? 0xFF5E6778 : on ? 0xFFFFFFFF : G_TEXT;
            small.draw(plain(it.optString("label")).toUpperCase(Locale.ROOT), x + 14, ry + 10, col);
            String sub = plain(it.optString("sub"));
            if (!sub.isEmpty()) {
                if (on) plate(x + w - 30, ry + 2, 22, 11, G_COST);
                small.draw(sub, x + w - 19 - small.width(sub) / 2, ry + 10, col);
            }
            if (on && hand != null) draw(hand, x - 12, ry);
        }
        // "BOOST [RT]" prompt under the command list while Live Mana is on the field, as the game shows it
        if (kind.equals("commands") && bx != null && bx.optBoolean("manaOn") && bx.optInt("fieldMana") + bx.optInt("bigMana") > 0) {
            int actorMana = 0;
            for (int i = 0; party != null && i < party.length(); i++)
                if (menu.optString("actor").equals(party.optJSONObject(i).optString("id"))) actorMana = party.optJSONObject(i).optInt("mana", 0);
            if (actorMana < 3) {
                Spr rt = sprite("dialog-generic-inputs-small_R2");
                int bw = small.width("BOOST") + 6 + (rt != null ? rt.b.getWidth() : 14);
                int bxx = x + w - bw - 4, byy = my - 16;
                small.drawOutline("BOOST", bxx, byy + 10, 0xFFFFFFFF);
                if (rt != null) draw(rt, bxx + small.width("BOOST") + 6, byy - 2);
                else { plate(bxx + small.width("BOOST") + 6, byy + 1, 14, 11, G_TEXT); small.draw("RT", bxx + small.width("BOOST") + 9, byy + 9, 0xFF05070F); }
            }
        }
    }

    private void barRed(int x, int y, int w, float frac) {
        Spr bg = sprite("lifebar-background");
        if (bg != null) nine(bg, x, y, w, 6, 0); else rect(x, y, w, 6, 0xFF05070F);
        int fw = Math.round((w - 2) * Math.max(0, Math.min(1, frac)));
        Spr f = sprite("battle-lifebar-red");
        if (fw > 0) { if (f != null && fw >= 4) nine(f, x + 1, y + 1, fw, 4, 0); else rect(x + 1, y + 1, fw, 4, 0xFFE0453A); }
    }

    /** One spell lock: the game's socket with the damage-type icon from its lock strip. */
    private void lock(int x, int y, String type, boolean revealed) {
        Spr socket = sprite("battle-locks-socket-t1");
        if (socket != null) draw(socket, x, y); else rect(x, y, 19, 21, 0xFF0B1020);
        if (!revealed) {      // type not discovered yet: the game shows a closed gold door, never the type
            Spr db = sprite("battle-locks-door-bottom-t1"), dt = sprite("battle-locks-door-top-t1");
            if (db != null && dt != null) { draw(db, x + 1, y + 1); draw(dt, x + 1, y + 1); } else big.draw("?", x + 7, y + 15, DIM);
            return;
        }
        int row = type.equals("Blunt") ? 0 : type.equals("Sword") ? 1 : type.equals("Sun") ? 2 : type.equals("Moon") ? 3 : -1;
        Spr strip = sprite("battle-icn-lock-strip");
        if (row >= 0 && strip != null) { drawPart(strip, 0, row * 17, 17, 17, x + 1, y + 2, 17, 17, spr); return; }
        int col = type.equals("Poison") ? 0xFF7CC75B : type.equals("Arcane") ? 0xFFE07BE8 : type.equals("Eclipse") ? 0xFF9A6BE0 : type.equals("Stun") ? 0xFFF3DD62 : 0xFFC9CFDC;
        rect(x + 4, y + 5, 11, 11, col);
        small.draw(type.isEmpty() ? "?" : type.substring(0, 1), x + 7, y + 14, 0xFF05070F);
    }
}
