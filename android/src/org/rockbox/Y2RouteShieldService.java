package org.rockbox;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Owns the visible hand-off from Android boot/USB back to Rockbox.
 *
 * This service runs outside the native Rockbox process. A branded system window
 * remains above the stock launcher until Android has finished booting or both
 * exported storage volumes have remounted, and is removed only after Rockbox
 * posts a real framebuffer frame.
 */
public class Y2RouteShieldService extends Service {
    public static final String ACTION_BEGIN_BOOT =
            "org.rockbox.y2.ROUTE_SHIELD_BEGIN_BOOT";
    public static final String ACTION_BEGIN_SETTINGS =
            "org.rockbox.y2.ROUTE_SHIELD_BEGIN_SETTINGS";
    public static final String ACTION_USB_CONNECTED =
            "org.rockbox.y2.ROUTE_SHIELD_USB_CONNECTED";
    public static final String ACTION_USB_RETURN_BEGIN =
            "org.rockbox.y2.ROUTE_SHIELD_USB_RETURN_BEGIN";
    public static final String ACTION_USB_DISCONNECT_RECOVERY =
            "org.rockbox.y2.ROUTE_SHIELD_USB_DISCONNECT_RECOVERY";
    public static final String ACTION_STORAGE_CHANGED =
            "org.rockbox.y2.ROUTE_SHIELD_STORAGE_CHANGED";
    public static final String ACTION_NATIVE_EXIT_RECOVERY =
            "org.rockbox.y2.ROUTE_SHIELD_NATIVE_EXIT_RECOVERY";
    public static final String ACTION_FRAME_READY =
            "org.rockbox.y2.ROUTE_SHIELD_FRAME_READY";
    public static final String ACTION_ACTIVITY_READY =
            "org.rockbox.y2.ROUTE_SHIELD_ACTIVITY_READY";
    public static final String ACTION_SETTINGS_READY =
            "org.rockbox.y2.ROUTE_SHIELD_SETTINGS_READY";

    private static final long POLL_MS = 100L;
    private static final long STORAGE_QUIET_MS = 300L;
    private static final long FRAME_RETRY_MS = 6000L;
    private static final long SHIELD_HIDE_DELAY_MS = 120L;
    private static final long NATIVE_EXIT_WINDOW_MS = 60000L;
    private static final int MAX_NATIVE_EXIT_RECOVERIES = 3;

    private static final int MODE_IDLE = 0;
    private static final int MODE_BOOT = 1;
    private static final int MODE_USB_RETURN = 2;
    private static final int MODE_NATIVE_EXIT = 3;
    private static final int MODE_SETTINGS = 4;

    private static long nativeExitWindowStartMs;
    private static int nativeExitRecoveries;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View shield;
    private int mode = MODE_IDLE;
    private boolean launchRequested;
    private boolean frameRetryIssued;
    private long lastStorageChangeMs;
    private long launchRequestedMs;

