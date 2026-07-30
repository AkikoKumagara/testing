package org.rockbox;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;

public final class Y2SystemControl {
    private static final String STOCK_PACKAGE = "com.innioasis.y2";
    private static final String STOCK_SPLASH =
            "com.innioasis.y2/com.innioasis.y1.activity.SplashActivity";

    private Y2SystemControl() {
    }

    public static void suppressStockForBootCapture(Context context,
            String reason) {
        Y2Marker.write(context,
                "SystemControl:suppressStockForBootCapture begin reason="
                + reason);
        forceStopStock(context, reason);
    }

    public static void suppressStockForRockbox(Context context, String reason) {
        Y2Marker.write(context,
                "SystemControl:suppressStockForRockbox begin reason=" + reason);
        runCommandAsync(context, reason, "root-pm-disable-stock",
                new String[] {"su", "-c", "pm disable " + STOCK_PACKAGE});
        runCommandAsync(context, reason, "normal-pm-disable-stock",
                new String[] {"pm", "disable", STOCK_PACKAGE});
        forceStopStock(context, reason);
    }

    public static void enableStockForStockRoute(Context context, String reason) {
        Y2Marker.write(context,
                "SystemControl:enableStockForStockRoute begin reason=" + reason);
        runCommandAsync(context, reason, "root-pm-enable-stock",
                new String[] {"su", "-c", "pm enable " + STOCK_PACKAGE});
        runCommandAsync(context, reason, "normal-pm-enable-stock",
                new String[] {"pm", "enable", STOCK_PACKAGE});
        runCommandAsync(context, reason, "root-am-start-stock",
                new String[] {"su", "-c", "am start -n " + STOCK_SPLASH});
        runCommandAsync(context, reason, "normal-am-start-stock",
                new String[] {"am", "start", "-n", STOCK_SPLASH});
    }

    public static void requestRockboxForeground(Context context, String reason) {
        Y2Marker.write(context,
                "SystemControl:rockboxForeground begin reason=" + reason);
        runCommandAsync(context, reason, "normal-am-start",
                new String[] {
                    "am", "start", "-n", "org.rockbox/.RockboxActivity"
                });
        runCommandAsync(context, reason, "root-am-start",
                new String[] {
                    "su", "-c", "am start -n org.rockbox/.RockboxActivity"
                });
    }

    public static void requestReboot(Context context, String reason) {
        Y2Marker.write(context,
                "SystemControl:reboot begin reason=" + reason);
        runRebootAsync(context, reason);
    }

