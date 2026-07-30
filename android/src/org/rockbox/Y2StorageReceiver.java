package org.rockbox;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;

public class Y2StorageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Y2Marker.installUncaughtExceptionHandler();
        String action = intent == null ? "null" : intent.getAction();
        String physicalRoute = Y2FirmwareRouteProperty.read();
        String route = Y2BootState.getCurrentFirmwareRoute(context);
        if (!Y2BootState.BOOT_ROCKBOX.equals(route))
            return;

        Y2Marker.write(context, "Y2StorageReceiver:onReceive action=" + action
                + " state=" + Environment.getExternalStorageState()
                + " route=" + route
                + " physicalRoute=" + physicalRoute
                + " " + Y2BootState.describe(context));

        if ("android.intent.action.UMS_CONNECTED".equals(action)) {
            boolean directLaunch = launchUsbStorageUi(context);
            Y2BootState.noteUsbStorageWindow(context, action);
            Y2BootState.noteStorageEvent(context, action, false);
            Intent coordinator = new Intent(context, Y2RouteShieldService.class);
            coordinator.setAction(Y2RouteShieldService.ACTION_USB_CONNECTED);
            context.startService(coordinator);
            Y2Marker.write(context, "Y2StorageReceiver:USB connection armed"
                    + " directLaunch=" + directLaunch
                    + " rootBroker=authoritative");
            return;
        }

        if ("com.innioasis.y1.PRE_MOUNT_SDCARD".equals(action)
                || "android.intent.action.UMS_DISCONNECTED".equals(action)) {
            /*
             * The Y2 emits UMS_DISCONNECTED during ordinary USB-state and
             * boot transitions, even when mass storage was never exported.
             * Raising the route shield for those broadcasts can cover a
             * sleeping Rockbox surface indefinitely.  A real return is
             * already armed by MEDIA_UNMOUNTED/EJECT/SHARED, so require that
             * persisted session evidence before taking display ownership.
             */
            if (!Y2BootState.isUsbReturnPending(context)) {
                if ("android.intent.action.UMS_DISCONNECTED".equals(action)
                        && Y2BootState.isUsbStorageWindowActive(context)) {
                    Intent coordinator = new Intent(
                            context, Y2RouteShieldService.class);
                    coordinator.setAction(
                            Y2RouteShieldService.ACTION_USB_DISCONNECT_RECOVERY);
                    context.startService(coordinator);
                    Y2Marker.write(context,
                            "Y2StorageReceiver:recover unexported USB disconnect");
                    return;
                }
                Y2Marker.write(context,
                        "Y2StorageReceiver:ignore unarmed USB return action="
                        + action);
                return;
            }
            Intent coordinator = new Intent(context, Y2RouteShieldService.class);
            coordinator.setAction(Y2RouteShieldService.ACTION_USB_RETURN_BEGIN);
            context.startService(coordinator);
            Y2Marker.write(context,
                    "Y2StorageReceiver:shielded USB return begin action=" + action);
            return;
        }

        if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
            if (!Y2BootState.isUsbReturnPending(context)) {
                Y2Marker.write(context,
                        "Y2StorageReceiver:ignore mounted event without USB return"
                        + " action=" + action);
                return;
            }
            Y2BootState.noteUsbStorageWindow(context, action);
            Y2BootState.noteStorageEvent(context, action, true);
            Intent coordinator = new Intent(context, Y2RouteShieldService.class);
            coordinator.setAction(Y2RouteShieldService.ACTION_STORAGE_CHANGED);
            context.startService(coordinator);
            Y2Marker.write(context,
                    "Y2StorageReceiver:single-owner storage event "
                    + Y2BootState.describe(context));
            return;
        }

        if (Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
            Y2Marker.write(context,
                    "Y2StorageReceiver:scanner-finished is not a launch trigger");
            return;
        }

        if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                || Intent.ACTION_MEDIA_EJECT.equals(action)
                || "android.intent.action.MEDIA_SHARED".equals(action)) {
            boolean mediaExport = Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_EJECT.equals(action)
                    || "android.intent.action.MEDIA_SHARED".equals(action);
            if (mediaExport && !Y2BootState.isRockboxUsbSessionReady(context)
                    && !Y2BootState.isUsbReturnPending(context)) {
                Y2Marker.write(context,
                        "Y2StorageReceiver:ignore media hold before Rockbox session"
                        + " action=" + action);
                return;
            }
            Y2BootState.noteUsbStorageWindow(context, action);
            Y2BootState.noteStorageEvent(context, action, false);
            if (mediaExport) {
                Y2BootState.noteExpectedRockboxExit(context, action);
            }
            Y2Marker.write(context,
                    "Y2StorageReceiver:hold for usb-storage reason=" + action + " "
                    + Y2BootState.describe(context));
            return;
        }

        Y2Marker.write(context, "Y2StorageReceiver:ignore non-usb action=" + action
                + " " + Y2BootState.describe(context));
    }

    private boolean launchUsbStorageUi(Context context) {
        try {
            Intent activity = new Intent();
            activity.setComponent(new ComponentName(
                    "com.android.systemui",
                    "com.android.systemui.usb.UsbStorageActivity"));
            activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(activity);
            Y2Marker.write(context,
                    "Y2StorageReceiver:UsbStorageActivity requested directly");
            return true;
        } catch (Throwable t) {
            Y2Marker.write(context,
                    "Y2StorageReceiver:direct UsbStorageActivity request failed", t);
            return false;
        }
    }
}
