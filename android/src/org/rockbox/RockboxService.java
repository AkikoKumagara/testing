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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.rockbox.Helper.Logger;
import org.rockbox.Helper.MediaButtonReceiver;
import org.rockbox.Helper.RunForegroundManager;
import org.rockbox.Helper.BrightnessController;
import org.rockbox.Helper.ExternalAppsManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.KeyEvent;
import android.util.Log;

/* This class is used as the main glue between java and c.
 * All access should be done through RockboxService.get_instance() for safety.
 */

public class RockboxService extends Service
{
    private static final String Y2_CONFIG_VERSION = "26";
    private static final String Y2_RESOURCE_VERSION = "23";
    private static final String Y2_RESOURCE_MARKER = "y2_resource_version.txt";
    private static final String[] Y2_REQUIRED_RESOURCE_FILES = {
        "themes/iClassic-MOD.cfg",
        "wps/iClassic-MOD.wps",
        "themes/iClassic.cfg",
        "themes/PodOne.cfg",
        "wps/PodOne.wps",
        "themes/SNAZZ3.cfg",
        "wps/SNAZZ3.wps",
        "themes/OneBitMono.cfg",
        "wps/OneBitMono.wps",
        "wps/OneBitMono.sbs",
        "wps/OneBitMono/progressbar.bmp",
        "rocks/demos/pictureflow.rock"
    };

    /* this Service is really a singleton class - well almost. */
    private static RockboxService instance = null;

    /* locals needed for the c code and Rockbox state */
    private static volatile boolean rockbox_running;
    private Activity mCurrentActivity = null;
    private RunForegroundManager mFgRunner;
    private MediaButtonReceiver mMediaButtonReceiver;
    private ResultReceiver mResultReceiver;

    /* possible result values for intent handling */ 
    public static final int RESULT_INVOKING_MAIN = 0;
    public static final int RESULT_LIB_LOAD_PROGRESS = 1;
    public static final int RESULT_SERVICE_RUNNING = 3;
    public static final int RESULT_ERROR_OCCURED = 4;
    public static final int RESULT_LIB_LOADED = 5;
    public static final int RESULT_ROCKBOX_EXIT = 6;

    @Override
    public void onCreate()
    {
        Y2Marker.installUncaughtExceptionHandler();
        Y2Marker.write(this, "RockboxService:onCreate");
        instance = this;
        mMediaButtonReceiver = new MediaButtonReceiver(this);
        mFgRunner = new RunForegroundManager(this);
    }

    public static RockboxService getInstance()
    {
        /* don't call the constructor here, the instances are managed by
         * android, so we can't just create a new one */
        return instance;
    }

    public boolean isRockboxRunning()
    {
        return rockbox_running;
    }
    public Activity getActivity()
    {
        return mCurrentActivity;
    }

    public void setActivity(Activity a)
    {
        mCurrentActivity = a;
    }
    
    private void putResult(int resultCode)
    {
        putResult(resultCode, null);
    }

    private void putResult(int resultCode, Bundle resultData)
    {
        if (mResultReceiver != null)
            mResultReceiver.send(resultCode, resultData);
    }

    private void doStart(Intent intent)
    {
        Y2Marker.write(this, "RockboxService:doStart action="
                + (intent == null ? "null" : intent.getAction())
                + " rockbox_running=" + rockbox_running);
        if (!Y2BootState.canLaunchRockbox(this, "RockboxService:doStart")) {
            Y2Marker.write(this, "RockboxService:deny start by launch guard "
                    + Y2BootState.describe(this));
            stopSelf();
            return;
        }
        Logger.d("Start RockboxService (Intent: " + intent.getAction() + ")");

        if (intent.getAction().equals("org.rockbox.ResendTrackUpdateInfo"))
        {
            if (rockbox_running)
                mFgRunner.resendUpdateNotification();
            return;
        }

        if (intent.hasExtra("callback"))
            mResultReceiver = (ResultReceiver) intent.getParcelableExtra("callback");

        if (!rockbox_running)
            startService();

        if (intent.getAction().equals(Intent.ACTION_MEDIA_BUTTON))
        {
            KeyEvent kev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            /* Only handle non-repeat events in RockboxService */
            /* Repeat events are handled by MediaButtonReceiver */
            if (kev.getRepeatCount() == 0)
            {
                /* Normal press/release event */
                RockboxFramebuffer.buttonHandler(kev.getKeyCode(),
                                    kev.getAction() == KeyEvent.ACTION_DOWN);
            }
        }

        /* (Re-)attach the media button receiver, in case it has been lost */
        mMediaButtonReceiver.register();
        putResult(RESULT_SERVICE_RUNNING);
        Y2Marker.write(this, "RockboxService:RESULT_SERVICE_RUNNING sent");

        rockbox_running = true;
    }

