package org.rockbox;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * System HOME router and root-started early route shield.
 *
 * LK and the selected ramdisk remain the route authority. Each ramdisk publishes
 * y2.firmware.route during early-init. The init-owned route script passes the
 * authenticated physical route and user-mapped effective destination directly;
 * this avoids Android 4.4's pre-boot broadcast prohibition. Once authenticated,
 * this Activity remains Android's preferred HOME recovery point. Stock HOME is
 * never allowed to become the fallback for Rockbox or Boot Settings.
 */
public class Y2FirmwareHomeActivity extends Activity {
    private static final long POLL_MS = 250L;
    private static final long DISPATCH_DEBOUNCE_MS = 1500L;
    private static final String STOCK_PACKAGE = "com.innioasis.y2";
    private static final String STOCK_SPLASH =
            "com.innioasis.y1.activity.SplashActivity";

    private final Handler handler = new Handler();
    private long lastDispatchAt = 0L;
    private String lastDispatchedRoute = "";
    private String physicalRoute = "";
    private String effectiveRoute = "";
    private boolean preferredHomePinned = false;

    private final Runnable routePoll = new Runnable() {
        @Override
        public void run() {
            dispatchSelectedRoute();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Y2Marker.installUncaughtExceptionHandler();
        super.onCreate(savedInstanceState);
        readAuthenticatedRoute(getIntent(), "onCreate");
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (!Y2BootState.BOOT_STOCK.equals(effectiveRoute)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        View shield = new View(this);
        shield.setBackgroundColor(Color.BLACK);
        setContentView(shield);
        pinPreferredHome();
        Y2Marker.write(this, "FirmwareHome:onCreate root-bootstrap"
                + " physicalRoute=" + physicalRoute
                + " effectiveRoute=" + effectiveRoute);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readAuthenticatedRoute(intent, "onNewIntent");
        Y2Marker.write(this, "FirmwareHome:onNewIntent"
                + " physicalRoute=" + physicalRoute
                + " effectiveRoute=" + effectiveRoute);
        handler.removeCallbacks(routePoll);
        handler.post(routePoll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        readAuthenticatedRoute(getIntent(), "onResume");
        pinPreferredHome();
        Y2Marker.write(this, "FirmwareHome:onResume"
                + " physicalRoute=" + physicalRoute
                + " effectiveRoute=" + effectiveRoute);
        handler.removeCallbacks(routePoll);
        handler.post(routePoll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(routePoll);
        Y2Marker.write(this, "FirmwareHome:onPause");
        super.onPause();
    }

    private void readAuthenticatedRoute(Intent launch, String reason) {
        String earlyInitRoute = Y2FirmwareRouteProperty.read();
        String intentPhysical = launch == null ? "" : launch.getStringExtra(
                Y2FirmwareRouteReceiver.EXTRA_PHYSICAL_ROUTE);
        String intentEffective = launch == null ? "" : launch.getStringExtra(
                Y2FirmwareRouteReceiver.EXTRA_ROUTE);
        if (intentPhysical == null)
            intentPhysical = "";
        if (intentEffective == null)
            intentEffective = "";

        if (earlyInitRoute.length() > 0
                && earlyInitRoute.equals(intentPhysical)
                && isValidRoute(intentEffective)) {
            physicalRoute = intentPhysical;
            effectiveRoute = intentEffective;
            return;
        }

        String currentRoute = Y2BootState.getCurrentFirmwareRoute(this);
        if (earlyInitRoute.length() > 0 && isValidRoute(currentRoute)) {
            physicalRoute = earlyInitRoute;
            effectiveRoute = currentRoute;
            Y2Marker.write(this, "FirmwareHome:restored current-boot route"
                    + " reason=" + reason
                    + " physicalRoute=" + physicalRoute
                    + " effectiveRoute=" + effectiveRoute);
            return;
        }

        physicalRoute = "";
        effectiveRoute = "";
        Y2Marker.write(this, "FirmwareHome:hold unauthenticated HOME invocation"
                + " reason=" + reason
                + " earlyInitRoute=" + earlyInitRoute
                + " intentPhysical=" + intentPhysical
                + " intentEffective=" + intentEffective
                + " currentRoute=" + currentRoute);
    }

    private boolean isValidRoute(String route) {
        return Y2BootState.BOOT_ROCKBOX.equals(route)
                || Y2BootState.BOOT_STOCK.equals(route)
                || Y2BootState.BOOT_SETTINGS.equals(route);
    }

    /**
     * Android 4.4 otherwise remembers Stock SplashActivity as HOME and can
     * restore it after sleep or task loss. This system app owns the preferred
     * HOME filter permanently and routes to the authenticated target itself.
     */
    private void pinPreferredHome() {
        if (preferredHomePinned)
            return;
        try {
            PackageManager manager = getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> resolved = manager.queryIntentActivities(
                    home, PackageManager.MATCH_DEFAULT_ONLY);
            ArrayList<ComponentName> candidates = new ArrayList<ComponentName>();
            int bestMatch = IntentFilter.MATCH_CATEGORY_EMPTY;
            if (resolved != null) {
                for (ResolveInfo info : resolved) {
                    if (info == null || info.activityInfo == null)
                        continue;
                    ComponentName candidate = new ComponentName(
                            info.activityInfo.packageName, info.activityInfo.name);
                    if (!candidates.contains(candidate))
                        candidates.add(candidate);
                    if (info.match > bestMatch)
                        bestMatch = info.match;
                }
            }

            ComponentName router = new ComponentName(
                    getPackageName(), Y2FirmwareHomeActivity.class.getName());
            if (!candidates.contains(router))
                candidates.add(router);
            ComponentName stock = new ComponentName(STOCK_PACKAGE, STOCK_SPLASH);
            if (!candidates.contains(stock))
                candidates.add(stock);

            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            manager.clearPackagePreferredActivities(getPackageName());
            manager.clearPackagePreferredActivities(STOCK_PACKAGE);
            manager.addPreferredActivity(filter, bestMatch,
                    candidates.toArray(new ComponentName[candidates.size()]), router);
            preferredHomePinned = true;
            Y2Marker.write(this, "FirmwareHome:preferred HOME pinned"
                    + " router=" + router.flattenToShortString()
                    + " candidates=" + candidates.size()
                    + " bestMatch=" + bestMatch);
        } catch (Throwable t) {
            Y2Marker.write(this,
                    "FirmwareHome:preferred HOME pin failed; root component gate remains",
                    t);
        }
    }

    private void dispatchSelectedRoute() {
        String selectedByRamdisk = Y2FirmwareRouteProperty.read();
        String route = effectiveRoute;
        if (selectedByRamdisk.length() == 0
                || !selectedByRamdisk.equals(physicalRoute)) {
            Y2Marker.write(this, "FirmwareHome:hold invalid physical route"
                    + " physicalRoute=" + physicalRoute
                    + " earlyInitRoute=" + selectedByRamdisk);
            handler.postDelayed(routePoll, POLL_MS);
            return;
        }
        if (!isValidRoute(route)) {
            Y2Marker.write(this, "FirmwareHome:hold invalid effective route=" + route);
            handler.postDelayed(routePoll, POLL_MS);
            return;
        }
        Y2BootState.beginFirmwareRouteSession(
                this, route, "FirmwareHome:dispatchSelectedRoute");
        if (Y2BootState.BOOT_STOCK.equals(route)) {
            launchStock();
            return;
        }

        if (Y2BootState.BOOT_SETTINGS.equals(route)) {
            startOwnedRoute(route);
            return;
        }

        if (Y2BootState.BOOT_ROCKBOX.equals(route)) {
            if (Y2BootState.isRockboxRelaunchBlocked(this)) {
                Y2Marker.write(this,
                        "FirmwareHome:hold black shield reason=rapid-relaunch-blocked "
                        + Y2BootState.describe(this));
                return;
            }
            if (isStorageReady()) {
                startOwnedRoute(route);
            } else {
                Y2Marker.write(this,
                        "FirmwareHome:hold rockbox until storage mounted state="
                        + Environment.getExternalStorageState());
                handler.postDelayed(routePoll, POLL_MS);
            }
            return;
        }

        Y2Marker.write(this, "FirmwareHome:waiting for early-init route=" + route);
        handler.postDelayed(routePoll, POLL_MS);
    }

    private void launchStock() {
        if (!allowDispatch(Y2BootState.BOOT_STOCK)) {
            handler.postDelayed(routePoll, POLL_MS);
            return;
        }

        try {
            Intent stock = new Intent();
            stock.setComponent(new ComponentName(STOCK_PACKAGE, STOCK_SPLASH));
            stock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(stock);
            Y2Marker.write(this, "FirmwareHome:started stock SplashActivity");
            finish();
        } catch (Throwable t) {
            Y2Marker.write(this, "FirmwareHome:stock launch failed", t);
            handler.postDelayed(routePoll, POLL_MS);
        }
    }

    private void startOwnedRoute(String route) {
        if (!allowDispatch(route)) {
            handler.postDelayed(routePoll, POLL_MS);
            return;
        }

        Intent coordinator = new Intent(this, Y2RouteShieldService.class);
        if (Y2BootState.BOOT_ROCKBOX.equals(route)) {
            Y2BootState.prepareRockboxLaunch(this, "root-bootstrap");
            coordinator.setAction(Y2RouteShieldService.ACTION_BEGIN_BOOT);
        } else {
            Y2BootState.blockRockboxLaunch(this, Y2BootState.BOOT_SETTINGS,
                    "root-bootstrap");
            coordinator.setAction(Y2RouteShieldService.ACTION_BEGIN_SETTINGS);
        }
        startService(coordinator);
        Y2Marker.write(this, "FirmwareHome:started root-owned route=" + route
                + " physicalRoute=" + physicalRoute);
    }

    private boolean allowDispatch(String route) {
        long now = SystemClock.elapsedRealtime();
        if (route.equals(lastDispatchedRoute)
                && now - lastDispatchAt < DISPATCH_DEBOUNCE_MS) {
            return false;
        }
        lastDispatchedRoute = route;
        lastDispatchAt = now;
        return true;
    }

    private boolean isStorageReady() {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
            return false;
        File directory = Environment.getExternalStorageDirectory();
        return directory != null && directory.isDirectory()
                && directory.canRead() && directory.canWrite();
    }

}
