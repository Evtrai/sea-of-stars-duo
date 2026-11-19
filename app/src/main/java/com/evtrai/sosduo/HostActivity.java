package com.evtrai.sosduo;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Hosts the Play-installed Sea of Stars Unity player inside this app's process
 * (top screen) and shows a companion Presentation on the second display.
 *
 * Nothing from com.unity3d.* is compiled in: the game's own classes are loaded
 * from its installed APK through createPackageContext(CONTEXT_INCLUDE_CODE).
 */
public class HostActivity extends Activity {
    static final String GAME = "com.playdigious.seaofstars";
    static final String TAG = "SosDuo";

    private Context gameCtx;
    private ApplicationInfo hostedAi;
    private Resources hostedRes;
    private FrameLayout unityView;     // com.unity3d.player.UnityPlayer extends FrameLayout
    private Class<?> unityCls;
    private Companion companion;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean nativeOk;
    private int pollCount;

    // ---- context redirection -------------------------------------------------

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            gameCtx = base.createPackageContext(GAME,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ApplicationInfo g = gameCtx.getApplicationInfo();
            ApplicationInfo h = new ApplicationInfo(base.getApplicationInfo());
            h.nativeLibraryDir = g.nativeLibraryDir;
            h.sourceDir = g.sourceDir;
            h.publicSourceDir = g.publicSourceDir;
            h.splitSourceDirs = g.splitSourceDirs;
            h.splitPublicSourceDirs = g.splitPublicSourceDirs;
            h.splitNames = g.splitNames;
            hostedAi = h;
            // The game's Java code (Firebase, Play services, Unity) resolves resources using R ids
            // compiled into the game, so it needs the game's resource table. Lookups by name that
            // pass OUR package name are redirected to the game's package.
            Resources gr = gameCtx.getResources();
            final String host = base.getPackageName();
            hostedRes = new Resources(gr.getAssets(), gr.getDisplayMetrics(), gr.getConfiguration()) {
                @Override public int getIdentifier(String name, String defType, String defPackage) {
                    if (host.equals(defPackage)) defPackage = GAME;
                    if (name != null && name.startsWith(host + ":")) name = GAME + name.substring(host.length());
                    return super.getIdentifier(name, defType, defPackage);
                }
            };
            Log.i(TAG, "game libs=" + g.nativeLibraryDir + " base=" + g.sourceDir
                    + " splits=" + (g.splitSourceDirs == null ? 0 : g.splitSourceDirs.length));
        } catch (Exception e) {
            Log.e(TAG, "Sea of Stars not found", e);
        }
    }

    @Override public ApplicationInfo getApplicationInfo() {
        return hostedAi != null ? hostedAi : super.getApplicationInfo();
    }
    @Override public ClassLoader getClassLoader() {
        return gameCtx != null ? gameCtx.getClassLoader() : super.getClassLoader();
    }
    @Override public Resources getResources() {
        return hostedRes != null ? hostedRes : super.getResources();
    }
    @Override public AssetManager getAssets() {
        return gameCtx != null ? gameCtx.getAssets() : super.getAssets();
    }
    @Override public String getPackageCodePath() {
        return hostedAi != null ? hostedAi.sourceDir : super.getPackageCodePath();
    }
    @Override public String getPackageResourcePath() {
        return hostedAi != null ? hostedAi.sourceDir : super.getPackageResourcePath();
    }

    // ---- lifecycle -------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(null); // never restore Unity view state
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (gameCtx == null) {
            TextView t = new TextView(this);
            t.setText("Sea of Stars (com.playdigious.seaofstars) is not installed.");
            setContentView(t);
            return;
        }
        try {
            System.loadLibrary("sosduo");
            nativeOk = true;
        } catch (Throwable t) {
            Log.e(TAG, "libsosduo load failed", t);
        }
        try {
            ClassLoader cl = gameCtx.getClassLoader();
            unityCls = cl.loadClass("com.unity3d.player.UnityPlayer");
            Class<?> lifeCls = cl.loadClass("com.unity3d.player.IUnityPlayerLifecycleEvents");
            Object life = Proxy.newProxyInstance(cl, new Class<?>[]{lifeCls}, (p, m, a) -> {
                Log.i(TAG, "Unity lifecycle: " + m.getName());
                if ("onUnityPlayerQuitted".equals(m.getName())) finish();
                return null;
            });
            unityView = (FrameLayout) unityCls.getConstructor(Context.class, lifeCls).newInstance(this, life);
            setContentView(unityView);
            unityView.requestFocus();
            Log.i(TAG, "UnityPlayer created");
        } catch (Throwable t) {
            Log.e(TAG, "UnityPlayer creation failed", t);
            TextView tv = new TextView(this);
            tv.setText("Failed to start Sea of Stars:\n" + Log.getStackTraceString(t));
            setContentView(tv);
            return;
        }
        if (nativeOk) { nativeStart(); nativeAction("dir:" + getExternalFilesDir(null).getAbsolutePath()); }
        showCompanion();
    }

    private void call(String name, Class<?>[] types, Object... args) {
        if (unityView == null) return;
        try {
            Method m = unityCls.getMethod(name, types);
            m.invoke(unityView, args);
        } catch (Throwable t) {
            Log.w(TAG, "UnityPlayer." + name + " failed", t);
        }
    }
    private void call(String name) { call(name, new Class<?>[0]); }

    @Override protected void onResume() { super.onResume(); call("resume"); }
    @Override protected void onPause() { super.onPause(); call("pause"); }
    @Override protected void onDestroy() {
        if (companion != null) companion.dismiss();
        call("destroy");
        super.onDestroy();
    }
    @Override public void onLowMemory() { super.onLowMemory(); call("lowMemory"); }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) call("lowMemory");
    }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (hostedRes != null) hostedRes.updateConfiguration(c, hostedRes.getDisplayMetrics());
        call("configurationChanged", new Class<?>[]{Configuration.class}, c);
    }
    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        call("windowFocusChanged", new Class<?>[]{boolean.class}, f);
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        call("newIntent", new Class<?>[]{Intent.class}, i);
    }

    // Input goes to the Unity view exactly like UnityPlayerActivity does.
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (unityView != null && e.getAction() == KeyEvent.ACTION_MULTIPLE) {
            try { return (boolean) unityCls.getMethod("injectEvent", android.view.InputEvent.class).invoke(unityView, e); }
            catch (Throwable ignored) { }
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean onKeyUp(int k, KeyEvent e) { return unityView != null ? unityView.onKeyUp(k, e) : super.onKeyUp(k, e); }
    @Override public boolean onKeyDown(int k, KeyEvent e) { return unityView != null ? unityView.onKeyDown(k, e) : super.onKeyDown(k, e); }
    @Override public boolean onTouchEvent(MotionEvent e) { return unityView != null ? unityView.onTouchEvent(e) : super.onTouchEvent(e); }
    @Override public boolean onGenericMotionEvent(MotionEvent e) { return unityView != null ? unityView.onGenericMotionEvent(e) : super.onGenericMotionEvent(e); }

    // ---- companion on the second display --------------------------------------

    private void showCompanion() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display own = getWindowManager().getDefaultDisplay();
        Display target = null;
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != own.getDisplayId()) { target = d; break; }
        }
        if (target == null) { Log.w(TAG, "no second display"); return; }
        probe = true;   // engine layer is always on (PLAN.md Phase 0 results)
        Log.i(TAG, "second display " + target + " probe=" + probe);
        companion = new Companion(this, target);
        companion.show();
        ui.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (companion == null) return;
            String js = nativeOk ? new String(nativePoll(), java.nio.charset.StandardCharsets.UTF_8) : "{}";
            companion.setText(js);
            lastJs = js;
            // The area view (second world camera) only runs while the player is in control. At the title, in
            // loading screens and in cutscenes it is off and the bottom screen is dark: the camera would show
            // the set behind a fade, and touching Unity before the title sequence finished changed its flow.
            boolean inGame = js.contains("\"state\":\"field\"") || js.contains("\"state\":\"battle\"");
            // Clean top screen: party status, battle status and zone name live on the bottom screen now.
            // The game rebuilds its HUD per level, so this is re-applied every few seconds while in control.
            // OFF by default: deactivating the panels at battle start left the encounter stuck in EncounterIdleState (2026-11-16).
            if (nativeOk && inGame && new java.io.File(getExternalFilesDir(null), "duo_hidehud.txt").exists() && ++hudTick % 20 == 1) nativeAction("hud:off");
            // Overworld: the map window on the bottom screen shows the game's own world, drawn by the engine from
            // further out (a real-art area map). Dungeons use the charted parchment map instead, so the camera is off.
            boolean wantView = AREA_ZOOM > 0 && inGame && js.contains("\"wm\":true") && js.contains("\"state\":\"field\"");
            if (nativeOk && wantView != areaOn) { areaOn = wantView; nativeAction(wantView ? "map:" + AREA_ZOOM + " " + DuoView.VIEW_OX + " " + DuoView.VIEW_OY : "map:0"); }
            // debug channel: adb writes one action per line into files/duo_cmd.txt
            java.io.File cmd = new java.io.File(getExternalFilesDir(null), "duo_cmd.txt");
            if (nativeOk && cmd.exists()) {
                try {
                    for (String line : new String(java.nio.file.Files.readAllBytes(cmd.toPath())).split("\n"))
                        if (!line.trim().isEmpty()) {
                            String c = line.trim();
                            Log.i(TAG, "cmd " + c);
                            if (c.equals("java:snap")) java.nio.file.Files.write(new java.io.File(getExternalFilesDir(null), "duo_snap.json").toPath(), lastJs.getBytes());
                            else if (c.startsWith("java:")) companion.javaCmd(c.substring(5).split(" ")); else nativeAction(c);
                        }
                } catch (Throwable t) { Log.w(TAG, "cmd read failed", t); }
                cmd.delete();
            }
            ui.postDelayed(this, js.contains("\"state\":\"battle\"") ? 50 : js.contains("\"state\":\"field\"") ? 100 : 250);
        }
    };

    // ---- engine layer (PLAN.md Phase 0): give Unity the bottom screen as its display 1 ----
    boolean probe;
    static final float AREA_ZOOM = 0f;     // live overworld window: off (user did not like it, 2026-11-16); illustrated map planned
    private boolean areaOn;
    private int hudTick;
    private String lastJs = "";
    void engineDisplay(android.view.Surface s) {
        if (unityView == null) return;
        try {
            Object r = unityCls.getMethod("displayChanged", int.class, android.view.Surface.class).invoke(unityView, 1, s);
            Log.i(TAG, "displayChanged(1, " + (s == null ? "null" : "surface") + ") -> " + r);
        } catch (Throwable t) {
            Log.w(TAG, "displayChanged failed", t);
        }
        if (s != null && nativeOk) {
            areaOn = false;   // (re)enabled by poll() once the game is in the field
        }
    }

    static class Companion extends Presentation {
        private DuoView view;
        private final HostActivity host;
        Companion(HostActivity outer, Display d) { super(outer, d); host = outer; }
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            // Never take key focus from the game: controller input must stay on the top screen.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            FrameLayout root = new FrameLayout(getContext());
            if (host.probe) {
                sv = new android.view.SurfaceView(getContext());
                sv.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                    @Override public void surfaceCreated(android.view.SurfaceHolder h) { }
                    @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int ht) {
                        Log.i(TAG, "engine surface " + w + "x" + ht);
                        host.engineDisplay(h.getSurface());
                    }
                    @Override public void surfaceDestroyed(android.view.SurfaceHolder h) { host.engineDisplay(null); }
                });
                // Unity draws display 1 as a 1920x1080 target and only fills the centred 1240 columns
                // (x 340..1580), measured 2026-11-16. Size the view 1920 wide and shift it so that part shows.
                root.addView(sv, new FrameLayout.LayoutParams(1920, 1080));
                sv.setTranslationX(-340);
            }
            view = new DuoView(getContext(), host.getExternalFilesDir(null));
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root, new FrameLayout.LayoutParams(-1, -1));
        }
        void setText(String json) { if (view != null) view.update(json); }
        android.view.SurfaceView sv;
        void javaCmd(String[] a) {
            if (a[0].equals("pad")) { DuoView.padParty = Integer.parseInt(a[1]); view.invalidate(); return; }
            if (a[0].equals("xps")) { DuoView.xpScroll = Float.parseFloat(a[1]); return; }
            if (a[0].equals("tab")) { view.openTab(Integer.parseInt(a[1])); return; }
            if (sv == null) return;
            if (a[0].equals("fixed")) sv.getHolder().setFixedSize(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
            else if (a[0].equals("auto")) sv.getHolder().setSizeFromLayout();
            else if (a[0].equals("detach")) host.engineDisplay(null);
            else if (a[0].equals("attach")) host.engineDisplay(sv.getHolder().getSurface());
            else if (a[0].equals("layout")) { sv.setLayoutParams(new FrameLayout.LayoutParams(Integer.parseInt(a[1]), Integer.parseInt(a[2]))); sv.setTranslationX(Float.parseFloat(a[3])); }
            else if (a[0].equals("wm")) { DuoView.wmMode = Integer.parseInt(a[1]); if (a.length > 3) { DuoView.wmDx = Integer.parseInt(a[2]); DuoView.wmDy = Integer.parseInt(a[3]); } view.invalidate(); }
            else if (a[0].equals("alpha")) view.setAlpha(Float.parseFloat(a[1]));
        }
    }

    // ---- native reader ----------------------------------------------------------
    private static native void nativeStart();
    private static native byte[] nativePoll();
    /** Queue an action for Unity's main thread: "pick:<i>" or "back". */
    static native void nativeAction(String action);
    /** [w,h (int32 LE)] + RGBA rows bottom-up, or null while not extracted yet. */
    static native byte[] nativePortrait(String characterId);
}
