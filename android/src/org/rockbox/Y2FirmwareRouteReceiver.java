package org.rockbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Executes a fixed route selected by LK and the boot ramdisk. */
public class Y2FirmwareRouteReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRMWARE_ROUTE = "org.rockbox.y2.FIRMWARE_ROUTE";
    public static final String EXTRA_ROUTE = "route";
    public static final String EXTRA_PHYSICAL_ROUTE = "physical_route";

    @Override
    public void onReceive(Context context, Intent intent) {
        Y2Marker.installUncaughtExceptionHandler();
        if (intent == null)
            return;

        String action = intent.getAction();
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            reassertRockboxAfterWake(context);
            return;
        }
        if (!ACTION_FIRMWARE_ROUTE.equals(action))
            return;

        String route = intent.getStringExtra(EXTRA_ROUTE);
        String physicalRoute = intent.getStringExtra(EXTRA_PHYSICAL_ROUTE);
        String selectedByRamdisk = Y2FirmwareRouteProperty.read();
        Y2Marker.write(context, "FirmwareRoute:received route=" + route
                + " physicalRoute=" + physicalRoute
                + " earlyInitRoute=" + selectedByRamdisk);
        if (selectedByRamdisk.length() == 0
                || !selectedByRamdisk.equals(physicalRoute)) {
            Y2Marker.write(context,
                    "FirmwareRoute:ignored physical route mismatch route=" + route
                    + " physicalRoute=" + physicalRoute
                    + " earlyInitRoute=" + selectedByRamdisk);
            return;
        }
        if (!Y2BootState.BOOT_ROCKBOX.equals(route)
                && !Y2BootState.BOOT_STOCK.equals(route)
                && !Y2BootState.BOOT_SETTINGS.equals(route)) {
            Y2Marker.write(context,
                    "FirmwareRoute:ignored invalid effective route=" + route
                    + " physicalRoute=" + physicalRoute);
            return;
        }

        Y2BootState.beginFirmwareRouteSession(
                context, route, "FirmwareRoute:authenticated-physical-route");

        if (Y2BootState.BOOT_ROCKBOX.equals(route)) {
            Y2BootState.prepareRockboxLaunch(context, "firmware-route");
            Intent coordinator = new Intent(context, Y2RouteShieldService.class);
            coordinator.setAction(Y2RouteShieldService.ACTION_BEGIN_BOOT);
            context.startService(coordinator);
            Y2Marker.write(context,
                    "FirmwareRoute:started single-owner boot shield coordinator");
            return;
        }

        if (Y2BootState.BOOT_SETTINGS.equals(route)) {
            Y2BootState.blockRockboxLaunch(context, Y2BootState.BOOT_SETTINGS,
                    "firmware-route");
            Intent coordinator = new Intent(context, Y2RouteShieldService.class);
            coordinator.setAction(Y2RouteShieldService.ACTION_BEGIN_SETTINGS);
            context.startService(coordinator);
            Y2Marker.write(context,
                    "FirmwareRoute:started protected Boot Settings coordinator");
            return;
        }

        Y2BootState.blockRockboxLaunch(context, Y2BootState.BOOT_STOCK,
                "firmware-route");
        Y2Marker.write(context, "FirmwareRoute:stock route, no activity launched");
    }

    /**
     * The stock launcher remains Android's HOME so it can complete the Y2's
     * vendor boot and power initialisation.  Once boot is complete, however,
     * every screen-on on the Rockbox firmware route must return the existing
     * singleTask Rockbox activity to the foreground.  This closes the late
     * sleep/wake ownership hole without killing the vendor process early.
     */
    private void reassertRockboxAfterWake(Context context) {
        if (!Y2BootState.isCurrentFirmwareRoute(
                context, Y2BootState.BOOT_ROCKBOX))
            return;

        if (!Y2FirmwareRouteProperty.isAndroidBootCompleted()) {
            Y2Marker.write(context,
                    "FirmwareRoute:screen-on ignored before Android boot complete");
            return;
        }

        Y2BootState.prepareRockboxLaunch(context, "screen-on-route-owner");
        Intent activity = new Intent(context, RockboxActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(activity);
        Y2Marker.write(context,
                "FirmwareRoute:screen-on reasserted Rockbox task ownership");
    }
}
