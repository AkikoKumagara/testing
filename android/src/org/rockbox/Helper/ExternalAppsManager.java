package org.rockbox.Helper;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.graphics.drawable.Drawable;
import org.rockbox.Y2Marker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Manager class for handling external Android applications
 * List installed apps and launch them
 */
public class ExternalAppsManager {

    private Context context;
    private PackageManager packageManager;

    public static class AppInfo {
        public String packageName;
        public String appName;
        public String className;
        public boolean isSystemApp;
        public String action;

        public AppInfo(String packageName, String appName, String className, boolean isSystemApp) {
            this.packageName = packageName;
            this.appName = appName;
            this.className = className;
            this.isSystemApp = isSystemApp;
        }

        public AppInfo(String packageName, String appName, String className,
                boolean isSystemApp, String action) {
            this(packageName, appName, className, isSystemApp);
            this.action = action;
        }
    }

    public ExternalAppsManager(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
    }

    public List<AppInfo> getInstalledApps() {
        List<AppInfo> apps = new ArrayList<>();

        try {
            // Get all installed applications
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA);

            for (ApplicationInfo appInfo : installedApps) {
                // Skip system apps
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }

                // Get the main activity for this app
                Intent launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName);
                if (launchIntent != null) {
                    String appName = appInfo.loadLabel(packageManager).toString();
                    String className = launchIntent.getComponent().getClassName();

                    apps.add(new AppInfo(appInfo.packageName, appName, className, false));
                }
            }

            addY2SystemEntries(apps);

            // Sort apps alphabetically
            Collections.sort(apps, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo app1, AppInfo app2) {
                    return app1.appName.compareToIgnoreCase(app2.appName);
                }
            });

        } catch (Exception e) {
            Logger.d("Error getting installed apps", e);
        }

        return apps;
    }

    public boolean launchApp(String packageName) {
        try {
            Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                return true;
            }
        } catch (Exception e) {
            Logger.d("Error launching app: " + packageName, e);
        }
        return false;
    }

    public boolean launchApp(AppInfo appInfo) {
        if (appInfo == null)
            return false;
        try {
            if ("y2.bluetooth.settings".equals(appInfo.action)) {
                return launchBluetoothSettings();
            }
            if ("y2.fmradio".equals(appInfo.action)) {
                return launchFMRadio();
            }
            return launchApp(appInfo.packageName);
        } catch (Exception e) {
            Logger.d("Error launching special app: " + appInfo.appName, e);
            return false;
        }
    }

    public int getAppCount() {
        return getInstalledApps().size();
    }

    private void addY2SystemEntries(List<AppInfo> apps) {
        if (canResolve(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) {
            apps.add(new AppInfo("android.settings.BLUETOOTH_SETTINGS",
                    "Y2 Bluetooth Settings", "", true, "y2.bluetooth.settings"));
        }
        apps.add(new AppInfo("y2.fmradio", "Y2 FM Radio Probe",
                "", true, "y2.fmradio"));
    }

    private boolean launchBluetoothSettings() {
        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (canResolve(intent)) {
            context.startActivity(intent);
            Y2Marker.write(context, "ExternalAppsManager:launched bluetooth settings action");
            return true;
        }
        intent = new Intent();
        intent.setComponent(new ComponentName("com.android.settings",
                "com.android.settings.Settings$BluetoothSettingsActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (canResolve(intent)) {
            context.startActivity(intent);
            Y2Marker.write(context, "ExternalAppsManager:launched bluetooth settings component");
            return true;
        }
        intent = new Intent();
        intent.setComponent(new ComponentName("com.android.settings",
                "com.android.settings.bluetooth.BluetoothSettings"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (canResolve(intent)) {
            context.startActivity(intent);
            Y2Marker.write(context, "ExternalAppsManager:launched bluetooth settings alias");
            return true;
        }
        Y2Marker.write(context, "ExternalAppsManager:bluetooth settings unresolved");
        return false;
    }

    private boolean launchFMRadio() {
        String[][] components = new String[][] {
                {"com.innioasis.y1", "com.innioasis.y1.fm.FMMainActivity"},
                {"com.mediatek.FMRadio", "com.mediatek.FMRadio.FMRadioActivity"},
                {"com.mediatek.FMRadio", "com.mediatek.FMRadioActivity"},
                {"com.android.fmradio", "com.android.fmradio.FmMainActivity"},
                {"com.android.FMRadio", "com.android.FMRadio.FMRadioActivity"}
        };
        for (int i = 0; i < components.length; i++) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(components[i][0], components[i][1]));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (canResolve(intent)) {
                context.startActivity(intent);
                Y2Marker.write(context, "ExternalAppsManager:launched FM component="
                        + components[i][0] + "/" + components[i][1]);
                return true;
            }
        }

        if (launchFirstPackage(new String[] {
                "com.innioasis.y1",
                "com.mediatek.FMRadio",
                "com.mediatek.fmradio",
                "com.android.fmradio",
                "com.android.FMRadio"
        }, "FM Radio")) {
            return true;
        }

        Y2Marker.write(context,
                "ExternalAppsManager:FM unresolved; stock firmware has /dev/fm and com.innioasis.y1.fm classes");
        return false;
    }

    private boolean launchFirstPackage(String[] packages, String label) {
        for (int i = 0; i < packages.length; i++) {
            Intent intent = packageManager.getLaunchIntentForPackage(packages[i]);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Y2Marker.write(context, "ExternalAppsManager:launched "
                        + label + " package=" + packages[i]);
                return true;
            }
        }
        Y2Marker.write(context, "ExternalAppsManager:unresolved " + label);
        return false;
    }

    private boolean canResolveAnyPackage(String[] packages) {
        for (int i = 0; i < packages.length; i++) {
            if (packageManager.getLaunchIntentForPackage(packages[i]) != null)
                return true;
        }
        return false;
    }

    private boolean canResolve(Intent intent) {
        return packageManager.resolveActivity(intent, 0) != null;
    }

}