    public void onStart(Intent intent, int startId) {
        doStart(intent);
    }

    public int onStartCommand(Intent intent, int flags, int startId)
    {
        /* if null, then the service was most likely restarted by android
         * after getting killed for memory pressure earlier */
        if (intent == null)
            intent = new Intent("org.rockbox.ServiceRestarted");
        doStart(intent);
        return START_STICKY;
    }

    private void startService()
    {
        Y2Marker.write(this, "RockboxService:startService thread setup");
        final Object lock = new Object();
        Thread rb = new Thread(new Runnable()
        {
            public void run()
            {
                Y2Marker.installUncaughtExceptionHandler();
                Y2Marker.write(RockboxService.this, "RockboxService:rockbox thread start");
                final int BUFFER = 8*1024;
                String rockboxDirPath = "/storage/sdcard0/.rockbox";
                String rockboxCreditsPath = "/data/data/org.rockbox/app_rockbox/rockbox/rocks/viewers";
                String rockboxSdDirPath = "/storage/sdcard0/.rockbox";
                String sdViewersPath = "/storage/sdcard0/.rockbox/rocks/viewers";

                /* Check if credits.rock exists in internal app directory */
                File internalCreditsFile = new File(rockboxCreditsPath, "credits.rock");
                File sdViewersDir = new File(sdViewersPath);

                /* If internal credits file doesn't exist and SD viewers directory exists, copy it */
                if (!internalCreditsFile.exists() && sdViewersDir.exists() && sdViewersDir.isDirectory()) {
                    Logger.d("Internal credits.rock not found, copying viewers from SD card");
                    Y2Marker.write(RockboxService.this,
                            "RockboxService:copying viewers from shared storage");
                    copyViewersFolder(sdViewersPath, rockboxCreditsPath);
                }

                /* the following block unzips libmisc.so, which contains the files 
                 * we ship, such as themes. It's needed to put it into a .so file
                 * because there's no other way to ship files and have access
                 * to them from native code
                 */
                File appLibMisc = new File("/data/data/org.rockbox/lib/libmisc.so");
                File systemLibMisc = new File("/system/lib/libmisc.so");
                File libMisc = appLibMisc.exists() ? appLibMisc : systemLibMisc;
                File rockboxInfoFile = new File(rockboxSdDirPath, "rockbox-info.txt");
                File resourceMarkerFile = new File(rockboxSdDirPath, Y2_RESOURCE_MARKER);
                boolean resourcesCurrent =
                        Y2_RESOURCE_VERSION.equals(readFirstLine(resourceMarkerFile));
                boolean requiredResourcesPresent = requiredResourcesPresent(new File(rockboxSdDirPath));
                boolean doExtract = !resourcesCurrent || !requiredResourcesPresent;
                Y2Marker.write(RockboxService.this,
                        "ResourceCheck: decision=" + (doExtract ? "extract" : "skip")
                        + " reason=" + resourceDecisionReason(resourcesCurrent, requiredResourcesPresent)
                        + " markerVersion=" + readFirstLine(resourceMarkerFile)
                        + " targetVersion=" + Y2_RESOURCE_VERSION
                        + " libmiscPath=" + libMisc.getAbsolutePath()
                        + " appLibmiscExists=" + appLibMisc.exists()
                        + " systemLibmiscExists=" + systemLibMisc.exists()
                        + " libmiscExists=" + libMisc.exists()
                        + " rockboxInfoExists=" + rockboxInfoFile.exists()
                        + " resourceMarkerExists=" + resourceMarkerFile.exists()
                        + " resourcesCurrent=" + resourcesCurrent
                        + " requiredResourcesPresent=" + requiredResourcesPresent
                        + " doExtract=" + doExtract);

                /* load library before unzipping which may take a while
                 * but at least tell if unzipping is going to be done before*/
                synchronized (lock) {
                    Bundle bdata = new Bundle();
                    bdata.putBoolean("unzip", doExtract);
                    Y2Marker.write(RockboxService.this,
                            "RockboxService:System.loadLibrary rockbox begin");
                    System.loadLibrary("rockbox");
                    Y2Marker.write(RockboxService.this,
                            "RockboxService:System.loadLibrary rockbox success");
                    putResult(RESULT_LIB_LOADED, bdata);
                    Y2Marker.write(RockboxService.this,
                            "RockboxService:RESULT_LIB_LOADED sent");
                    lock.notify();
                }

                if (doExtract)
                {
                    Y2Marker.write(RockboxService.this, "RockboxService:extract begin");
                    boolean extractToSd = false;
                    if(rockboxInfoFile.exists()) {
                        extractToSd = true;
                        Logger.d("extracting resources to SD card");
                    }
                    else {
                        Logger.d("extracting resources to internal memory");
                    }
                    try
                    {
                        Bundle progressData = new Bundle();
                        byte data[] = new byte[BUFFER];
                        ZipFile zipfile = new ZipFile(libMisc);
                        Enumeration<? extends ZipEntry> e = zipfile.entries();
                        progressData.putInt("max", zipfile.size());

                        while(e.hasMoreElements())
                        {
                           ZipEntry entry = (ZipEntry) e.nextElement();
                           File file;
                           /* strip off /.rockbox when extracting */
                           String fileName = entry.getName();
                           int slashIndex = fileName.indexOf('/', 1);
                           /* codecs are now stored as libs, only keep rocks on internal */
                           if(extractToSd == false
                               || fileName.substring(slashIndex).startsWith("/rocks"))
                           {
                               file = new File(rockboxDirPath + fileName.substring(slashIndex));
                           }
                           else
                           {
                               file = new File(rockboxSdDirPath + fileName.substring(slashIndex));
                           }

                           if (!entry.isDirectory())
                           {
                               /* Create the parent folders if necessary */
                               File folder = new File(file.getParent());
                               if (!folder.exists())
                                   folder.mkdirs();

                               /* Extract file */
                               BufferedInputStream is = new BufferedInputStream(zipfile.getInputStream(entry), BUFFER);
                               FileOutputStream fos = new FileOutputStream(file);
                               BufferedOutputStream dest = new BufferedOutputStream(fos, BUFFER);

                               int count;
                               while ((count = is.read(data, 0, BUFFER)) != -1)
                                  dest.write(data, 0, count);

                               dest.flush();
                               dest.close();
                               is.close();
                           }

                           progressData.putInt("value", progressData.getInt("value", 0) + 1);
                           putResult(RESULT_LIB_LOAD_PROGRESS, progressData);
                        }
                        writeTextFile(resourceMarkerFile, Y2_RESOURCE_VERSION + "\n");
                        Y2Marker.write(RockboxService.this, "RockboxService:extract complete");
                    } catch(Exception e) {
                        Logger.d("Exception when unzipping", e);
                        Y2Marker.write(RockboxService.this,
                                "RockboxService:extract exception", e);
                        Bundle bundle = new Bundle();
                        e.printStackTrace();
                        bundle.putString("error", getString(R.string.error_extraction));
                        putResult(RESULT_ERROR_OCCURED, bundle);
                    }
                }

                logStorageAndDatabaseState("before-config");

                /* Generate or upgrade only the Y2-owned default config. */
                File rockboxConfig = new File(Environment.getExternalStorageDirectory(), ".rockbox/config.cfg");
                if (shouldWriteDefaultConfig(rockboxConfig)) {
                    Y2Marker.write(RockboxService.this,
                            "RockboxService:writing v26 default config path="
                            + rockboxConfig.getAbsolutePath());
                    File rbDir = new File(rockboxConfig.getParent());
                    if (!rbDir.exists())
                        rbDir.mkdirs();

                    try {
                        writeDefaultConfig(rockboxConfig);
                    } catch(Exception e) {
                        Logger.d("Exception when writing default config", e);
                        Y2Marker.write(RockboxService.this,
                                "RockboxService:default config exception", e);
                    }
                }
                writeDefaultShortcuts();
                cleanEmptyDatabaseTemp();
                logStorageAndDatabaseState("before-native-main");

                /* Start native code */
                Y2Marker.write(RockboxService.this,
                        "RockboxService:RESULT_INVOKING_MAIN before native main");
                putResult(RESULT_INVOKING_MAIN);

                Y2Marker.write(RockboxService.this, "RockboxService:native main begin");
                Y2BootState.markRockboxUsbSessionReady(
                        RockboxService.this, "native-main-begin");
                scheduleHealthyProcessMarker();
                main();
                Y2Marker.write(RockboxService.this, "RockboxService:native main returned");

                Intent recovery = new Intent(RockboxService.this,
                        Y2RouteShieldService.class);
                recovery.setAction(Y2RouteShieldService.ACTION_NATIVE_EXIT_RECOVERY);
                startService(recovery);
                Y2Marker.write(RockboxService.this,
                        "RockboxService:native-exit recovery handed to route process");

                putResult(RESULT_ROCKBOX_EXIT);
                Y2Marker.write(RockboxService.this, "RockboxService:RESULT_ROCKBOX_EXIT sent");

                Logger.d("Stop service: main() returned");
                stopSelf(); /* service is of no use anymore */
            }
        }, "Rockbox thread");
        rb.setDaemon(false);
        /* wait at least until the library is loaded */
        synchronized (lock)
        {
            Y2Marker.write(this, "RockboxService:starting rockbox thread");
            rb.start();
            while(true)
            {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    continue;
                }
                break;
            }
            Y2Marker.write(this, "RockboxService:library load wait complete");
        }
    }

    private native void main();

    private boolean shouldWriteDefaultConfig(File rockboxConfig) {
        if (!rockboxConfig.exists())
            return true;

        BufferedReader reader = null;
        boolean generated = false;
        boolean current = false;
        try {
            reader = new BufferedReader(new FileReader(rockboxConfig));
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 20) {
                if ("# config generated by RockboxService".equals(line))
                    generated = true;
                if (line.startsWith("# y2 config version: ")
                        && line.endsWith(Y2_CONFIG_VERSION))
                    current = true;
                lines++;
            }
        } catch (Exception e) {
            Y2Marker.write(this, "RockboxService:default config probe exception", e);
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }

        boolean shouldWrite = generated && !current;
        Y2Marker.write(this, "RockboxService:shouldWriteDefaultConfig generated="
                + generated + " current=" + current + " targetVersion="
                + Y2_CONFIG_VERSION + " shouldWrite=" + shouldWrite);
        return shouldWrite;
    }

    private void writeDefaultConfig(File rockboxConfig) throws IOException {
        OutputStreamWriter strm = new OutputStreamWriter(new FileOutputStream(rockboxConfig));
        try {
            strm.write("# config generated by RockboxService\n");
            strm.write("# y2 config version: " + Y2_CONFIG_VERSION + "\n");
            strm.write("volume: -75\n");
            strm.write("wps: /.rockbox/wps/MacClassic.wps\n");
            strm.write("fms: -\n");
            strm.write("sbs: -\n");
            strm.write("backdrop: /.rockbox/backdrops/MacClassic480.bmp\n");
            strm.write("font: /.rockbox/fonts/24-Terminus-Bold.fnt\n");
            strm.write("iconset: /.rockbox/-\n");
            strm.write("viewers iconset: /.rockbox/-\n");
            strm.write("selector type: bar (inverse)\n");
            strm.write("statusbar: off\n");
            strm.write("scrollbar: off\n");
            strm.write("list padding: 10\n");
            strm.write("show icons: off\n");
            strm.write("foreground color: 000000\n");
            strm.write("background color: FFFFFF\n");
            strm.write("line selector start color: FFFFFF\n");
            strm.write("line selector end color: FFFFFF\n");
            strm.write("line selector text color: 000000\n");
            strm.write("list separator height: auto\n");
            strm.write("list separator color: 808080\n");
            strm.write("ui viewport: 6,3,468,354,1,000000,FFFFFF\n");
            strm.write("list wraparound: off\n");
            strm.write("root menu order: shortcuts,database,wps,files,settings,playlists,system_menu\n");
            strm.write("start in screen: root\n");
            strm.write("default browser: database\n");
            strm.write("database scan paths: /storage/sdcard1/Music:/storage/sdcard0/Music:/sdcard/Music:/sdcard\n");
            strm.write("tagcache_autoupdate: off\n");
            strm.write("max files in playlist: 32000\n");
            strm.write("max files in dir: 10000\n");
            strm.write("start directory: " + chooseStartDirectory() + "\n");
            strm.write("qs top: -\n");
            strm.write("qs left: shuffle\n");
            strm.write("qs right: repeat\n");
            strm.write("qs bottom: -\n");
            strm.write("lang: /.rockbox/langs/" + getString(R.string.rockbox_language_file) + "\n");
            strm.write("wheel vibration intensity: 15\n");
        } finally {
            strm.close();
        }
    }

    private void writeDefaultShortcuts() {
        File rbDir = new File(Environment.getExternalStorageDirectory(), ".rockbox");
        if (!rbDir.exists())
            rbDir.mkdirs();

        File shortcuts = new File(rbDir, "shortcuts.txt");
        if (shortcuts.exists() && !shouldWriteDefaultShortcuts(shortcuts)) {
            Y2Marker.write(this, "RockboxService:shortcuts existing path="
                    + shortcuts.getAbsolutePath() + " size=" + shortcuts.length());
            return;
        }

        OutputStreamWriter strm = null;
        try {
            strm = new OutputStreamWriter(new FileOutputStream(shortcuts));
            strm.write("# shortcuts generated by RockboxService\n");
            strm.write("# y2 shortcuts version: " + Y2_CONFIG_VERSION + "\n");
            writeShortcut(strm, "separator", "", "Music");
            writeShortcut(strm, "browse", "/storage/sdcard1/Music/", "SD Music");
            writeShortcut(strm, "browse", "/storage/sdcard0/Music/", "Internal Music");
            writeShortcut(strm, "file", "/.rockbox/rocks/demos/pictureflow.rock", "PictureFlow");
            writeShortcut(strm, "playlist menu", "/storage/sdcard1/Music/", "Play SD Music...");
            writeShortcut(strm, "playlist menu", "/storage/sdcard0/Music/", "Play Internal Music...");
            writeShortcut(strm, "setting", "shuffle", "Shuffle");
            Y2Marker.write(this, "RockboxService:shortcuts written path="
                    + shortcuts.getAbsolutePath());
        } catch (Exception e) {
            Y2Marker.write(this, "RockboxService:shortcuts exception", e);
        } finally {
            if (strm != null) {
                try {
                    strm.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean shouldWriteDefaultShortcuts(File shortcuts) {
        BufferedReader reader = null;
        boolean generated = false;
        boolean current = false;
        try {
            reader = new BufferedReader(new FileReader(shortcuts));
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 20) {
                if ("# shortcuts generated by RockboxService".equals(line))
                    generated = true;
                if (line.startsWith("# y2 shortcuts version: ")
                        && line.endsWith(Y2_CONFIG_VERSION))
                    current = true;
                lines++;
            }
        } catch (Exception e) {
            Y2Marker.write(this, "RockboxService:shortcuts probe exception", e);
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }

        boolean shouldWrite = generated && !current;
        Y2Marker.write(this, "RockboxService:shouldWriteDefaultShortcuts generated="
                + generated + " current=" + current + " targetVersion="
                + Y2_CONFIG_VERSION + " shouldWrite=" + shouldWrite);
        return shouldWrite;
    }

    private static void writeShortcut(OutputStreamWriter strm, String type,
            String data, String name) throws IOException {
        strm.write("[shortcut]\n");
        strm.write("type: " + type + "\n");
        strm.write("data: " + data + "\n");
        strm.write("name: " + name + "\n\n");
    }

    private void logStorageAndDatabaseState(String label) {
        File external = Environment.getExternalStorageDirectory();
        Y2Marker.write(this, "RockboxService:diagnostic label=" + label
                + " external=" + external.getAbsolutePath()
                + " exists=" + external.exists()
                + " canRead=" + external.canRead()
                + " canWrite=" + external.canWrite());
        logDirectory("sdcard0-music", new File("/storage/sdcard0/Music"));
        logDirectory("sdcard1-music", new File("/storage/sdcard1/Music"));
        logDirectory("sdcard-root", external);

        File rbDir = new File(external, ".rockbox");
        logFile("config", new File(rbDir, "config.cfg"));
        logFile("shortcuts", new File(rbDir, "shortcuts.txt"));
        logFile("resource-marker", new File(rbDir, Y2_RESOURCE_MARKER));
        logFile("database_idx", new File(rbDir, "database_idx.tcd"));
        logFile("database_tmp", new File(rbDir, "database_tmp.tcd"));
        for (int i = 0; i <= 12; i++)
            logFile("database_" + i, new File(rbDir, "database_" + i + ".tcd"));

        logFile("pictureflow-rock", new File(rbDir, "rocks/demos/pictureflow.rock"));
        logFile("pictureflow-splash", new File(rbDir, "rocks/demos/pictureflow_splash.bmp"));
        logFile("pictureflow-empty", new File(rbDir, "rocks/demos/pictureflow_emptyslide.bmp"));
        logFile("theme-macclassic-cfg", new File(rbDir, "themes/MacClassic.cfg"));
        logFile("theme-macclassic-dark-cfg", new File(rbDir, "themes/MacClassic-Dark.cfg"));
        logFile("theme-square-cfg", new File(rbDir, "themes/iClassic-MOD.cfg"));
        logFile("theme-square-wps", new File(rbDir, "wps/iClassic-MOD.wps"));
        logFile("theme-original-cfg", new File(rbDir, "themes/iClassic.cfg"));
        logFile("theme-podone-cfg", new File(rbDir, "themes/PodOne.cfg"));
        logFile("theme-podone-wps", new File(rbDir, "wps/PodOne.wps"));
        logFile("theme-snazz3-cfg", new File(rbDir, "themes/SNAZZ3.cfg"));
        logFile("theme-snazz3-wps", new File(rbDir, "wps/SNAZZ3.wps"));
        logConfigState(new File(rbDir, "config.cfg"));
        logDatabaseState(rbDir);
        logDirectory("rockbox-themes", new File(rbDir, "themes"));
        logDirectory("stock-internal-themes", new File("/storage/sdcard0/Themes"));
        logDirectory("stock-sd-themes", new File("/storage/sdcard1/Themes"));
    }

    private boolean requiredResourcesPresent(File rbDir) {
        boolean present = true;
        for (int i = 0; i < Y2_REQUIRED_RESOURCE_FILES.length; i++) {
            File file = new File(rbDir, Y2_REQUIRED_RESOURCE_FILES[i]);
            boolean exists = file.exists();
            Y2Marker.write(this, "ResourceCheck: requiredFile path="
                    + file.getAbsolutePath()
                    + " exists=" + exists
                    + " size=" + (exists ? file.length() : -1));
            if (!exists)
                present = false;
        }
        return present;
    }

    private static String resourceDecisionReason(boolean resourcesCurrent,
            boolean requiredResourcesPresent) {
        if (!resourcesCurrent)
            return "marker-missing-or-outdated";
        if (!requiredResourcesPresent)
            return "required-files-missing";
        return "marker-current-required-files-present";
    }

    private void logDatabaseState(File rbDir) {
        File idx = new File(rbDir, "database_idx.tcd");
        File tmp = new File(rbDir, "database_tmp.tcd");
        int databaseFiles = 0;
        for (int i = 0; i <= 12; i++) {
            if (new File(rbDir, "database_" + i + ".tcd").exists())
                databaseFiles++;
        }
        boolean complete = idx.exists();
        Y2Marker.write(this, "DatabaseState: idxExists=" + idx.exists()
                + " idxSize=" + (idx.exists() ? idx.length() : -1)
                + " tmpExists=" + tmp.exists()
                + " tmpSize=" + (tmp.exists() ? tmp.length() : -1)
                + " databaseFiles=" + databaseFiles
                + " complete=" + complete);
        Y2Marker.write(this, "DatabaseGuard: action=allow reason=root-startup-no-auto-database");
    }

    private void cleanEmptyDatabaseTemp() {
        File rbDir = new File(Environment.getExternalStorageDirectory(), ".rockbox");
        File tmp = new File(rbDir, "database_tmp.tcd");
        if (!tmp.exists()) {
            Y2Marker.write(this, "DatabaseGuard: action=none reason=temp-missing");
            return;
        }
        long size = tmp.length();
        if (size > 12L) {
            Y2Marker.write(this, "DatabaseGuard: action=preserve reason=recoverable-payload"
                    + " tmpSize=" + size);
            return;
        }
        boolean deleted = tmp.delete();
        Y2Marker.write(this, "DatabaseGuard: action=delete-empty-temp"
                + " tmpSize=" + size + " deleted=" + deleted);
    }

    private void scheduleHealthyProcessMarker() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (RockboxService.getInstance() == RockboxService.this
                        && RockboxService.this.isRockboxRunning()) {
                    Y2BootState.markRockboxProcessHealthy(
                            RockboxService.this, "native-main-alive-20s");
                    Y2Marker.writeDebugSnapshot(
                            RockboxService.this, "RockboxService:healthy-20s");
                }
            }
        }, 20000L);
    }

    private void logConfigState(File config) {
        Y2Marker.write(this, "ConfigState: activeWps=" + readConfigValue(config, "wps:")
                + " activeSbs=" + readConfigValue(config, "sbs:")
                + " activeFont=" + readConfigValue(config, "font:")
                + " activeBackdrop=" + readConfigValue(config, "backdrop:"));
    }

    private static String readConfigValue(File config, String prefix) {
        BufferedReader reader = null;
        try {
            if (!config.exists())
                return "<missing>";
            reader = new BufferedReader(new FileReader(config));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(prefix))
                    return line.substring(prefix.length()).trim();
            }
            return "<unset>";
        } catch (Exception e) {
            return "<error>";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void logDirectory(String label, File dir) {
        int files = 0;
        int dirs = 0;
        File[] children = null;
        if (dir.exists() && dir.isDirectory() && dir.canRead())
            children = dir.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                if (children[i].isDirectory())
                    dirs++;
                else
                    files++;
            }
        }
        Y2Marker.write(this, "RockboxService:dir " + label
                + " path=" + dir.getAbsolutePath()
                + " exists=" + dir.exists()
                + " dir=" + dir.isDirectory()
                + " canRead=" + dir.canRead()
                + " files=" + files
                + " dirs=" + dirs);
    }

    private void logFile(String label, File file) {
        Y2Marker.write(this, "RockboxService:file " + label
                + " path=" + file.getAbsolutePath()
                + " exists=" + file.exists()
                + " size=" + (file.exists() ? file.length() : -1)
                + " modified=" + (file.exists() ? file.lastModified() : -1));
    }

    private static String readFirstLine(File file) {
        BufferedReader reader = null;
        try {
            if (!file.exists())
                return null;
            reader = new BufferedReader(new FileReader(file));
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void writeTextFile(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();

        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file));
        try {
            writer.write(text);
        } finally {
            writer.close();
        }
    }

    private String chooseStartDirectory() {
        String[] candidates = {
            "/storage/sdcard1/Music",
            "/storage/sdcard0/Music",
            Environment.getExternalStorageDirectory().getAbsolutePath()
        };

        for (int i = 0; i < candidates.length; i++) {
            File dir = new File(candidates[i]);
            if (dir.exists() && dir.isDirectory() && dir.canRead())
                return slash(candidates[i]);
        }
        return slash(Environment.getExternalStorageDirectory().getAbsolutePath());
    }

    private static String slash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    void startForeground()
    {
        mFgRunner.startForeground();
    }

    void stopForeground()
    {
        mFgRunner.stopForeground();
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        Y2Marker.write(this, "RockboxService:onDestroy");
        /* Don't unregister so we can receive them (and startup the service)
         * after idle power-off. Hopefully it's OK if mMediaButtonReceiver is
         * garbage collected.
         *  mMediaButtonReceiver.unregister(); */
        mMediaButtonReceiver = null;
        /* Make sure our notification is gone. */
        stopForeground();
        instance = null;
        rockbox_running = false;
        System.runFinalization();
        /* exit() seems unclean but is needed in order to get the .so file garbage 
         * collected, otherwise Android caches this Service and librockbox.so
         * The library must be reloaded to zero the bss and reset data
         * segment */
        System.exit(0);
    }

    /* Android brightness control methods for JNI interface */
    private BrightnessController brightnessController = null;

    /**
     * Set Android brightness to a specific percentage
     * Called from native code via JNI
     */
    public int setAndroidBrightnessPercent(int percent)
    {
        if (brightnessController == null) {
            brightnessController = new BrightnessController();
        }
        return brightnessController.setBrightnessPercent(percent);
    }

    /**
     * Get current Android brightness as percentage
     * Called from native code via JNI
     * @return Current brightness percentage (0-100)
     */
    public int getAndroidBrightnessPercent()
    {
        if (brightnessController == null) {
            brightnessController = new BrightnessController();
        }
        return brightnessController.getBrightnessPercent();
    }

    /* Android external apps methods for JNI interface */
    private ExternalAppsManager externalAppsManager = null;

    /**
     * Get the number of installed applications
     */
    public int getExternalAppsCount()
    {
        if (externalAppsManager == null) {
            externalAppsManager = new ExternalAppsManager(this);
        }
        return externalAppsManager.getAppCount();
    }

    /**
     * Get app name by index
     */
    public String getExternalAppName(int index)
    {
        if (externalAppsManager == null) {
            externalAppsManager = new ExternalAppsManager(this);
        }

        try {
            java.util.List<ExternalAppsManager.AppInfo> apps = externalAppsManager.getInstalledApps();
            if (index >= 0 && index < apps.size()) {
                return apps.get(index).appName;
            }
        } catch (Exception e) {
            Logger.d("Error getting app name at index: " + index, e);
        }
        return null;
    }

    /**
     * Get app package name by index
     */
    public String getExternalAppPackageName(int index)
    {
        if (externalAppsManager == null) {
            externalAppsManager = new ExternalAppsManager(this);
        }

        try {
            java.util.List<ExternalAppsManager.AppInfo> apps = externalAppsManager.getInstalledApps();
            if (index >= 0 && index < apps.size()) {
                return apps.get(index).packageName;
            }
        } catch (Exception e) {
            Logger.d("Error getting app package name at index: " + index, e);
        }
        return null;
    }

    /**
     * Launch an application by index
     */
    public boolean launchExternalApp(int index)
    {
        if (externalAppsManager == null) {
            externalAppsManager = new ExternalAppsManager(this);
        }

        try {
            java.util.List<ExternalAppsManager.AppInfo> apps = externalAppsManager.getInstalledApps();
            if (index >= 0 && index < apps.size()) {
                return externalAppsManager.launchApp(apps.get(index));
            }
        } catch (Exception e) {
            Logger.d("Error launching app at index: " + index, e);
        }
        return false;
    }

    public void shutdownDevice() {
        final Activity activity = getActivity();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(activity)
                    .setTitle("Shutdown Device")
                    .setMessage("Are you sure you want to shut down the device?")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d("RockboxPower", "Root shutdown disabled for Y2 safe build");
                        }
                    })
                    .setNegativeButton("No", null)
                    .show();
            }
        });
    }

    public static void setSystemTimeAsRoot(final String dateString) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d("RockboxTime", "Root time set disabled for Y2 safe build: " + dateString);
                } catch (Exception e) {
                    Log.e("RockboxTime", "Failed to set date as root: " + e.getMessage());
                }
            }
        }).start();
    }

    private void copyViewersFolder(String sourceDir, String destDir) {
        File srcDir = new File(sourceDir);
        File destDirFile = new File(destDir);

        if (!srcDir.exists() || !srcDir.isDirectory()) {
            Logger.d("Source viewers directory not found: " + sourceDir);
            return;
        }

        if (!destDirFile.exists()) {
            destDirFile.mkdirs();
        }

        File[] files = srcDir.listFiles();
        if (files == null) {
            Logger.d("Error listing files in source viewers directory: " + sourceDir);
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                copyViewersFolder(file.getAbsolutePath(), destDirFile.getAbsolutePath() + File.separator + file.getName());
            } else {
                try {
                    BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
                    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(new File(destDirFile, file.getName())));
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, bytesRead);
                    }
                    bos.flush();
                    bos.close();
                    bis.close();
                } catch (IOException e) {
                    Logger.d("Error copying file: " + file.getAbsolutePath(), e);
                }
            }
        }
    }
}
