package org.rockbox;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;

public final class Y2Marker {
    private static final String BUILD_VERSION = "88";
    private static final String MARKER_NAME =
            "y2_rockbox_v88_route_readback_repair_marker.txt";
    private static final String DEBUG_NAME =
            "y2_rockbox_v88_route_readback_repair_snapshot.txt";
    private static boolean exceptionHandlerInstalled = false;

    private Y2Marker() {
    }

    public static synchronized void installUncaughtExceptionHandler() {
        if (exceptionHandlerInstalled)
            return;

        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable throwable) {
                write(null, "UNCAUGHT_EXCEPTION thread=" + thread.getName(), throwable);
                if (previous != null)
                    previous.uncaughtException(thread, throwable);
            }
        });
        exceptionHandlerInstalled = true;
    }

    public static void write(Context context, String event) {
        write(context, event, null);
    }

    public static void write(Context context, String event, Throwable throwable) {
        StringBuilder line = new StringBuilder();
        line.append("wallMs=");
        line.append(System.currentTimeMillis());
        line.append(" elapsedMs=");
        line.append(SystemClock.elapsedRealtime());
        line.append(" buildVersion=");
        line.append(BUILD_VERSION);
        line.append(" pid=");
        line.append(Process.myPid());
        line.append(" externalState=");
        line.append(Environment.getExternalStorageState());
        line.append(" ");
        line.append(event);
        line.append("\n");

        if (throwable != null) {
            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));
            line.append(stack.toString());
            line.append("\n");
        }

        writeToKnownLocations(context, line.toString());
    }

    public static void writeDebugSnapshot(Context context, String event) {
        StringBuilder text = new StringBuilder();
        text.append("event=");
        text.append(event);
        text.append("\nwallMs=");
        text.append(System.currentTimeMillis());
        text.append("\nelapsedMs=");
        text.append(SystemClock.elapsedRealtime());
        text.append("\nbuildVersion=");
        text.append(BUILD_VERSION);
        text.append("\npid=");
        text.append(Process.myPid());
        text.append("\nexternalState=");
        text.append(Environment.getExternalStorageState());
        text.append("\nexternalStorageDirectory=");
        File external = Environment.getExternalStorageDirectory();
        text.append(external == null ? "null" : external.getAbsolutePath());
        text.append("\ncontextFilesDir=");
        text.append(context == null ? "null" : context.getFilesDir().getAbsolutePath());
        text.append("\ncontextExternalFilesDir=");
        File externalFiles = context == null ? null : context.getExternalFilesDir(null);
        text.append(externalFiles == null ? "null" : externalFiles.getAbsolutePath());
        text.append("\nmodel=");
        text.append(Build.MODEL);
        text.append("\ndevice=");
        text.append(Build.DEVICE);
        text.append("\nproduct=");
        text.append(Build.PRODUCT);
        text.append("\nmanufacturer=");
        text.append(Build.MANUFACTURER);
        text.append("\nandroidRelease=");
        text.append(Build.VERSION.RELEASE);
        text.append("\nsdkInt=");
        text.append(Build.VERSION.SDK_INT);
        text.append("\nbootState=");
        text.append(context == null ? "context-null" : Y2BootState.describe(context));
        text.append("\nknownPaths:\n");
        appendPathStatus(text, "/storage/sdcard0");
        appendPathStatus(text, "/sdcard");
        appendPathStatus(text, "/mnt/sdcard");
        appendPathStatus(text, "/storage/sdcard1");
        appendPathStatus(text, "/mnt/external_sd");
        text.append("\n");

        writeToKnownLocations(context, "DebugSnapshot:" + event + "\n" + text.toString());
        writeDebugToKnownLocations(context, text.toString());
    }

    private static void writeToKnownLocations(Context context, String text) {
        HashSet<String> written = new HashSet<String>();
        appendOnce(written, new File("/storage/sdcard0", MARKER_NAME), text);
        appendOnce(written, new File("/sdcard", MARKER_NAME), text);
        appendOnce(written, new File("/storage/sdcard1", MARKER_NAME), text);
        appendOnce(written, new File("/mnt/external_sd", MARKER_NAME), text);

        File external = Environment.getExternalStorageDirectory();
        if (external != null)
            appendOnce(written, new File(external, MARKER_NAME), text);

        if (context != null) {
            File externalFiles = context.getExternalFilesDir(null);
            if (externalFiles != null)
                appendOnce(written, new File(externalFiles, MARKER_NAME), text);

            appendOnce(written, new File(context.getFilesDir(), MARKER_NAME), text);
        }
    }

    private static void writeDebugToKnownLocations(Context context, String text) {
        HashSet<String> written = new HashSet<String>();
        appendOnce(written, new File("/storage/sdcard0", DEBUG_NAME), text);
        appendOnce(written, new File("/sdcard", DEBUG_NAME), text);
        appendOnce(written, new File("/storage/sdcard1", DEBUG_NAME), text);
        appendOnce(written, new File("/mnt/external_sd", DEBUG_NAME), text);

        File external = Environment.getExternalStorageDirectory();
        if (external != null)
            appendOnce(written, new File(external, DEBUG_NAME), text);

        if (context != null) {
            File externalFiles = context.getExternalFilesDir(null);
            if (externalFiles != null)
                appendOnce(written, new File(externalFiles, DEBUG_NAME), text);

            appendOnce(written, new File(context.getFilesDir(), DEBUG_NAME), text);
        }
    }

    private static void appendPathStatus(StringBuilder text, String path) {
        File file = new File(path);
        text.append(path);
        text.append(" exists=");
        text.append(file.exists());
        text.append(" directory=");
        text.append(file.isDirectory());
        text.append(" canRead=");
        text.append(file.canRead());
        text.append(" canWrite=");
        text.append(file.canWrite());
        text.append("\n");
    }

    private static void appendOnce(HashSet<String> written, File file, String text) {
        try {
            String key = file.getCanonicalPath();
            if (written.contains(key))
                return;
            written.add(key);
        } catch (Throwable ignored) {
            String key = file.getAbsolutePath();
            if (written.contains(key))
                return;
            written.add(key);
        }
        append(file, text);
    }

    private static void append(File file, String text) {
        FileWriter writer = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();

            writer = new FileWriter(file, true);
            writer.write(text);
        } catch (Throwable ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