    public static boolean persistNormalBootTarget(Context context,
            String normalBoot) {
        if (!isPlayer(normalBoot)) {
            Y2Marker.write(context,
                    "SystemControl:normal-boot rejected invalid target");
            return false;
        }

        /*
         * Applications cannot write the root-owned early-boot route map.
         * This provider carries one validated choice to the root guard. The
         * guard derives Volume Up as the opposite player, fixes Volume Down
         * to Boot Settings, writes atomically, rereads, acknowledges, and only
         * then reboots.
         */
        String requestId = Long.toString(SystemClock.elapsedRealtime());
        String bootId = Y2BootState.currentBootId();
        ContentValues request = new ContentValues();
        request.put(Y2BootRouteProvider.PROTOCOL_VERSION,
                Y2BootRouteProvider.EXPECTED_PROTOCOL_VERSION);
        request.put(Y2BootRouteProvider.BOOT_ID, bootId);
        request.put(Y2BootRouteProvider.REQUEST_ID, requestId);
        request.put(Y2BootRouteProvider.NORMAL_BOOT, normalBoot);
        request.put(Y2BootRouteProvider.REBOOT, "1");
        try {
            Uri inserted = context.getContentResolver().insert(
                    Y2BootRouteProvider.REQUEST_URI, request);
            if (inserted == null) {
                Y2Marker.write(context,
                        "SystemControl:normal-boot request rejected");
                return false;
            }
            Y2Marker.write(context,
                    "SystemControl:normal-boot request submitted id="
                    + requestId + " target=" + normalBoot);
        } catch (Throwable t) {
            Y2Marker.write(context,
                    "SystemControl:normal-boot request exception", t);
            return false;
        }

        long deadline = SystemClock.elapsedRealtime() + 20000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            String responseId = null;
            String status = null;
            String responseProtocol = null;
            String responseBootId = null;
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        Y2BootRouteProvider.RESPONSE_URI,
                        null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    responseProtocol = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    Y2BootRouteProvider.PROTOCOL_VERSION));
                    responseBootId = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    Y2BootRouteProvider.BOOT_ID));
                    responseId = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    Y2BootRouteProvider.REQUEST_ID));
                    status = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                    Y2BootRouteProvider.STATUS));
                }
            } catch (Throwable t) {
                Y2Marker.write(context,
                        "SystemControl:normal-boot response query exception", t);
            } finally {
                if (cursor != null)
                    cursor.close();
            }
            boolean matchingResponse =
                    Y2BootRouteProvider.EXPECTED_PROTOCOL_VERSION.equals(
                            responseProtocol)
                    && bootId.equals(responseBootId)
                    && requestId.equals(responseId);
            if (matchingResponse && "ok".equals(status)) {
                clearBootRouteIpc(context, Y2BootRouteProvider.RESPONSE_URI,
                        "confirmed-response");
                Y2Marker.write(context,
                        "SystemControl:normal-boot save confirmed id="
                        + requestId + " target=" + normalBoot);
                return true;
            }
            if (matchingResponse && status != null
                    && status.startsWith("error")) {
                clearBootRouteIpc(context, Y2BootRouteProvider.RESPONSE_URI,
                        "rejected-response");
                Y2Marker.write(context,
                        "SystemControl:normal-boot save rejected id="
                        + requestId + " status=" + status);
                return false;
            }
            SystemClock.sleep(100L);
        }
        clearBootRouteIpc(context, Y2BootRouteProvider.REQUEST_URI,
                "timeout-request");
        Y2Marker.write(context,
                "SystemControl:normal-boot save timeout id=" + requestId);
        return false;
    }

    private static void clearBootRouteIpc(Context context, Uri uri,
            String reason) {
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Throwable t) {
            Y2Marker.write(context,
                    "SystemControl:normal-boot IPC cleanup exception reason="
                    + reason, t);
        }
    }

    private static boolean isPlayer(String action) {
        return Y2BootState.BOOT_ROCKBOX.equals(action)
                || Y2BootState.BOOT_STOCK.equals(action);
    }

    private static void runCommandAsync(final Context context,
            final String reason, final String label, final String[] command) {
        new Thread(new Runnable() {
            public void run() {
                runCommand(context, reason, label, command);
            }
        }, "y2-system-" + label).start();
    }

    private static void runRebootAsync(final Context context,
            final String reason) {
        new Thread(new Runnable() {
            public void run() {
                boolean root = runCommand(context, reason, "root-reboot",
                        new String[] {"su", "-c", "reboot"});
                if (!root) {
                    runCommand(context, reason, "normal-reboot",
                            new String[] {"reboot"});
                }
            }
        }, "y2-system-reboot").start();
    }

    private static void forceStopStock(Context context, String reason) {
        runCommandAsync(context, reason, "root-am-force-stop-stock",
                new String[] {
                    "su", "-c", "am force-stop " + STOCK_PACKAGE
                });
        runCommandAsync(context, reason, "normal-am-force-stop-stock",
                new String[] {"am", "force-stop", STOCK_PACKAGE});
    }

    private static boolean runCommand(Context context, String reason,
            String label, String[] command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            int result = process.waitFor();
            Y2Marker.write(context, "SystemControl:" + label
                    + " reason=" + reason + " exitCode=" + result);
            return result == 0;
        } catch (Throwable t) {
            Y2Marker.write(context, "SystemControl:" + label
                    + " reason=" + reason + " exception", t);
            return false;
        }
    }
}
