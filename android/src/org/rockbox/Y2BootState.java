package org.rockbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class Y2BootState {
    public static final String BOOT_ASK = "ask";
    public static final String BOOT_ROCKBOX = "rockbox";
    public static final String BOOT_STOCK = "stock";
    public static final String BOOT_SETTINGS = "settings";
    public static final String BOOT_NONE = "none";
    public static final String BOOT_DEFAULT = "default";
    public static final String SELECT_UNSET = "unset";

    private static final String PREFS = "y2_boot_state";
    private static final String KEY_DEFAULT_BOOT = "default_boot";
    private static final String KEY_SELECTED_FIRMWARE = "selected_firmware";
    private static final String KEY_BOOT_ELAPSED_MS = "boot_elapsed_ms";
    private static final String KEY_FIRST_CHOICE_MADE = "first_choice_made";
    private static final String KEY_NO_BUTTON_ACTION = "no_button_action";
    private static final String KEY_VOLUME_UP_ACTION = "volume_up_action";
    private static final String KEY_VOLUME_DOWN_ACTION = "volume_down_action";
    private static final String KEY_MENU_ACTION = "menu_action";
    private static final String KEY_CENTER_ACTION = "center_action";
    private static final String KEY_LEFT_ACTION = "left_action";
    private static final String KEY_RIGHT_ACTION = "right_action";
    private static final String KEY_PLAY_ACTION = "play_action";
    private static final String KEY_BOOT_KEY_CAPTURE_ACTIVE = "boot_key_capture_active";
    private static final String KEY_BOOT_SETTINGS_ACTIVE = "boot_settings_active";
    private static final String KEY_ROCKBOX_LAUNCH_ALLOWED = "rockbox_launch_allowed";
    private static final String KEY_ROCKBOX_LAUNCH_REASON = "rockbox_launch_reason";
    private static final String KEY_STOCK_LOCKOUT_UNTIL_MS = "stock_lockout_until_ms";
    private static final String KEY_SELECTED_ROUTE = "selected_route";
    private static final String KEY_DETECTED_BUTTON = "detected_button";
    private static final String KEY_RESOLVED_ACTION = "resolved_action";
    private static final String KEY_SETTINGS_VERSION = "settings_version";
    private static final String KEY_USB_STORAGE_GRACE_UNTIL_MS = "usb_storage_grace_until_ms";
    private static final String KEY_STORAGE_EVENT_ACTION = "storage_event_action";
    private static final String KEY_STORAGE_EVENT_ELAPSED_MS = "storage_event_elapsed_ms";
    private static final String KEY_STORAGE_RETURN_CANDIDATE = "storage_return_candidate";
    private static final String KEY_SECONDARY_STORAGE_EXPECTED = "secondary_storage_expected";
    private static final String KEY_USB_RETURN_PENDING = "usb_return_pending";
    private static final String KEY_EXPECTED_ROCKBOX_EXIT = "expected_rockbox_exit";
    private static final String KEY_EXPECTED_ROCKBOX_EXIT_AT_MS = "expected_rockbox_exit_at_ms";
    private static final String KEY_ROCKBOX_PROCESS_PID = "rockbox_process_pid";
    private static final String KEY_ROCKBOX_PROCESS_START_MS = "rockbox_process_start_ms";
    private static final String KEY_ROCKBOX_PROCESS_HEALTHY = "rockbox_process_healthy";
    private static final String KEY_ROCKBOX_PROCESS_BOOT_ID = "rockbox_process_boot_id";
    private static final String KEY_RAPID_ROCKBOX_FAILURES = "rapid_rockbox_failures";
    private static final String KEY_ROCKBOX_RELAUNCH_BLOCKED = "rockbox_relaunch_blocked";
    private static final String KEY_FIRMWARE_SESSION_BOOT_ID = "firmware_session_boot_id";
    private static final String KEY_FIRMWARE_SESSION_ROUTE = "firmware_session_route";
    private static final String KEY_FIRMWARE_SESSION_START_MS = "firmware_session_start_ms";
    private static final String KEY_FIRMWARE_SESSION_BOOT_WALL_MS =
            "firmware_session_boot_wall_ms";
    private static final String KEY_ROCKBOX_USB_SESSION_READY =
            "rockbox_usb_session_ready";
    private static final long USB_STORAGE_GRACE_MS = 120000L;
    private static final long EXPECTED_EXIT_WINDOW_MS = 120000L;
    private static final long RAPID_FAILURE_WINDOW_MS = 30000L;
    private static final int MAX_RAPID_FAILURES = 2;
    private static final long BOOT_WALL_TOLERANCE_MS = 5000L;

    private Y2BootState() {
    }

    public static void resetSession(Context context) {
        prefs(context).edit()
                .putString(KEY_SELECTED_FIRMWARE, SELECT_UNSET)
                .putLong(KEY_BOOT_ELAPSED_MS, SystemClock.elapsedRealtime())
                .putBoolean(KEY_BOOT_KEY_CAPTURE_ACTIVE, false)
                .putBoolean(KEY_BOOT_SETTINGS_ACTIVE, false)
                .putBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, false)
                .putString(KEY_SELECTED_ROUTE, SELECT_UNSET)
                .putString(KEY_DETECTED_BUTTON, "unknown")
                .putString(KEY_RESOLVED_ACTION, SELECT_UNSET)
                .putString(KEY_ROCKBOX_LAUNCH_REASON, "reset-session")
                .putBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)
                .putInt(KEY_RAPID_ROCKBOX_FAILURES, 0)
                .commit();
    }

    public static void noteBoot(Context context) {
        prefs(context).edit()
                .putLong(KEY_BOOT_ELAPSED_MS, SystemClock.elapsedRealtime())
                .putBoolean(KEY_BOOT_KEY_CAPTURE_ACTIVE, false)
                .putBoolean(KEY_BOOT_SETTINGS_ACTIVE, false)
                .putBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, false)
                .putString(KEY_SELECTED_ROUTE, SELECT_UNSET)
                .putString(KEY_DETECTED_BUTTON, "unknown")
                .putString(KEY_RESOLVED_ACTION, SELECT_UNSET)
                .putString(KEY_ROCKBOX_LAUNCH_REASON, "boot-start")
                .putBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)
                .putInt(KEY_RAPID_ROCKBOX_FAILURES, 0)
                .commit();
    }

    public static void migrateV17Selection(Context context) {
        SharedPreferences p = prefs(context);
        String defaultBoot = normalizeDefault(p.getString(KEY_DEFAULT_BOOT, BOOT_ASK));
        String selected = normalizeSelected(p.getString(KEY_SELECTED_FIRMWARE, SELECT_UNSET));
        if (!BOOT_ASK.equals(defaultBoot) || SELECT_UNSET.equals(selected))
            return;

        p.edit()
                .putString(KEY_DEFAULT_BOOT, selected)
                .putBoolean(KEY_FIRST_CHOICE_MADE, true)
                .commit();
    }

    public static void setSelectedFirmware(Context context, String firmware) {
        String normalized = normalizeSelected(firmware);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_SELECTED_FIRMWARE, normalized);
        applyLaunchPolicy(editor, normalized, "set-selected");
        editor.commit();
    }

    public static void chooseDefaultFirmware(Context context, String firmware) {
        String normalized = normalizeSelected(firmware);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_SELECTED_FIRMWARE, normalized)
                .putBoolean(KEY_FIRST_CHOICE_MADE, true);
        if (BOOT_ROCKBOX.equals(normalized) || BOOT_STOCK.equals(normalized))
            editor.putString(KEY_DEFAULT_BOOT, normalized);
        applyLaunchPolicy(editor, normalized, "choose-default");
        editor.commit();
    }

    public static void prepareRockboxLaunch(Context context, String reason) {
        prefs(context).edit()
                .putString(KEY_SELECTED_FIRMWARE, BOOT_ROCKBOX)
                .putString(KEY_SELECTED_ROUTE, BOOT_ROCKBOX)
                .putString(KEY_RESOLVED_ACTION, BOOT_ROCKBOX)
                .putBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, true)
                .putString(KEY_ROCKBOX_LAUNCH_REASON, reason)
                .putLong(KEY_STOCK_LOCKOUT_UNTIL_MS, 0L)
                .commit();
        Y2Marker.write(context, "BootState:prepareRockboxLaunch reason=" + reason
                + " " + describe(context));
    }

    public static boolean canLaunchRockbox(Context context, String reason) {
        SharedPreferences p = prefs(context);
        String selected = normalizeSelected(p.getString(KEY_SELECTED_FIRMWARE, SELECT_UNSET));
        boolean allowed = p.getBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, false);
        long now = SystemClock.elapsedRealtime();
        long lockoutUntil = p.getLong(KEY_STOCK_LOCKOUT_UNTIL_MS, 0L);
        String route = normalizeSelected(p.getString(KEY_SELECTED_ROUTE, selected));
        String physicalRoute = Y2FirmwareRouteProperty.read();
        String effectiveRoute = getCurrentFirmwareRoute(context);
        boolean result = BOOT_ROCKBOX.equals(selected)
                && BOOT_ROCKBOX.equals(route)
                && BOOT_ROCKBOX.equals(effectiveRoute)
                && allowed
                && now >= lockoutUntil;
        Y2Marker.write(context, "RockboxLaunchGuard: "
                + (result ? "allow" : "deny")
                + " reason=" + reason
                + " selectedFirmware=" + selected
                + " selectedRoute=" + route
                + " physicalRoute=" + physicalRoute
                + " effectiveRoute=" + effectiveRoute
                + " rockboxLaunchAllowed=" + allowed
                + " stockLockoutUntilMs=" + lockoutUntil
                + " nowMs=" + now
                + " launchReason=" + p.getString(KEY_ROCKBOX_LAUNCH_REASON, ""));
        return result;
    }

    public static void blockRockboxLaunch(Context context, String selected, String reason) {
        String normalized = normalizeSelected(selected);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_SELECTED_FIRMWARE, normalized)
                .putString(KEY_SELECTED_ROUTE, normalized)
                .putString(KEY_RESOLVED_ACTION, normalized);
        applyLaunchPolicy(editor, normalized, reason);
        editor.commit();
        Y2Marker.write(context, "BootState:blockRockboxLaunch reason=" + reason
                + " " + describe(context));
    }

    public static String getSelectedFirmware(Context context) {
        return normalizeSelected(prefs(context).getString(KEY_SELECTED_FIRMWARE, SELECT_UNSET));
    }

    public static boolean isRockboxSelected(Context context) {
        return BOOT_ROCKBOX.equals(getSelectedFirmware(context));
    }

    public static String getDefaultBoot(Context context) {
        return normalizeDefault(prefs(context).getString(KEY_DEFAULT_BOOT, BOOT_ASK));
    }

    public static void setDefaultBoot(Context context, String boot) {
        String normalized = normalizeDefault(boot);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_DEFAULT_BOOT, normalized)
                .putString(KEY_NO_BUTTON_ACTION, defaultActionForBoot(normalized))
                .putString(KEY_SELECTED_FIRMWARE, normalized)
                .putBoolean(KEY_FIRST_CHOICE_MADE, true);
        applyLaunchPolicy(editor, normalized, "set-default");
        editor.commit();
    }

    public static void ensureV26Defaults(Context context) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit();
        boolean changed = false;
        int settingsVersion = p.getInt(KEY_SETTINGS_VERSION, 0);
        if (settingsVersion < 26) {
            editor.putInt(KEY_SETTINGS_VERSION, 26)
                    .putString(KEY_DEFAULT_BOOT, BOOT_ROCKBOX)
                    .putString(KEY_SELECTED_FIRMWARE, SELECT_UNSET)
                    .putBoolean(KEY_FIRST_CHOICE_MADE, true)
                    .putString(KEY_NO_BUTTON_ACTION, BOOT_ROCKBOX)
                    .putString(KEY_VOLUME_UP_ACTION, BOOT_STOCK)
                    .putString(KEY_VOLUME_DOWN_ACTION, BOOT_SETTINGS)
                    .putString(KEY_MENU_ACTION, BOOT_STOCK)
                    .putString(KEY_CENTER_ACTION, BOOT_SETTINGS)
                    .putString(KEY_LEFT_ACTION, BOOT_DEFAULT)
                    .putString(KEY_RIGHT_ACTION, BOOT_DEFAULT)
                    .putString(KEY_PLAY_ACTION, BOOT_DEFAULT)
                    .putString(KEY_SELECTED_ROUTE, SELECT_UNSET)
                    .putString(KEY_DETECTED_BUTTON, "unknown")
                    .putString(KEY_RESOLVED_ACTION, SELECT_UNSET)
                    .putBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, false)
                    .putLong(KEY_STOCK_LOCKOUT_UNTIL_MS, 0L)
                    .putLong(KEY_USB_STORAGE_GRACE_UNTIL_MS, 0L);
            changed = true;
        }
        if (!p.contains(KEY_NO_BUTTON_ACTION)) {
            editor.putString(KEY_NO_BUTTON_ACTION, BOOT_ROCKBOX);
            changed = true;
        }
        if (!p.contains(KEY_VOLUME_UP_ACTION)) {
            editor.putString(KEY_VOLUME_UP_ACTION, BOOT_STOCK);
            changed = true;
        }
        if (!p.contains(KEY_VOLUME_DOWN_ACTION)) {
            editor.putString(KEY_VOLUME_DOWN_ACTION, BOOT_SETTINGS);
            changed = true;
        }
        if (!p.contains(KEY_MENU_ACTION)) {
            editor.putString(KEY_MENU_ACTION, BOOT_STOCK);
            changed = true;
        }
        if (!p.contains(KEY_CENTER_ACTION)) {
            editor.putString(KEY_CENTER_ACTION, BOOT_SETTINGS);
            changed = true;
        }
        if (!p.contains(KEY_LEFT_ACTION)) {
            editor.putString(KEY_LEFT_ACTION, BOOT_DEFAULT);
            changed = true;
        }
        if (!p.contains(KEY_RIGHT_ACTION)) {
            editor.putString(KEY_RIGHT_ACTION, BOOT_DEFAULT);
            changed = true;
        }
        if (!p.contains(KEY_PLAY_ACTION)) {
            editor.putString(KEY_PLAY_ACTION, BOOT_DEFAULT);
            changed = true;
        }
        if (BOOT_NONE.equals(p.getString(KEY_LEFT_ACTION, BOOT_DEFAULT))) {
            editor.putString(KEY_LEFT_ACTION, BOOT_DEFAULT);
            changed = true;
        }
        if (BOOT_NONE.equals(p.getString(KEY_RIGHT_ACTION, BOOT_DEFAULT))) {
            editor.putString(KEY_RIGHT_ACTION, BOOT_DEFAULT);
            changed = true;
        }
        if (BOOT_NONE.equals(p.getString(KEY_PLAY_ACTION, BOOT_DEFAULT))) {
            editor.putString(KEY_PLAY_ACTION, BOOT_DEFAULT);
            changed = true;
        }
        if (changed)
            editor.commit();
    }

    public static void ensureV25Defaults(Context context) {
        ensureV26Defaults(context);
    }

    public static void ensureV24Defaults(Context context) {
        ensureV25Defaults(context);
    }

    public static void ensureV23Defaults(Context context) {
        ensureV24Defaults(context);
    }

    public static void ensureV22Defaults(Context context) {
        ensureV24Defaults(context);
    }

    public static void ensureV21Defaults(Context context) {
        ensureV24Defaults(context);
    }

    public static String getNoButtonAction(Context context) {
        ensureV26Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_NO_BUTTON_ACTION, BOOT_ROCKBOX), false);
    }

    public static String getVolumeUpAction(Context context) {
        ensureV26Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_VOLUME_UP_ACTION, BOOT_STOCK), false);
    }

    public static String getVolumeDownAction(Context context) {
        ensureV26Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_VOLUME_DOWN_ACTION, BOOT_SETTINGS), false);
    }

    public static void setVolumeBootActions(Context context, String noButton,
            String volumeUp, String volumeDown) {
        String normalizedNoButton = normalizeAction(noButton, false);
        prefs(context).edit()
                .putString(KEY_NO_BUTTON_ACTION, normalizedNoButton)
                .putString(KEY_VOLUME_UP_ACTION, normalizeAction(volumeUp, false))
                .putString(KEY_VOLUME_DOWN_ACTION, normalizeAction(volumeDown, false))
                .putString(KEY_DEFAULT_BOOT, defaultBootForAction(normalizedNoButton,
                        getDefaultBoot(context)))
                .putBoolean(KEY_FIRST_CHOICE_MADE, true)
                .commit();
    }

    public static String getMenuAction(Context context) {
        ensureV25Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_MENU_ACTION, BOOT_STOCK), true);
    }

    public static String getCenterAction(Context context) {
        ensureV25Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_CENTER_ACTION, BOOT_SETTINGS), true);
    }

    public static String getLeftAction(Context context) {
        ensureV25Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_LEFT_ACTION, BOOT_DEFAULT), true);
    }

    public static String getRightAction(Context context) {
        ensureV25Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_RIGHT_ACTION, BOOT_DEFAULT), true);
    }

    public static String getPlayAction(Context context) {
        ensureV25Defaults(context);
        return normalizeAction(prefs(context).getString(KEY_PLAY_ACTION, BOOT_DEFAULT), true);
    }

    public static void setBootActions(Context context, String noButton, String menu,
            String center, String left, String right, String play) {
        prefs(context).edit()
                .putString(KEY_NO_BUTTON_ACTION, normalizeAction(noButton, false))
                .putString(KEY_MENU_ACTION, normalizeAction(menu, true))
                .putString(KEY_CENTER_ACTION, normalizeAction(center, true))
                .putString(KEY_LEFT_ACTION, normalizeAction(left, true))
                .putString(KEY_RIGHT_ACTION, normalizeAction(right, true))
                .putString(KEY_PLAY_ACTION, normalizeAction(play, true))
                .putString(KEY_DEFAULT_BOOT, defaultBootForAction(normalizeAction(noButton, false),
                        getDefaultBoot(context)))
                .putBoolean(KEY_FIRST_CHOICE_MADE, true)
                .commit();
    }

    public static String resolveAction(Context context, String action) {
        String normalized = normalizeAction(action, true);
        if (BOOT_DEFAULT.equals(normalized))
            return getNoButtonAction(context);
        return normalized;
    }

    public static void noteResolvedAction(Context context, String button, String action,
            String resolved, String reason) {
        prefs(context).edit()
                .putString(KEY_DETECTED_BUTTON, button)
                .putString(KEY_RESOLVED_ACTION, resolved)
                .commit();
        Y2Marker.write(context, "BootAction:detectedButton=" + button
                + " action=" + action
                + " resolvedAction=" + resolved
                + " reason=" + reason
                + " " + describe(context));
    }

    public static void setBootKeyCaptureActive(Context context, boolean active) {
        prefs(context).edit()
                .putBoolean(KEY_BOOT_KEY_CAPTURE_ACTIVE, active)
                .commit();
    }

    public static boolean isBootKeyCaptureActive(Context context) {
        return prefs(context).getBoolean(KEY_BOOT_KEY_CAPTURE_ACTIVE, false);
    }

    public static void setBootSettingsActive(Context context, boolean active) {
        prefs(context).edit()
                .putBoolean(KEY_BOOT_SETTINGS_ACTIVE, active)
                .commit();
    }

    public static boolean isBootSettingsActive(Context context) {
        return prefs(context).getBoolean(KEY_BOOT_SETTINGS_ACTIVE, false);
    }

    public static void noteUsbStorageWindow(Context context, String reason) {
        long until = SystemClock.elapsedRealtime() + USB_STORAGE_GRACE_MS;
        prefs(context).edit()
                .putLong(KEY_USB_STORAGE_GRACE_UNTIL_MS, until)
                .commit();
        Y2Marker.write(context, "BootState:usbStorageWindow reason=" + reason
                + " untilMs=" + until + " " + describe(context));
    }

    public static boolean isUsbStorageWindowActive(Context context) {
        return SystemClock.elapsedRealtime()
                < prefs(context).getLong(KEY_USB_STORAGE_GRACE_UNTIL_MS, 0L);
    }

    public static void noteStorageEvent(Context context, String action,
            boolean returnCandidate) {
        long now = SystemClock.elapsedRealtime();
        SharedPreferences p = prefs(context);
        boolean secondaryExpected = p.getBoolean(KEY_SECONDARY_STORAGE_EXPECTED, false);
        File secondary = new File("/storage/sdcard1");
        if (!returnCandidate && secondary.exists() && secondary.isDirectory()
                && secondary.canRead()) {
            secondaryExpected = true;
        }
        p.edit()
                .putString(KEY_STORAGE_EVENT_ACTION, action == null ? "null" : action)
                .putLong(KEY_STORAGE_EVENT_ELAPSED_MS, now)
                .putBoolean(KEY_STORAGE_RETURN_CANDIDATE, returnCandidate)
                .putBoolean(KEY_SECONDARY_STORAGE_EXPECTED, secondaryExpected)
                .commit();
        Y2Marker.write(context, "BootState:storageEvent action=" + action
                + " returnCandidate=" + returnCandidate
                + " secondaryExpected=" + secondaryExpected
                + " eventElapsedMs=" + now);
    }

    public static void beginFirmwareRouteSession(Context context, String route,
            String reason) {
        if (!BOOT_ROCKBOX.equals(route) && !BOOT_STOCK.equals(route)
                && !BOOT_SETTINGS.equals(route))
            return;

        SharedPreferences p = prefs(context);
        long now = SystemClock.elapsedRealtime();
        long previousStart = p.getLong(KEY_FIRMWARE_SESSION_START_MS, 0L);
        long bootWall = System.currentTimeMillis() - now;
        long previousBootWall = p.getLong(KEY_FIRMWARE_SESSION_BOOT_WALL_MS, 0L);
        String bootId = readBootId();
        String previousBootId = p.getString(KEY_FIRMWARE_SESSION_BOOT_ID, "");
        String previousRoute = p.getString(KEY_FIRMWARE_SESSION_ROUTE, "");
        boolean sameBoot;
        if (bootId.length() > 0 && previousBootId.length() > 0)
            sameBoot = bootId.equals(previousBootId);
        else
            sameBoot = previousStart > 0L && now >= previousStart
                    && previousBootWall > 0L
                    && Math.abs(bootWall - previousBootWall)
                    <= BOOT_WALL_TOLERANCE_MS;

        if (sameBoot && route.equals(previousRoute))
            return;

        p.edit()
                .putString(KEY_FIRMWARE_SESSION_BOOT_ID, bootId)
                .putString(KEY_FIRMWARE_SESSION_ROUTE, route)
                .putLong(KEY_FIRMWARE_SESSION_START_MS, now)
                .putLong(KEY_FIRMWARE_SESSION_BOOT_WALL_MS, bootWall)
                .putString(KEY_STORAGE_EVENT_ACTION, "firmware-session-reset")
                .putLong(KEY_STORAGE_EVENT_ELAPSED_MS, now)
                .putBoolean(KEY_STORAGE_RETURN_CANDIDATE, false)
                .putBoolean(KEY_SECONDARY_STORAGE_EXPECTED, false)
                .putBoolean(KEY_USB_RETURN_PENDING, false)
                .putBoolean(KEY_EXPECTED_ROCKBOX_EXIT, false)
                .putLong(KEY_EXPECTED_ROCKBOX_EXIT_AT_MS, 0L)
                .putLong(KEY_USB_STORAGE_GRACE_UNTIL_MS, 0L)
                .putBoolean(KEY_BOOT_SETTINGS_ACTIVE, false)
                .putBoolean(KEY_BOOT_KEY_CAPTURE_ACTIVE, false)
                .putBoolean(KEY_ROCKBOX_USB_SESSION_READY, false)
                .commit();
        Y2Marker.write(context, "BootState:beginFirmwareRouteSession"
                + " route=" + route
                + " previousRoute=" + previousRoute
                + " sameBoot=" + sameBoot
                + " reason=" + reason
                + " action=clear-stale-usb-return");
    }

    /**
     * Returns the authenticated, user-mapped route for this boot.  The init
     * property identifies only the physical LK/ramdisk path; it must not be
     * reused as the effective destination after Boot Settings remaps it.
     */
    public static String getCurrentFirmwareRoute(Context context) {
        SharedPreferences p = prefs(context);
        if (!isCurrentFirmwareSession(p))
            return SELECT_UNSET;
        return normalizeSelected(p.getString(
                KEY_FIRMWARE_SESSION_ROUTE, SELECT_UNSET));
    }

    public static boolean isCurrentFirmwareRoute(Context context, String route) {
        return normalizeSelected(route).equals(getCurrentFirmwareRoute(context));
    }

    private static boolean isCurrentFirmwareSession(SharedPreferences p) {
        long now = SystemClock.elapsedRealtime();
        long sessionStart = p.getLong(KEY_FIRMWARE_SESSION_START_MS, 0L);
        if (sessionStart <= 0L || now < sessionStart)
            return false;

        String bootId = readBootId();
        String sessionBootId = p.getString(KEY_FIRMWARE_SESSION_BOOT_ID, "");
        if (bootId.length() > 0 && sessionBootId.length() > 0)
            return bootId.equals(sessionBootId);

        long bootWall = System.currentTimeMillis() - now;
        long sessionBootWall = p.getLong(KEY_FIRMWARE_SESSION_BOOT_WALL_MS, 0L);
        return sessionBootWall > 0L
                && Math.abs(bootWall - sessionBootWall) <= BOOT_WALL_TOLERANCE_MS;
    }

    public static void markRockboxUsbSessionReady(Context context, String reason) {
        if (!isCurrentFirmwareRoute(context, BOOT_ROCKBOX)) {
            Y2Marker.write(context, "BootState:ignoreUsbSessionReady non-rockbox"
                    + " reason=" + reason);
            return;
        }
        prefs(context).edit()
                .putBoolean(KEY_ROCKBOX_USB_SESSION_READY, true)
                .commit();
        Y2Marker.write(context, "BootState:rockboxUsbSessionReady reason=" + reason);
    }

    public static boolean isRockboxUsbSessionReady(Context context) {
        SharedPreferences p = prefs(context);
        return isCurrentFirmwareRoute(context, BOOT_ROCKBOX)
                && p.getBoolean(KEY_ROCKBOX_USB_SESSION_READY, false);
    }

    public static void noteExpectedRockboxExit(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        prefs(context).edit()
                .putBoolean(KEY_EXPECTED_ROCKBOX_EXIT, true)
                .putLong(KEY_EXPECTED_ROCKBOX_EXIT_AT_MS, now)
                .putBoolean(KEY_USB_RETURN_PENDING, true)
                .commit();
        Y2Marker.write(context, "BootState:expectedRockboxExit reason=" + reason
                + " elapsedMs=" + now);
    }

    public static boolean isUsbReturnPending(Context context) {
        return isRockboxUsbSessionReady(context)
                && prefs(context).getBoolean(KEY_USB_RETURN_PENDING, false);
    }

    public static boolean isLatestStorageEventReturnCandidate(Context context) {
        return prefs(context).getBoolean(KEY_STORAGE_RETURN_CANDIDATE, false);
    }

    public static long storageEventAgeMs(Context context) {
        long then = prefs(context).getLong(KEY_STORAGE_EVENT_ELAPSED_MS, 0L);
        long now = SystemClock.elapsedRealtime();
        if (then <= 0L || now < then)
            return Long.MAX_VALUE;
        return now - then;
    }

    public static String getLastStorageEventAction(Context context) {
        return prefs(context).getString(KEY_STORAGE_EVENT_ACTION, "none");
    }

    public static boolean isSecondaryStorageExpected(Context context) {
        return prefs(context).getBoolean(KEY_SECONDARY_STORAGE_EXPECTED, false);
    }

    public static void completeUsbStorageReturn(Context context, String reason) {
        prefs(context).edit()
                .putBoolean(KEY_USB_RETURN_PENDING, false)
                .putBoolean(KEY_EXPECTED_ROCKBOX_EXIT, false)
                .putLong(KEY_EXPECTED_ROCKBOX_EXIT_AT_MS, 0L)
                .putLong(KEY_USB_STORAGE_GRACE_UNTIL_MS, 0L)
                .putBoolean(KEY_SECONDARY_STORAGE_EXPECTED, false)
                .commit();
        Y2Marker.write(context, "BootState:completeUsbStorageReturn reason=" + reason
                + " lastAction=" + getLastStorageEventAction(context));
    }

    public static boolean beginRockboxProcessLaunch(Context context, String reason) {
        SharedPreferences p = prefs(context);
        long now = SystemClock.elapsedRealtime();
        int pid = Process.myPid();
        int previousPid = p.getInt(KEY_ROCKBOX_PROCESS_PID, 0);
        long previousStart = p.getLong(KEY_ROCKBOX_PROCESS_START_MS, 0L);
        boolean previousHealthy = p.getBoolean(KEY_ROCKBOX_PROCESS_HEALTHY, false);
        int rapidFailures = p.getInt(KEY_RAPID_ROCKBOX_FAILURES, 0);
        String bootId = readBootId();
        String previousBootId = p.getString(KEY_ROCKBOX_PROCESS_BOOT_ID, "");
        boolean sameBoot = bootId.length() == 0 || previousBootId.length() == 0
                ? now >= previousStart
                : bootId.equals(previousBootId);

        if (!sameBoot) {
            previousPid = 0;
            previousStart = 0L;
            previousHealthy = false;
            rapidFailures = 0;
            p.edit()
                    .putBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)
                    .putInt(KEY_RAPID_ROCKBOX_FAILURES, 0)
                    .commit();
        } else if (p.getBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)) {
            Y2Marker.write(context, "RelaunchGuard:block reason=already-blocked"
                    + " request=" + reason
                    + " pid=" + pid
                    + " previousPid=" + previousPid
                    + " rapidFailures=" + rapidFailures);
            return false;
        }

        boolean expectedExit = p.getBoolean(KEY_EXPECTED_ROCKBOX_EXIT, false);
        long expectedAt = p.getLong(KEY_EXPECTED_ROCKBOX_EXIT_AT_MS, 0L);
        boolean expectedExitCurrent = expectedExit && now >= expectedAt
                && now - expectedAt <= EXPECTED_EXIT_WINDOW_MS;

        if (previousPid == pid && previousPid != 0) {
            if (expectedExit) {
                p.edit()
                        .putBoolean(KEY_EXPECTED_ROCKBOX_EXIT, false)
                        .putLong(KEY_EXPECTED_ROCKBOX_EXIT_AT_MS, 0L)
                        .commit();
            }
            Y2Marker.write(context, "RelaunchGuard:allow reason=same-process"
                    + " request=" + reason + " pid=" + pid
                    + " healthy=" + previousHealthy);
            return true;
        }

        if (previousPid != 0 && sameBoot) {
            long delta = now - previousStart;
            if (expectedExitCurrent) {
                rapidFailures = 0;
            } else if (!previousHealthy && delta >= 0L
                    && delta <= RAPID_FAILURE_WINDOW_MS) {
                rapidFailures++;
            } else {
                rapidFailures = 0;
            }
        } else {
            rapidFailures = 0;
        }

        if (rapidFailures >= MAX_RAPID_FAILURES) {
            p.edit()
                    .putInt(KEY_RAPID_ROCKBOX_FAILURES, rapidFailures)
                    .putBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, true)
                    .putBoolean(KEY_EXPECTED_ROCKBOX_EXIT, false)
                    .putLong(KEY_EXPECTED_ROCKBOX_EXIT_AT_MS, 0L)
                    .commit();
            Y2Marker.write(context, "RelaunchGuard:block reason=rapid-process-loss"
                    + " request=" + reason
                    + " pid=" + pid
                    + " previousPid=" + previousPid
                    + " previousHealthy=" + previousHealthy
                    + " rapidFailures=" + rapidFailures);
            Y2Marker.writeDebugSnapshot(context, "RelaunchGuard:rapid-process-loss");
            return false;
        }

        p.edit()
                .putInt(KEY_ROCKBOX_PROCESS_PID, pid)
                .putLong(KEY_ROCKBOX_PROCESS_START_MS, now)
                .putBoolean(KEY_ROCKBOX_PROCESS_HEALTHY, false)
                .putString(KEY_ROCKBOX_PROCESS_BOOT_ID, bootId)
                .putInt(KEY_RAPID_ROCKBOX_FAILURES, rapidFailures)
                .putBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)
                .putBoolean(KEY_EXPECTED_ROCKBOX_EXIT, false)
                .putLong(KEY_EXPECTED_ROCKBOX_EXIT_AT_MS, 0L)
                .commit();
        Y2Marker.write(context, "RelaunchGuard:allow reason=process-start"
                + " request=" + reason
                + " pid=" + pid
                + " previousPid=" + previousPid
                + " previousHealthy=" + previousHealthy
                + " expectedExit=" + expectedExitCurrent
                + " rapidFailures=" + rapidFailures
                + " bootId=" + bootId);
        return true;
    }

    public static void markRockboxProcessHealthy(Context context, String reason) {
        SharedPreferences p = prefs(context);
        int pid = Process.myPid();
        if (p.getInt(KEY_ROCKBOX_PROCESS_PID, 0) != pid)
            return;
        p.edit()
                .putBoolean(KEY_ROCKBOX_PROCESS_HEALTHY, true)
                .putInt(KEY_RAPID_ROCKBOX_FAILURES, 0)
                .putBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)
                .commit();
        Y2Marker.write(context, "RelaunchGuard:healthy reason=" + reason
                + " pid=" + pid);
    }

    public static boolean isRockboxRelaunchBlocked(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false))
            return false;
        String currentBootId = readBootId();
        String storedBootId = p.getString(KEY_ROCKBOX_PROCESS_BOOT_ID, "");
        if (currentBootId.length() > 0 && storedBootId.length() > 0
                && !currentBootId.equals(storedBootId)) {
            p.edit()
                    .putBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)
                    .putInt(KEY_RAPID_ROCKBOX_FAILURES, 0)
                    .commit();
            return false;
        }
        return true;
    }

    public static boolean hasFirstChoice(Context context) {
        return prefs(context).getBoolean(KEY_FIRST_CHOICE_MADE, false);
    }

    public static String describe(Context context) {
        return "default=" + getDefaultBoot(context)
                + " selected=" + getSelectedFirmware(context)
                + " route=" + prefs(context).getString(KEY_SELECTED_ROUTE, SELECT_UNSET)
                + " physicalRoute=" + Y2FirmwareRouteProperty.read()
                + " effectiveRoute=" + getCurrentFirmwareRoute(context)
                + " detectedButton=" + prefs(context).getString(KEY_DETECTED_BUTTON, "unknown")
                + " resolvedAction=" + prefs(context).getString(KEY_RESOLVED_ACTION, SELECT_UNSET)
                + " firstChoice=" + hasFirstChoice(context)
                + " noButton=" + getNoButtonAction(context)
                + " volumeUp=" + getVolumeUpAction(context)
                + " volumeDown=" + getVolumeDownAction(context)
                + " menu=" + getMenuAction(context)
                + " center=" + getCenterAction(context)
                + " left=" + getLeftAction(context)
                + " right=" + getRightAction(context)
                + " play=" + getPlayAction(context)
                + " bootKeyCaptureActive=" + isBootKeyCaptureActive(context)
                + " bootSettingsActive=" + isBootSettingsActive(context)
                + " usbStorageWindowActive=" + isUsbStorageWindowActive(context)
                + " usbReturnPending=" + isUsbReturnPending(context)
                + " storageAction=" + getLastStorageEventAction(context)
                + " storageReturnCandidate=" + isLatestStorageEventReturnCandidate(context)
                + " rockboxUsbSessionReady=" + isRockboxUsbSessionReady(context)
                + " rapidRockboxFailures="
                + prefs(context).getInt(KEY_RAPID_ROCKBOX_FAILURES, 0)
                + " relaunchBlocked="
                + prefs(context).getBoolean(KEY_ROCKBOX_RELAUNCH_BLOCKED, false)
                + " rockboxLaunchAllowed="
                + prefs(context).getBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, false)
                + " stockLockoutUntilMs="
                + prefs(context).getLong(KEY_STOCK_LOCKOUT_UNTIL_MS, 0L)
                + " bootElapsedMs=" + prefs(context).getLong(KEY_BOOT_ELAPSED_MS, 0L);
    }

    private static SharedPreferences prefs(Context context) {
        /*
         * The route receiver/shield live in :route while Rockbox runs in the
         * main process. Android 4.4 still supports MODE_MULTI_PROCESS and needs
         * it here so USB-return completion cannot remain stale in :route.
         */
        return context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    static String currentBootId() {
        return readBootId();
    }

    private static String readBootId() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new FileReader("/proc/sys/kernel/random/boot_id"));
            String value = reader.readLine();
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
        }
    }

    private static String normalizeDefault(String value) {
        if (BOOT_ROCKBOX.equals(value) || BOOT_STOCK.equals(value))
            return value;
        return BOOT_ASK;
    }

    private static String normalizeSelected(String value) {
        if (BOOT_ROCKBOX.equals(value) || BOOT_STOCK.equals(value)
                || BOOT_SETTINGS.equals(value) || BOOT_NONE.equals(value))
            return value;
        return SELECT_UNSET;
    }

    private static String normalizeAction(String value, boolean allowNone) {
        if (BOOT_ROCKBOX.equals(value) || BOOT_STOCK.equals(value)
                || BOOT_SETTINGS.equals(value))
            return value;
        if (allowNone && (BOOT_DEFAULT.equals(value) || BOOT_NONE.equals(value)))
            return BOOT_DEFAULT;
        return allowNone ? BOOT_DEFAULT : BOOT_ROCKBOX;
    }

    private static String defaultActionForBoot(String boot) {
        if (BOOT_STOCK.equals(boot))
            return BOOT_STOCK;
        return BOOT_ROCKBOX;
    }

    private static String defaultBootForAction(String action, String previousDefault) {
        if (BOOT_STOCK.equals(action))
            return BOOT_STOCK;
        if (BOOT_ROCKBOX.equals(action))
            return BOOT_ROCKBOX;
        if (BOOT_STOCK.equals(previousDefault) || BOOT_ROCKBOX.equals(previousDefault))
            return previousDefault;
        return BOOT_ROCKBOX;
    }

    private static void applyLaunchPolicy(SharedPreferences.Editor editor,
            String selected, String reason) {
        if (BOOT_ROCKBOX.equals(selected)) {
            editor.putBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, true)
                    .putString(KEY_ROCKBOX_LAUNCH_REASON, reason)
                    .putString(KEY_SELECTED_ROUTE, BOOT_ROCKBOX)
                    .putString(KEY_RESOLVED_ACTION, BOOT_ROCKBOX)
                    .putLong(KEY_STOCK_LOCKOUT_UNTIL_MS, 0L);
            return;
        }

        editor.putBoolean(KEY_ROCKBOX_LAUNCH_ALLOWED, false)
                .putString(KEY_ROCKBOX_LAUNCH_REASON, reason)
                .putString(KEY_SELECTED_ROUTE, selected)
                .putString(KEY_RESOLVED_ACTION, selected);
        if (BOOT_STOCK.equals(selected)) {
            editor.putLong(KEY_STOCK_LOCKOUT_UNTIL_MS, Long.MAX_VALUE);
        }
    }
}
