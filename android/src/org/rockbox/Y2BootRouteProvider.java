package org.rockbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * Narrow Binder mailbox between Boot Settings and the root-owned save helper.
 *
 * The only writable preference is the normal boot player. Volume Up is always
 * derived as the other player and Volume Down remains Boot Settings.
 */
public final class Y2BootRouteProvider extends ContentProvider {
    public static final String AUTHORITY = "org.rockbox.y2.bootroutes";
    public static final Uri REQUEST_URI =
            Uri.parse("content://" + AUTHORITY + "/request");
    public static final Uri RESPONSE_URI =
            Uri.parse("content://" + AUTHORITY + "/response");

    public static final String PROTOCOL_VERSION = "protocol_version";
    public static final String BOOT_ID = "boot_id";
    public static final String REQUEST_ID = "request_id";
    public static final String NORMAL_BOOT = "normal_boot";
    public static final String REBOOT = "reboot";
    public static final String STATUS = "status";
    public static final String EXPECTED_PROTOCOL_VERSION = "3";

    private static final int REQUEST = 1;
    private static final int RESPONSE = 2;
    private static final String REQUEST_STORE = "y2_boot_route_ipc";
    private static final String KIND = "kind";
    private static final String KIND_REQUEST = "request";
    private static final String KIND_RESPONSE = "response";
    private static final int SHELL_UID = 2000;

    private static final UriMatcher URI_MATCHER =
            new UriMatcher(UriMatcher.NO_MATCH);
    static {
        URI_MATCHER.addURI(AUTHORITY, "request", REQUEST);
        URI_MATCHER.addURI(AUTHORITY, "response", RESPONSE);
    }

    private SharedPreferences requestStore;

    @Override
    public boolean onCreate() {
        requestStore = getContext().getSharedPreferences(REQUEST_STORE, 0);
        return true;
    }

    @Override
    public synchronized Uri insert(Uri uri, ContentValues values) {
        int match = URI_MATCHER.match(uri);
        if (match == REQUEST) {
            requireAppCaller();
            String protocolVersion = stringValue(values, PROTOCOL_VERSION);
            String bootId = stringValue(values, BOOT_ID);
            String requestId = stringValue(values, REQUEST_ID);
            String normalBoot = stringValue(values, NORMAL_BOOT);
            String reboot = stringValue(values, REBOOT);
            if (!EXPECTED_PROTOCOL_VERSION.equals(protocolVersion)
                    || !isCurrentBootId(bootId)
                    || !isRequestId(requestId)
                    || !isPlayer(normalBoot)
                    || !"1".equals(reboot)) {
                throw new IllegalArgumentException(
                        "invalid normal boot request");
            }
            boolean committed = requestStore.edit().clear()
                    .putString(KIND, KIND_REQUEST)
                    .putString(PROTOCOL_VERSION, protocolVersion)
                    .putString(BOOT_ID, bootId)
                    .putString(REQUEST_ID, requestId)
                    .putString(NORMAL_BOOT, normalBoot)
                    .putString(REBOOT, reboot)
                    .commit();
            if (!committed)
                throw new IllegalStateException("request commit failed");
            getContext().getContentResolver().notifyChange(REQUEST_URI, null);
            return Uri.withAppendedPath(REQUEST_URI, requestId);
        }
        if (match == RESPONSE) {
            requireBrokerCaller();
            String protocolVersion = stringValue(values, PROTOCOL_VERSION);
            String bootId = stringValue(values, BOOT_ID);
            String requestId = stringValue(values, REQUEST_ID);
            String status = stringValue(values, STATUS);
            if (!EXPECTED_PROTOCOL_VERSION.equals(protocolVersion)
                    || !isCurrentBootId(bootId)
                    || !KIND_REQUEST.equals(
                            requestStore.getString(KIND, null))
                    || !isRequestId(requestId)
                    || !requestId.equals(
                            requestStore.getString(REQUEST_ID, null))
                    || !isStatus(status)) {
                throw new IllegalArgumentException(
                        "response does not match active request");
            }
            boolean committed = requestStore.edit().clear()
                    .putString(KIND, KIND_RESPONSE)
                    .putString(PROTOCOL_VERSION, protocolVersion)
                    .putString(BOOT_ID, bootId)
                    .putString(REQUEST_ID, requestId)
                    .putString(STATUS, status)
                    .commit();
            if (!committed)
                throw new IllegalStateException("response commit failed");
            getContext().getContentResolver().notifyChange(RESPONSE_URI, null);
            return Uri.withAppendedPath(RESPONSE_URI, requestId);
        }
        throw new IllegalArgumentException("unsupported URI " + uri);
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        int match = URI_MATCHER.match(uri);
        if (match == REQUEST) {
            requireBrokerCaller();
            String[] columns = {
                PROTOCOL_VERSION, BOOT_ID, REQUEST_ID, NORMAL_BOOT, REBOOT
            };
            MatrixCursor cursor = new MatrixCursor(columns);
            if (KIND_REQUEST.equals(requestStore.getString(KIND, null))) {
                cursor.addRow(new Object[] {
                    requestStore.getString(PROTOCOL_VERSION, null),
                    requestStore.getString(BOOT_ID, null),
                    requestStore.getString(REQUEST_ID, null),
                    requestStore.getString(NORMAL_BOOT, null),
                    requestStore.getString(REBOOT, null)
                });
            }
            cursor.setNotificationUri(
                    getContext().getContentResolver(), REQUEST_URI);
            return cursor;
        }
        if (match == RESPONSE) {
            requireAppCaller();
            String[] columns = {
                PROTOCOL_VERSION, BOOT_ID, REQUEST_ID, STATUS
            };
            MatrixCursor cursor = new MatrixCursor(columns);
            if (KIND_RESPONSE.equals(requestStore.getString(KIND, null))) {
                cursor.addRow(new Object[] {
                    requestStore.getString(PROTOCOL_VERSION, null),
                    requestStore.getString(BOOT_ID, null),
                    requestStore.getString(REQUEST_ID, null),
                    requestStore.getString(STATUS, null)
                });
            }
            cursor.setNotificationUri(
                    getContext().getContentResolver(), RESPONSE_URI);
            return cursor;
        }
        throw new IllegalArgumentException("unsupported URI " + uri);
    }

