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

import java.nio.ByteBuffer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.os.Vibrator;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

public class RockboxFramebuffer extends SurfaceView 
                                 implements SurfaceHolder.Callback
{
    private static final int Y2_LCD_WIDTH = 480;
    private static final int Y2_LCD_HEIGHT = 360;

    private final DisplayMetrics metrics;
    private final ViewConfiguration view_config;
    private final Context markerContext;
    private Bitmap btm;
    private Rect srcRect;
    private Rect dstRect;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean loggedFirstUpdate;
    private boolean loggedFirstFullRefresh;
    private boolean notifiedRouteShield;

    private static final int[] duration_mapping = {
        0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50
    };

    private static final int CENTER_KEYCODE = KeyEvent.KEYCODE_ENTER; // 66
    private static final long LONG_PRESS_DURATION_MS = 1000;
    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private boolean centerLongPressDetected = false;
    private boolean centerKeyDownObserved = false;
    private boolean screenWasOff = false;
    private Runnable centerLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            centerLongPressDetected = true;
            Log.d("RockboxButton", "Turning off screen...");
        }
    };

    /* first stage init; needs to run from a thread that has a Looper 
     * setup stuff that needs a Context */
    public RockboxFramebuffer(Context c)
    {
        super(c);
        markerContext = c;
        metrics = c.getResources().getDisplayMetrics();
        view_config = ViewConfiguration.get(c);
        getHolder().setFormat(PixelFormat.RGB_565);
        getHolder().addCallback(this);
        /* Needed so we can catch KeyEvents */
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        /* don't draw until native is ready (2nd stage) */
        setEnabled(false);
        Y2Marker.write(markerContext,
                "RockboxFramebuffer:constructed displayMetrics="
                + metrics.widthPixels + "x" + metrics.heightPixels
                + " densityDpi=" + metrics.densityDpi
                + " density=" + metrics.density);
    }

    private void update(ByteBuffer framebuffer)
    {
        SurfaceHolder holder = getHolder();                            
        Canvas c = holder.lockCanvas();
        if (c == null)
			return;

        try {
            if (!isReadyForFrame()) {
                Y2Marker.write(markerContext,
                        "RockboxFramebuffer:update full skipped before surface ready");
                holder.unlockCanvasAndPost(c);
                return;
            }
            copyFrame(framebuffer);
            synchronized (holder)
            { /* draw */
                c.drawColor(Color.BLACK);
                c.drawBitmap(btm, srcRect, dstRect, null);
            }
            logFirstUpdate("full");
        } catch (Throwable t) {
            Y2Marker.write(markerContext, "RockboxFramebuffer:update full exception", t);
        }
        holder.unlockCanvasAndPost(c);
    }
    
    private void update(ByteBuffer framebuffer, Rect dirty)
    {
        SurfaceHolder holder = getHolder();         
        /*
         * The native dirty rectangle is expressed in Rockbox framebuffer
         * coordinates.  Passing it to SurfaceHolder also clips the Android
         * canvas, so pixels outside the dirty rectangle can retain the stock
         * launcher or USB activity after a surface handoff.  Always lock and
         * post the full surface; the copied bitmap is already a full frame.
         */
        Canvas c = holder.lockCanvas();
        
        if (c == null)
			return;

        try {
            if (!isReadyForFrame()) {
                Y2Marker.write(markerContext,
                        "RockboxFramebuffer:update rect skipped before surface ready");
                holder.unlockCanvasAndPost(c);
                return;
            }
            /* The Y2 build scales a full native framebuffer into the Android surface.
             * Partial Android dirty rectangles are unreliable when the native and
             * surface dimensions differ, so refresh the mapped frame each time.
             */
            copyFrame(framebuffer);
            synchronized (holder)
            {   /* draw */
                c.drawColor(Color.BLACK);
                c.drawBitmap(btm, srcRect, dstRect, null);
            }
            if (!loggedFirstFullRefresh) {
                loggedFirstFullRefresh = true;
                Y2Marker.write(markerContext,
                        "RockboxFramebuffer:dirty update promoted to full-surface refresh"
                        + " dirty=" + dirty.left + "," + dirty.top + "-"
                        + dirty.right + "," + dirty.bottom);
            }
            logFirstUpdate("rect " + dirty.left + "," + dirty.top + "-"
                    + dirty.right + "," + dirty.bottom);
        } catch (Throwable t) {
            Y2Marker.write(markerContext, "RockboxFramebuffer:update rect exception", t);
        }
        holder.unlockCanvasAndPost(c);
    }

    private boolean isReadyForFrame()
    {
        return btm != null && srcRect != null && dstRect != null;
    }

    private void copyFrame(ByteBuffer framebuffer)
    {
        framebuffer.rewind();
        btm.copyPixelsFromBuffer(framebuffer);
    }

    private void logFirstUpdate(String mode)
    {
        if (loggedFirstUpdate)
            return;
        loggedFirstUpdate = true;
        Y2Marker.write(markerContext,
                "RockboxFramebuffer:firstUpdate mode=" + mode
                + " native=" + Y2_LCD_WIDTH + "x" + Y2_LCD_HEIGHT
                + " surface=" + surfaceWidth + "x" + surfaceHeight
                + " bitmap=" + btm.getWidth() + "x" + btm.getHeight()
                + " dst=" + dstRect.left + "," + dstRect.top
                + "-" + dstRect.right + "," + dstRect.bottom);
        if (!notifiedRouteShield) {
            notifiedRouteShield = true;
            try {
                Intent ready = new Intent(markerContext,
                        Y2RouteShieldService.class);
                ready.setAction(Y2RouteShieldService.ACTION_FRAME_READY);
                markerContext.startService(ready);
                Y2Marker.write(markerContext,
                        "RockboxFramebuffer:first frame signalled to route shield");
            } catch (Throwable t) {
                Y2Marker.write(markerContext,
                        "RockboxFramebuffer:first frame signal failed", t);
            }
        }
    }

    public boolean onTouchEvent(MotionEvent me)
    {
        int x = (int) me.getX();
        int y = (int) me.getY();

        switch (me.getAction())
        {
        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_UP:
            touchHandler(false, x, y);
            return true;
        case MotionEvent.ACTION_MOVE:
        case MotionEvent.ACTION_DOWN:
            touchHandler(true, x, y);
            return true;
        }

        return false;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if ((keyCode == CENTER_KEYCODE) && event.getRepeatCount() == 0) {
            centerKeyDownObserved = true;
            centerLongPressDetected = false;
            longPressHandler.postDelayed(centerLongPressRunnable, LONG_PRESS_DURATION_MS);
            return true;
        }
        /* Handle repeat events */
        else {
            if (event.getRepeatCount() > 0)
            {
                return buttonHandlerRepeat(keyCode);
            }
            else
            {
                return buttonHandler(keyCode, true);
            }
        }
    }

    public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        if (keyCode == CENTER_KEYCODE) {
            // Cancel pending timer
            longPressHandler.removeCallbacks(centerLongPressRunnable);

            /* ENTER is mapped WAKE_DROPPED on the Y2.  Android may therefore
             * discard the asleep key-down but deliver its key-up after the
             * display wakes.  Never turn that orphan release into a synthetic
             * Rockbox click: the first center press must wake only.
             */
            if (!centerKeyDownObserved) {
                centerLongPressDetected = false;
                Log.d("RockboxButton", "Consumed orphan center key-up after wake");
                Y2Marker.write(markerContext,
                        "RockboxFramebuffer:consumed orphan center key-up after WAKE_DROPPED wake");
                return true;
            }
            centerKeyDownObserved = false;

            // Only send POWER keyevent if long-press was detected
            if (centerLongPressDetected) {
                centerLongPressDetected = false;
                Log.d("RockboxButton", "Center long-press screen toggle disabled for Y2 safe build");
                return true;
            } 
            else {
                if (!screenWasOff){
                    // center button was pressed but not long enough, handle like a normal press
                    buttonHandler(keyCode, true);
                    try {
                        // pause to make Rockbox catch up
                        Thread.sleep(10);
                        return buttonHandler(keyCode, false);
                    }
                    catch (InterruptedException e){
                        Log.e("RockboxButton", "Failed sending center key-up");
                    }
                } else {
                    screenWasOff = false;
                    Log.d("RockboxButton", "Screen was just off, do not process this center key press");
                }
            }
        }
        return buttonHandler(keyCode, false);
    }
 
    private int getDpi()
    {
        return metrics.densityDpi;
    }

    private int getScrollThreshold()
    {
        return view_config.getScaledTouchSlop();
    }

    private native void touchHandler(boolean down, int x, int y);
    public native static boolean buttonHandler(int keycode, boolean state);
    public native static boolean buttonHandlerRepeat(int keycode);
    public native static void triggerVibrationNative(int baseDuration, int boostDuration);
    
    public native void surfaceCreated(SurfaceHolder holder);
    public native void surfaceDestroyed(SurfaceHolder holder);
    
    /* Trigger vibration for button feedback */
    public static void triggerVibration(Context context, int baseDuration, int boostDuration) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                int base_ms = duration_mapping[baseDuration];
                int boost_ms = boostDuration;
                int total_ms = base_ms + boost_ms;
                vibrator.vibrate(total_ms);
            } else {
                android.util.Log.e("RockboxFramebuffer", "Vibrator is null");
            }
        } catch (Exception e) {
            android.util.Log.e("RockboxFramebuffer", "Vibration error: " + e.getMessage());
        }
    }
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        surfaceWidth = width;
        surfaceHeight = height;
        btm = Bitmap.createBitmap(Y2_LCD_WIDTH, Y2_LCD_HEIGHT, Bitmap.Config.RGB_565);
        srcRect = new Rect(0, 0, Y2_LCD_WIDTH, Y2_LCD_HEIGHT);
        dstRect = buildDestinationRect(width, height);
        loggedFirstUpdate = false;
        loggedFirstFullRefresh = false;
        /* A recreated surface must acknowledge a later USB-return launch too. */
        notifiedRouteShield = false;
        clearSurface(holder, "surfaceChanged");
        Y2Marker.write(markerContext,
                "RockboxFramebuffer:surfaceChanged format=" + format
                + " surface=" + width + "x" + height
                + " native=" + Y2_LCD_WIDTH + "x" + Y2_LCD_HEIGHT
                + " dst=" + dstRect.left + "," + dstRect.top
                + "-" + dstRect.right + "," + dstRect.bottom);
        setEnabled(true);
    }

    private void clearSurface(SurfaceHolder holder, String reason)
    {
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas != null)
                canvas.drawColor(Color.BLACK);
            Y2Marker.write(markerContext,
                    "RockboxFramebuffer:surface cleared reason=" + reason
                    + " surface=" + surfaceWidth + "x" + surfaceHeight);
        } catch (Throwable t) {
            Y2Marker.write(markerContext,
                    "RockboxFramebuffer:surface clear exception reason=" + reason, t);
        } finally {
            if (canvas != null)
                holder.unlockCanvasAndPost(canvas);
        }
    }

    private Rect buildDestinationRect(int width, int height)
    {
        if (width <= 0 || height <= 0)
            return new Rect(0, 0, Y2_LCD_WIDTH, Y2_LCD_HEIGHT);

        int scaledWidth = width;
        int scaledHeight = (Y2_LCD_HEIGHT * scaledWidth) / Y2_LCD_WIDTH;
        if (scaledHeight > height) {
            scaledHeight = height;
            scaledWidth = (Y2_LCD_WIDTH * scaledHeight) / Y2_LCD_HEIGHT;
        }

        int left = (width - scaledWidth) / 2;
        int top = (height - scaledHeight) / 2;
        return new Rect(left, top, left + scaledWidth, top + scaledHeight);
    }
}