    private final Runnable coordinator = new Runnable() {
        @Override
        public void run() {
            coordinate();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Y2Marker.installUncaughtExceptionHandler();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Y2Marker.write(this, "Y2RouteShieldService:onCreate process=route");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        Y2Marker.write(this, "Y2RouteShieldService:onStartCommand action=" + action
                + " mode=" + mode + " launchRequested=" + launchRequested);

        String requiredRoute = (ACTION_BEGIN_SETTINGS.equals(action)
                || ACTION_SETTINGS_READY.equals(action))
                ? Y2BootState.BOOT_SETTINGS : Y2BootState.BOOT_ROCKBOX;
        if (!Y2BootState.isCurrentFirmwareRoute(this, requiredRoute)) {
            hideShield("effective-route-mismatch");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_BEGIN_BOOT.equals(action)) {
            mode = MODE_BOOT;
            launchRequested = false;
            frameRetryIssued = false;
            showShield("boot");
            scheduleNow();
            return START_STICKY;
        }

        if (ACTION_BEGIN_SETTINGS.equals(action)) {
            mode = MODE_SETTINGS;
            launchRequested = false;
            frameRetryIssued = false;
            showShield("settings");
            scheduleNow();
            return START_STICKY;
        }

        if (ACTION_USB_CONNECTED.equals(action)) {
            handler.removeCallbacks(coordinator);
            mode = MODE_IDLE;
            launchRequested = false;
            frameRetryIssued = false;
            hideShield("usb-systemui-owner");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_USB_RETURN_BEGIN.equals(action)) {
            if (!Y2BootState.isUsbReturnPending(this)) {
                Y2Marker.write(this,
                        "Y2RouteShieldService:ignore unarmed USB return begin");
                hideShield("unarmed-usb-return");
                mode = MODE_IDLE;
                launchRequested = false;
                frameRetryIssued = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            mode = MODE_USB_RETURN;
            launchRequested = false;
            frameRetryIssued = false;
            lastStorageChangeMs = SystemClock.elapsedRealtime();
            showShield("usb-return-begin");
            scheduleNow();
            return START_STICKY;
        }

        if (ACTION_USB_DISCONNECT_RECOVERY.equals(action)) {
            mode = MODE_USB_RETURN;
            launchRequested = false;
            frameRetryIssued = false;
            lastStorageChangeMs = SystemClock.elapsedRealtime();
            showShield("usb-disconnect-recovery");
            scheduleNow();
            return START_STICKY;
        }

        if (ACTION_STORAGE_CHANGED.equals(action)) {
            if (!Y2BootState.isUsbReturnPending(this)) {
                Y2Marker.write(this,
                        "Y2RouteShieldService:ignore storage change without USB return");
                hideShield("storage-change-without-return");
                mode = MODE_IDLE;
                launchRequested = false;
                frameRetryIssued = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            mode = MODE_USB_RETURN;
            lastStorageChangeMs = SystemClock.elapsedRealtime();
            showShield("storage-changed");
            scheduleNow();
            return START_STICKY;
        }

        if (ACTION_NATIVE_EXIT_RECOVERY.equals(action)) {
            long now = SystemClock.elapsedRealtime();
            if (nativeExitWindowStartMs == 0L
                    || now - nativeExitWindowStartMs > NATIVE_EXIT_WINDOW_MS) {
                nativeExitWindowStartMs = now;
                nativeExitRecoveries = 0;
            }
            nativeExitRecoveries++;
            if (nativeExitRecoveries > MAX_NATIVE_EXIT_RECOVERIES) {
                Y2Marker.write(this,
                        "Y2RouteShieldService:native-exit recovery budget exhausted"
                        + " count=" + nativeExitRecoveries
                        + " windowStartMs=" + nativeExitWindowStartMs);
                hideShield("native-exit-budget-exhausted");
                mode = MODE_IDLE;
                launchRequested = false;
                frameRetryIssued = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            mode = MODE_NATIVE_EXIT;
            launchRequested = false;
            frameRetryIssued = false;
            lastStorageChangeMs = now;
            showShield("native-exit-recovery");
            Y2Marker.write(this,
                    "Y2RouteShieldService:native-exit recovery armed"
                    + " count=" + nativeExitRecoveries);
            scheduleNow();
            return START_STICKY;
        }

        if (ACTION_FRAME_READY.equals(action)) {
            if (launchRequested) {
                if (mode == MODE_USB_RETURN || mode == MODE_NATIVE_EXIT) {
                    Y2BootState.completeUsbStorageReturn(
                            this, "route-shield-rockbox-frame-ready");
                }
                handler.removeCallbacks(coordinator);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideShield("rockbox-first-frame");
                        mode = MODE_IDLE;
                        launchRequested = false;
                        frameRetryIssued = false;
                        stopSelf();
                    }
                }, SHIELD_HIDE_DELAY_MS);
            } else {
                Y2Marker.write(this,
                        "Y2RouteShieldService:ignore frame without owned launch");
                hideShield("frame-without-owned-launch");
                mode = MODE_IDLE;
                frameRetryIssued = false;
                stopSelf();
            }
            return START_STICKY;
        }

        if (ACTION_ACTIVITY_READY.equals(action)) {
            if (launchRequested
                    && (mode == MODE_USB_RETURN || mode == MODE_NATIVE_EXIT)) {
                Y2BootState.completeUsbStorageReturn(
                        this, "route-shield-existing-activity-ready");
                handler.removeCallbacks(coordinator);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideShield("rockbox-existing-activity-ready");
                        mode = MODE_IDLE;
                        launchRequested = false;
                        frameRetryIssued = false;
                        stopSelf();
                    }
                }, SHIELD_HIDE_DELAY_MS);
            } else {
                Y2Marker.write(this,
                        "Y2RouteShieldService:ignore existing activity readiness"
                        + " mode=" + mode
                        + " launchRequested=" + launchRequested);
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_SETTINGS_READY.equals(action)) {
            if (launchRequested && mode == MODE_SETTINGS) {
                handler.removeCallbacks(coordinator);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideShield("settings-first-window");
                        mode = MODE_IDLE;
                        launchRequested = false;
                        frameRetryIssued = false;
                        stopSelf();
                    }
                }, SHIELD_HIDE_DELAY_MS);
            } else {
                Y2Marker.write(this,
                        "Y2RouteShieldService:ignore settings readiness"
                        + " mode=" + mode
                        + " launchRequested=" + launchRequested);
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    private void coordinate() {
        if (mode == MODE_IDLE)
            return;

        if (!launchRequested) {
            if ((mode == MODE_BOOT || mode == MODE_SETTINGS)
                    && !"1".equals(readProperty("sys.boot_completed"))) {
                handler.postDelayed(coordinator, POLL_MS);
                return;
            }
            if ((mode == MODE_USB_RETURN || mode == MODE_NATIVE_EXIT)
                    && !storageReadyAndQuiet()) {
                handler.postDelayed(coordinator, POLL_MS);
                return;
            }
            if (mode == MODE_SETTINGS) {
                launchBootSettings("authenticated-route");
                handler.postDelayed(coordinator, FRAME_RETRY_MS);
                return;
            }
            String reason = mode == MODE_BOOT ? "boot-complete"
                    : (mode == MODE_USB_RETURN ? "usb-storage-ready"
                            : "native-exit-storage-ready");
            launchRockbox(reason);
            handler.postDelayed(coordinator, FRAME_RETRY_MS);
            return;
        }

        if (SystemClock.elapsedRealtime() - launchRequestedMs >= FRAME_RETRY_MS) {
            if (!frameRetryIssued) {
                frameRetryIssued = true;
                Y2Marker.write(this,
                        "Y2RouteShieldService:readiness timeout, one bounded retry"
                        + " mode=" + mode);
                if (mode == MODE_SETTINGS)
                    launchBootSettings("window-timeout-retry");
                else
                    launchRockbox("frame-timeout-retry");
            } else {
                Y2Marker.write(this,
                        "Y2RouteShieldService:frame retry exhausted, fail-open hide");
                hideShield("frame-timeout-fail-open");
                mode = MODE_IDLE;
                launchRequested = false;
                frameRetryIssued = false;
                stopSelf();
                return;
            }
        }
        handler.postDelayed(coordinator, FRAME_RETRY_MS);
    }

    private boolean storageReadyAndQuiet() {
        File primary = Environment.getExternalStorageDirectory();
        boolean primaryReady = Environment.MEDIA_MOUNTED.equals(
                Environment.getExternalStorageState())
                && primary != null && primary.canRead() && primary.canWrite();
        boolean secondaryExpected = Y2BootState.isSecondaryStorageExpected(this);
        boolean secondaryReady = !secondaryExpected
                || (new File("/storage/sdcard1").canRead()
                        && new File("/storage/sdcard1").canWrite());
        boolean quiet = SystemClock.elapsedRealtime() - lastStorageChangeMs
                >= STORAGE_QUIET_MS;
        return primaryReady && secondaryReady && quiet;
    }

    private void launchRockbox(String reason) {
        launchRequested = true;
        launchRequestedMs = SystemClock.elapsedRealtime();
        Y2BootState.prepareRockboxLaunch(this, "route-shield-" + reason);
        Intent activity = new Intent(this, RockboxActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(activity);
        Y2Marker.write(this,
                "Y2RouteShieldService:single-owner launch reason=" + reason);
    }

    private void launchBootSettings(String reason) {
        launchRequested = true;
        launchRequestedMs = SystemClock.elapsedRealtime();
        Intent activity = new Intent(this, Y2BootSettingsActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(activity);
        Y2Marker.write(this,
                "Y2RouteShieldService:Boot Settings launch reason=" + reason);
    }

    private void showShield(String reason) {
        if (shield != null)
            return;
        try {
            TextView message = new TextView(this);
            message.setBackgroundColor(Color.rgb(4, 3, 28));
            message.setTextColor(Color.WHITE);
            message.setTextSize(28.0f);
            message.setGravity(Gravity.CENTER);
            if ("settings".equals(reason)) {
                message.setText("Boot Settings\nStarting...");
            } else if (reason.indexOf("usb") >= 0
                    || reason.indexOf("native-exit") >= 0
                    || reason.indexOf("storage") >= 0) {
                message.setText("Rockbox\nRestoring storage...");
            } else {
                message.setText("Rockbox\nStarting...");
            }
            shield = message;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE);
            params.gravity = Gravity.LEFT | Gravity.TOP;
            params.setTitle("Y2 Rockbox route shield");
            windowManager.addView(shield, params);
            Y2Marker.write(this,
                    "Y2RouteShieldService:shield shown reason=" + reason);
        } catch (Throwable t) {
            shield = null;
            Y2Marker.write(this,
                    "Y2RouteShieldService:shield show failed reason=" + reason, t);
        }
    }

    private void hideShield(String reason) {
        if (shield == null)
            return;
        try {
            windowManager.removeViewImmediate(shield);
            Y2Marker.write(this,
                    "Y2RouteShieldService:shield hidden reason=" + reason);
        } catch (Throwable t) {
            Y2Marker.write(this,
                    "Y2RouteShieldService:shield hide failed reason=" + reason, t);
        } finally {
            shield = null;
        }
    }

    private void scheduleNow() {
        handler.removeCallbacks(coordinator);
        handler.post(coordinator);
    }

    private static String readProperty(String name) {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"getprop", name});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            process.waitFor();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
            if (process != null)
                process.destroy();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideShield("service-destroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