    @Override
    public synchronized int delete(Uri uri, String selection,
            String[] selectionArgs) {
        int match = URI_MATCHER.match(uri);
        if (match == REQUEST) {
            requireAppOrBrokerCaller();
            if (!KIND_REQUEST.equals(requestStore.getString(KIND, null)))
                return 0;
        } else if (match == RESPONSE) {
            requireAppCaller();
            if (!KIND_RESPONSE.equals(requestStore.getString(KIND, null)))
                return 0;
        } else {
            throw new IllegalArgumentException("unsupported URI " + uri);
        }
        if (!requestStore.edit().clear().commit())
            throw new IllegalStateException("mailbox clear failed");
        getContext().getContentResolver().notifyChange(uri, null);
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("update is not supported");
    }

    @Override
    public String getType(Uri uri) {
        int match = URI_MATCHER.match(uri);
        if (match == REQUEST)
            return "vnd.android.cursor.item/vnd.rockbox.y2.normal-boot-request";
        if (match == RESPONSE)
            return "vnd.android.cursor.item/vnd.rockbox.y2.normal-boot-response";
        throw new IllegalArgumentException("unsupported URI " + uri);
    }

    private void requireAppCaller() {
        if (Binder.getCallingUid() != Process.myUid())
            throw new SecurityException("Rockbox caller required");
    }

    private void requireBrokerCaller() {
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != Process.SYSTEM_UID && uid != SHELL_UID)
            throw new SecurityException("root/system/shell broker required");
    }

    private void requireAppOrBrokerCaller() {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid() && uid != 0
                && uid != Process.SYSTEM_UID && uid != SHELL_UID) {
            throw new SecurityException("Rockbox or broker caller required");
        }
    }

    private static String stringValue(ContentValues values, String key) {
        return values == null ? null : values.getAsString(key);
    }

    private static boolean isRequestId(String value) {
        if (value == null || value.length() < 1 || value.length() > 20)
            return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i)))
                return false;
        }
        return true;
    }

    private static boolean isCurrentBootId(String value) {
        if (value == null || value.length() < 1 || value.length() > 64
                || !value.equals(Y2BootState.currentBootId())) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.digit(c, 16) >= 0 || c == '-'))
                return false;
        }
        return true;
    }

    private static boolean isPlayer(String value) {
        return Y2BootState.BOOT_ROCKBOX.equals(value)
                || Y2BootState.BOOT_STOCK.equals(value);
    }

    private static boolean isStatus(String value) {
        if ("ok".equals(value))
            return true;
        if (value == null || !value.startsWith("error-")
                || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-'))
                return false;
        }
        return true;
    }
}
