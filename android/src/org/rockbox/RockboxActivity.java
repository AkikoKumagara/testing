/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 * $Id$
 *
 * Copyright (C) 2010 Thomas Martitz
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY
 * KIND, either express or implied.
 *
 ****************************************************************************/

package org.rockbox;


import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import android.os.Environment;
import java.io.File;

public class RockboxActivity extends Activity 
{
    private static final long USB_RETURN_SETTLE_MS = 0L;
    private boolean framebufferAttached;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        Y2Marker.installUncaughtExceptionHandler();
        if (!Y2BootState.canLaunchRockbox(this, "RockboxActivity:onCreate")) {
            Y2Marker.write(this, "RockboxActivity:finish denied by launch guard "
                    + Y2BootState.describe(this));
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        if (!Y2BootState.beginRockboxProcessLaunch(this, "RockboxActivity:onCreate")) {
            Y2Marker.write(this, "RockboxActivity:finish blocked by rapid relaunch guard "
                    + Y2BootState.describe(this));
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        Y2Marker.write(this, "RockboxActivity:onCreate start "
                + Y2BootState.describe(this));
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        waitForStorageAndInit(0);
        Y2Marker.write(this, "RockboxActivity:onCreate end");
    }

    private void waitForStorageAndInit(final int attempt) {
        final int MAX_ATTEMPTS = 20;
        final String state = Environment.getExternalStorageState();
        File storageDir = Environment.getExternalStorageDirectory();
        Y2Marker.write(this, "RockboxActivity:checkExternalStorage attempt=" + attempt
                + " state=" + state
                + " path=" + storageDir.getAbsolutePath()
                + " exists=" + storageDir.exists()
                + " isDirectory=" + storageDir.isDirectory()
                + " canRead=" + storageDir.canRead()
                + " canWrite=" + storageDir.canWrite());
        boolean primaryReady = Environment.MEDIA_MOUNTED.equals(state)
                && storageDir.exists()
                && storageDir.isDirectory()
                && storageDir.canRead()
                && storageDir.canWrite();
        boolean pendingUsbReturn = Y2BootState.isUsbReturnPending(this);
        boolean quietReturn = !pendingUsbReturn
                || (Y2BootState.isLatestStorageEventReturnCandidate(this)
                && Y2BootState.storageEventAgeMs(this) >= USB_RETURN_SETTLE_MS);
        boolean secondaryReady = !Y2BootState.isSecondaryStorageExpected(this)
                || new File("/storage/sdcard1").canRead()
                || attempt >= 15;
        Y2Marker.write(this, "RockboxActivity:storageGate"
                + " pendingUsbReturn=" + pendingUsbReturn
                + " quietReturn=" + quietReturn
                + " storageEventAgeMs=" + Y2BootState.storageEventAgeMs(this)
                + " secondaryExpected=" + Y2BootState.isSecondaryStorageExpected(this)
                + " secondaryReady=" + secondaryReady);
        if (primaryReady && quietReturn && secondaryReady) {
            if (pendingUsbReturn)
                Y2BootState.completeUsbStorageReturn(this, "activity-storage-gate");
            startRockboxService();
        } else if (attempt < MAX_ATTEMPTS - 1) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    waitForStorageAndInit(attempt + 1);
                }
            }, 1000);
        } else {
            Toast.makeText(this, "Storage is not ready for Rockbox.", Toast.LENGTH_LONG).show();
            Y2Marker.write(this, "RockboxActivity:storage unavailable, not starting service");
            finish();
        }
    }

    private void startRockboxService() {
        Y2Marker.write(this, "RockboxActivity:startRockboxService");
        Intent intent = new Intent(this, RockboxService.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.putExtra("callback", new ResultReceiver(new Handler(getMainLooper())) {
            private boolean unzip = false;
            private ProgressDialog loadingdialog;
            private void createProgressDialog()
            {
                loadingdialog = new ProgressDialog(RockboxActivity.this);
                loadingdialog.setMessage(getString(R.string.rockbox_extracting));
                loadingdialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                loadingdialog.setIndeterminate(true);
                loadingdialog.setCancelable(false);
                loadingdialog.show();
            }

            @Override
            protected void onReceiveResult(final int resultCode, final Bundle resultData)
            {
                if (resultCode != RockboxService.RESULT_LIB_LOAD_PROGRESS) {
                    Y2Marker.write(RockboxActivity.this,
                            "RockboxActivity:onReceiveResult resultCode=" + resultCode);
                }
                switch (resultCode) {
                    case RockboxService.RESULT_INVOKING_MAIN:
                        if (loadingdialog != null)
                            loadingdialog.dismiss();
                        attachFramebufferIfNeeded(
                                "before native main display");
                        break;
                    case RockboxService.RESULT_LIB_LOAD_PROGRESS:
                        if (loadingdialog == null)
                            createProgressDialog();
                        loadingdialog.setIndeterminate(false);
                        loadingdialog.setMax(resultData.getInt("max", 100));
                        loadingdialog.setProgress(resultData.getInt("value", 0));
                        int max = resultData.getInt("max", 100);
                        int value = resultData.getInt("value", 0);
                        if (value == 1 || value == max || value % 100 == 0) {
                            Y2Marker.write(RockboxActivity.this,
                                    "RockboxActivity:extract progress value=" + value
                                    + " max=" + max);
                        }
                        break;
                    case RockboxService.RESULT_LIB_LOADED:
                        unzip = resultData.getBoolean("unzip");
                        break;
                    case RockboxService.RESULT_SERVICE_RUNNING:
                        if (!unzip) /* defer to RESULT_INVOKING_MAIN */
                        {
                            attachFramebufferIfNeeded("for running service");
                        }
                        setServiceActivity(true);
                        break;
                    case RockboxService.RESULT_ERROR_OCCURED:
                        Toast.makeText(RockboxActivity.this, resultData.getString("error"), Toast.LENGTH_LONG);
                        break;
                    case RockboxService.RESULT_ROCKBOX_EXIT:
                        finish();
                        break;
                }
            }
        });
        startService(intent);
        Y2Marker.write(this, "RockboxActivity:startService returned");
    }

    private void attachFramebufferIfNeeded(String reason)
    {
        if (framebufferAttached) {
            Y2Marker.write(this,
                    "RockboxActivity:framebuffer attach skipped already attached "
                    + "reason=" + reason);
            return;
        }
        Y2Marker.write(this,
                "RockboxActivity:creating framebuffer " + reason);
        RockboxFramebuffer framebuffer = new RockboxFramebuffer(this);
        setContentView(framebuffer);
        framebuffer.requestFocus();
        framebufferAttached = true;
        Y2Marker.write(this,
                "RockboxActivity:framebuffer attached " + reason);
    }

    private void setServiceActivity(boolean set)
    {
        RockboxService s = RockboxService.getInstance();
        if (s != null)
            s.setActivity(set ? this : null);
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Y2BootState.isUsbReturnPending(this)
                && Y2BootState.isLatestStorageEventReturnCandidate(this)
                && Y2BootState.storageEventAgeMs(this) >= USB_RETURN_SETTLE_MS) {
            Y2BootState.completeUsbStorageReturn(this,
                    "existing-activity-stable-storage-reassert");
        }
        Y2Marker.write(this, "RockboxActivity:onNewIntent "
                + Y2BootState.describe(this));
    }

    public void onResume()
    {
        super.onResume();
        Y2Marker.write(this, "RockboxActivity:onResume " + Y2BootState.describe(this));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setVisible(true);
        setServiceActivity(true);
        if (framebufferAttached && Y2BootState.isUsbStorageWindowActive(this)) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    signalExistingActivityReady();
                }
            }, 100L);
        }
    }

    private void signalExistingActivityReady() {
        if (!framebufferAttached || isFinishing())
            return;
        try {
            Intent ready = new Intent(this, Y2RouteShieldService.class);
            ready.setAction(Y2RouteShieldService.ACTION_ACTIVITY_READY);
            startService(ready);
            Y2Marker.write(this,
                    "RockboxActivity:existing framebuffer signalled to route shield");
        } catch (Throwable t) {
            Y2Marker.write(this,
                    "RockboxActivity:existing framebuffer signal failed", t);
        }
    }
    
    /* this is also called when the backlight goes off,
     * which is nice 
     */
    @Override
    protected void onPause() 
    {
        super.onPause();
        Y2Marker.write(this, "RockboxActivity:onPause " + Y2BootState.describe(this));
        /* this will cause the framebuffer's Surface to be destroyed, enabling
         * us to disable drawing */
        setVisible(false);
        Y2Marker.write(this, "RockboxActivity:onPause no foreground reassert in v41 "
                + Y2BootState.describe(this));
    }
    
    @Override
    protected void onStop() 
    {
        super.onStop();
        Y2Marker.write(this, "RockboxActivity:onStop " + Y2BootState.describe(this));
        setServiceActivity(false);
        Y2Marker.write(this, "RockboxActivity:onStop no foreground reassert in v41 "
                + Y2BootState.describe(this));
    }
    
    @Override
    protected void onDestroy() 
    {
        super.onDestroy();
        Y2Marker.write(this, "RockboxActivity:onDestroy " + Y2BootState.describe(this));
        setServiceActivity(false);
    }
}
