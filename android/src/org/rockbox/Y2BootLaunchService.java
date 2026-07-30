package org.rockbox;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Environment;
import java.io.File;

public class Y2BootLaunchService extends Service {
    public static final String ACTION_BOOT_COMPLETED = "org.rockbox.y2.BOOT_COMPLETED";
    public static final String ACTION_REASSERT_ROCKBOX = "org.rockbox.y2.REASSERT_ROCKBOX";
    public static final String ACTION_CANCEL_USB_RETURN = "org.rockbox.y2.CANCEL_USB_RETURN";
    private static final long BOOT_LAUNCH_DELAY_MS = 1500L;
    private static final long REASSERT_SETTLE_DELAY_MS = 2000L;
    private static final long STORAGE_RETRY_MS = 1000L;
    private static final int MAX_STORAGE_RETRIES = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pendingReassert = new Runnable() {
        @Override
        public void run() {
            reassertRockbox(0);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Y2Marker.installUncaughtExceptionHandler();
        Y2Marker.write(this, "Y2BootLaunchService:onStartCommand action="
                + (intent == null ? "null" : intent.getAction())
                + " flags=" + flags + " startId=" + startId
                + " " + Y2BootState.describe(this));
        Y2Marker.writeDebugSnapshot(this, "Y2BootLaunchService:onStartCommand");
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL_USB_RETURN.equals(action)) {
            handler.removeCallbacks(pendingReassert);
            Y2Marker.write(this, "Y2BootLaunchService:cancel pending USB return"
                    + " lastStorageAction=" + Y2BootState.getLastStorageEventAction(this));
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REASSERT_ROCKBOX.equals(action)) {
            if (!Y2BootState.isLatestStorageEventReturnCandidate(this)) {
                Y2Marker.write(this,
                        "Y2BootLaunchService:skip reassert latest event is not return candidate"
                        + " action=" + Y2BootState.getLastStorageEventAction(this));
                return START_NOT_STICKY;
            }
            handler.removeCallbacks(pendingReassert);
            handler.postDelayed(pendingReassert, REASSERT_SETTLE_DELAY_MS);
            Y2Marker.write(this, "Y2BootLaunchService:scheduled stable USB return"
                    + " delayMs=" + REASSERT_SETTLE_DELAY_MS
                    + " action=" + Y2BootState.getLastStorageEventAction(this));
            return START_NOT_STICKY;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dispatchBootDefault();
            }
        }, BOOT_LAUNCH_DELAY_MS);
        return START_NOT_STICKY;
    }

    private void dispatchBootDefault() {
        Y2BootState.ensureV25Defaults(this);
        Y2Marker.write(this, "Y2BootLaunchService:dispatchBootDefault "
                + Y2BootState.describe(this));
        Y2Marker.writeDebugSnapshot(this, "Y2BootLaunchService:dispatchBootDefault");
        Intent activity = new Intent(this, Y2BootKeyCaptureActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(activity);
        Y2Marker.write(this,
                "Y2BootLaunchService:launched Y2BootKeyCaptureActivity mode=forced-router-no-chooser");
        stopSelf();
    }

    private void reassertRockbox(final int attempt) {
        Y2Marker.write(this, "Y2BootLaunchService:reassertRockbox attempt=" + attempt + " "
                + Y2BootState.describe(this));
        if (!Y2BootState.isLatestStorageEventReturnCandidate(this)) {
            Y2Marker.write(this, "Y2BootLaunchService:skip stable return reason=event-cancelled"
                    + " action=" + Y2BootState.getLastStorageEventAction(this));
            stopSelf();
            return;
        }
        if (Y2BootState.storageEventAgeMs(this) < REASSERT_SETTLE_DELAY_MS) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reassertRockbox(attempt);
                }
            }, STORAGE_RETRY_MS);
            return;
        }
        if (!isPrimaryStorageReady()
                || (Y2BootState.isSecondaryStorageExpected(this)
                && !isSecondaryStorageReady())) {
            if (attempt < MAX_STORAGE_RETRIES) {
                Y2Marker.write(this, "Y2BootLaunchService:wait storage readiness"
                        + " attempt=" + attempt
                        + " primaryReady=" + isPrimaryStorageReady()
                        + " secondaryExpected="
                        + Y2BootState.isSecondaryStorageExpected(this)
                        + " secondaryReady=" + isSecondaryStorageReady());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        reassertRockbox(attempt + 1);
                    }
                }, STORAGE_RETRY_MS);
                return;
            }
            if (!isPrimaryStorageReady()) {
                Y2Marker.write(this,
                        "Y2BootLaunchService:stop stable return primary storage unavailable");
                stopSelf();
                return;
            }
            Y2Marker.write(this,
                    "Y2BootLaunchService:secondary wait expired, primary storage is stable");
        }
        if (Y2BootState.isBootKeyCaptureActive(this)) {
            Y2Marker.write(this,
                    "Y2BootLaunchService:skip reassert reason=boot-key-active "
                    + Y2BootState.describe(this));
            stopSelf();
            return;
        }
        if (Y2BootState.isBootSettingsActive(this)) {
            Y2Marker.write(this,
                    "Y2BootLaunchService:skip reassert reason=boot-settings-active "
                    + Y2BootState.describe(this));
            stopSelf();
            return;
        }
        if (Y2BootState.canLaunchRockbox(this, "storage-reassert"))
            launchRockbox("storage-reassert");
        else
            stopSelf();
    }

    private void launchRockbox(String reason) {
        RockboxService service = RockboxService.getInstance();
        boolean foregroundExisting = service != null && service.isRockboxRunning();
        if (!Y2BootState.canLaunchRockbox(this, reason)) {
            Y2Marker.write(this, "Y2BootLaunchService:deny launch reason="
                    + reason + " " + Y2BootState.describe(this));
            stopSelf();
            return;
        }
        Y2BootState.prepareRockboxLaunch(this, reason);
        Y2Marker.write(this, "Y2BootLaunchService:launchRockbox reason=" + reason
                + " foregroundExisting=" + foregroundExisting);
        Intent activity = new Intent(this, RockboxActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(activity);
        Y2Marker.write(this, "Y2BootLaunchService:foreground-requested reason=" + reason
                + " foregroundExisting=" + foregroundExisting);
        stopSelf();
    }

    private boolean isPrimaryStorageReady() {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
            return false;
        File directory = Environment.getExternalStorageDirectory();
        return directory != null && directory.exists() && directory.isDirectory()
                && directory.canRead() && directory.canWrite();
    }

    private boolean isSecondaryStorageReady() {
        File directory = new File("/storage/sdcard1");
        return directory.exists() && directory.isDirectory() && directory.canRead();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
