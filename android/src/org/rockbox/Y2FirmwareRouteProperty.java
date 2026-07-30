package org.rockbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Reads the fixed route published by the init file selected by LK. */
public final class Y2FirmwareRouteProperty {
    private static final String PROPERTY = "y2.firmware.route";

    private Y2FirmwareRouteProperty() {
    }

    public static String read() {
        return readProperty(PROPERTY);
    }

    public static boolean isAndroidBootCompleted() {
        return "1".equals(readProperty("sys.boot_completed"));
    }

    private static String readProperty(String property) {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"getprop", property});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            process.waitFor();
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
            if (process != null)
                process.destroy();
        }
    }
}
