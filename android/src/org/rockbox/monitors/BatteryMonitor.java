/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 * $Id$
 *
 * Copyright (C) 2011 Thomas Martitz
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

package org.rockbox.monitors;

import org.rockbox.RockboxActivity;
import org.rockbox.Y2BootState;
import org.rockbox.Y2Marker;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

public class BatteryMonitor extends BroadcastReceiver
{
    private static final long Y2_UNPLUG_FALLBACK_DELAY_MS = 150L;

    private final IntentFilter mBattFilter;
    private final Context mContext;
    private final Handler mHandler;
    private int mPreviousPlugType = -1;
    @SuppressWarnings("unused")
    private int mBattLevel; /* read by native code */

    public BatteryMonitor(Context c)
    {
        mBattFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext = c.getApplicationContext();
        mHandler = new Handler(Looper.getMainLooper());
        attach();
    }

    @Override
    public void onReceive(Context arg0, Intent intent)
    {
       int rawlevel = intent.getIntExtra("level", -1);
       int scale = intent.getIntExtra("scale", -1);
       if (rawlevel >= 0 && scale > 0)
           mBattLevel = (rawlevel * 100) / scale;
       else
           mBattLevel = -1;

       int plugType = intent.getIntExtra("plugged", -1);
       int previousPlugType = mPreviousPlugType;
       mPreviousPlugType = plugType;
       if (previousPlugType > 0 && plugType == 0)
           scheduleY2UnplugReassert(previousPlugType);
    }

    private void scheduleY2UnplugReassert(int previousPlugType)
    {
        if (Y2BootState.isUsbStorageWindowActive(mContext)
                || Y2BootState.isUsbReturnPending(mContext)) {
            Y2Marker.write(mContext,
                    "BatteryMonitor:Y2 unplug reassert skipped"
                    + " reason=route-coordinator-owns-usb-handoff"
                    + " previousPlug=" + previousPlugType);
            return;
        }
        if (!Y2BootState.canLaunchRockbox(mContext,
                "battery-unplug-reassert-schedule"))
            return;

        Y2Marker.write(mContext,
                "BatteryMonitor:Y2 unplug fallback scheduled previousPlug="
                + previousPlugType + " currentPlug=0 delayMs="
                + Y2_UNPLUG_FALLBACK_DELAY_MS);
        mHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                reassertRockboxAfterUnplug(Y2_UNPLUG_FALLBACK_DELAY_MS);
            }
        }, Y2_UNPLUG_FALLBACK_DELAY_MS);
    }

    private void reassertRockboxAfterUnplug(long delayMs)
    {
        PowerManager power = (PowerManager)
                mContext.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isScreenOn()) {
            Y2Marker.write(mContext,
                    "BatteryMonitor:Y2 unplug reassert skipped screen-off delayMs="
                    + delayMs);
            return;
        }
        if (!Y2BootState.canLaunchRockbox(mContext,
                "battery-unplug-reassert-" + delayMs + "ms"))
            return;

        Intent launch = new Intent(mContext, RockboxActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mContext.startActivity(launch);
        Y2Marker.write(mContext,
                "BatteryMonitor:Y2 unplug reassert requested delayMs=" + delayMs);
    }

    private void attach()
    {
        mContext.registerReceiver(this, mBattFilter);
    }
}
