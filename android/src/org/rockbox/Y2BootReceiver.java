package org.rockbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class Y2BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Y2Marker.installUncaughtExceptionHandler();
        Y2Marker.write(context, "Y2BootReceiver:onReceive action="
                + (intent == null ? "null" : intent.getAction()));
        Y2Marker.writeDebugSnapshot(context, "Y2BootReceiver:onReceive");
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Y2BootState.noteBoot(context);
            Y2BootState.migrateV17Selection(context);
            Y2Marker.write(context, "Y2BootReceiver:noteBoot "
                    + Y2BootState.describe(context));
            Y2Marker.writeDebugSnapshot(context, "Y2BootReceiver:noteBoot");
            Intent service = new Intent(context, Y2BootLaunchService.class);
            service.setAction(Y2BootLaunchService.ACTION_BOOT_COMPLETED);
            context.startService(service);
            Y2Marker.write(context, "Y2BootReceiver:startService Y2BootLaunchService");
        }
    }
}
