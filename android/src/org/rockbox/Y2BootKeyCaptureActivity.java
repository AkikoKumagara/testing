package org.rockbox;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Y2BootKeyCaptureActivity extends Activity {
    private static final long NO_BUTTON_TIMEOUT_MS = 6000L;
    private static final long RAW_CAPTURE_MS = 5500L;
    private static final int RAW_LOG_LIMIT = 40;
    private final Handler handler = new Handler();
    private boolean decided = false;
    private long captureStartElapsedMs = 0L;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Y2Marker.installUncaughtExceptionHandler();
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);

        captureStartElapsedMs = SystemClock.elapsedRealtime();
        Y2BootState.ensureV25Defaults(this);
        Y2BootState.setBootKeyCaptureActive(this, true);
        Y2Marker.write(this, "BootKey:captureWindow startElapsedMs="
                + captureStartElapsedMs + " timeoutMs=" + NO_BUTTON_TIMEOUT_MS);
        Y2Marker.write(this, "BootKey:onCreate mode=quiet-android-level-capture timeoutMs="
                + NO_BUTTON_TIMEOUT_MS
                + Y2BootState.describe(this));
        Y2Marker.writeDebugSnapshot(this, "BootKey:onCreate");
        startRawInputCapture("normal-getevent", new String[] {"getevent", "-lt"});
        startRawInputCapture("root-getevent", new String[] {"su", "-c", "getevent -lt"});

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyAction("no-button", "no-button-timeout",
                        Y2BootState.getNoButtonAction(Y2BootKeyCaptureActivity.this));
            }
        }, NO_BUTTON_TIMEOUT_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Y2Marker.write(this, "BootKey:dispatchKeyEvent "
                + describeKeyEvent(event) + " " + Y2BootState.describe(this));
        if (handleBootKeyEvent("dispatch", event))
            return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Y2Marker.write(this, "BootKey:onKeyDown keyCode=" + keyCode
                + " repeat=" + event.getRepeatCount()
                + " eventTime=" + event.getEventTime()
                + " " + Y2BootState.describe(this));
        if (handleBootKeyEvent("onKeyDown", event))
            return true;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            applyAction("back", "back", Y2BootState.BOOT_STOCK);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Y2Marker.write(this, "BootKey:onKeyUp keyCode=" + keyCode
                + " eventTime=" + event.getEventTime()
                + " " + Y2BootState.describe(this));
        if (handleBootKeyEvent("onKeyUp", event))
            return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        Y2BootState.setBootKeyCaptureActive(this, false);
        Y2Marker.write(this, "BootKey:finish reason=destroy " + Y2BootState.describe(this));
        super.onDestroy();
    }

    private void applyAction(String button, String reason, String action) {
        if (decided)
            return;
        decided = true;
        String resolved = Y2BootState.resolveAction(this, action);
        Y2BootState.noteResolvedAction(this, button, action, resolved, reason);
        Y2Marker.write(this, "BootKey:applyAction reason="
                + reason + " button=" + button + " action=" + action
                + " resolvedAction=" + resolved + " " + Y2BootState.describe(this));
        Y2Marker.writeDebugSnapshot(this, "BootKey:applyAction-" + reason);

        if (Y2BootState.BOOT_SETTINGS.equals(resolved)) {
            Y2BootState.blockRockboxLaunch(this, Y2BootState.BOOT_SETTINGS, reason);
            Y2Marker.write(this, "BootAction: source=boot-key button="
                    + button + " action=settings " + Y2BootState.describe(this));
            Intent activity = new Intent(this, Y2BootSettingsActivity.class);
            activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(activity);
            finish();
            return;
        }

        if (Y2BootState.BOOT_STOCK.equals(resolved)) {
            Y2BootState.blockRockboxLaunch(this, Y2BootState.BOOT_STOCK, reason);
            Y2Marker.write(this, "BootKey:stock selected " + Y2BootState.describe(this));
            Y2SystemControl.enableStockForStockRoute(this, "boot-key-" + reason);
            finish();
            return;
        }

        Y2BootState.prepareRockboxLaunch(this, "boot-key-" + reason);
        Y2SystemControl.suppressStockForRockbox(this, "boot-key-" + reason);
        if (!Y2BootState.canLaunchRockbox(this, "boot-key-" + reason)) {
            finish();
            return;
        }
        Intent activity = new Intent(this, RockboxActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(activity);
        Y2SystemControl.requestRockboxForeground(this, "boot-key-" + reason);
        finish();
    }

    private boolean handleBootKeyEvent(String source, KeyEvent event) {
        if (event == null || decided)
            return false;
        String button = buttonForKeyCode(event.getKeyCode());
        if (button == null)
            return false;

        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            applyAction(button, source + "-down", actionForButton(button));
            return true;
        }

        if (action == KeyEvent.ACTION_UP && isEarlyCaptureEvent()) {
            applyAction(button, source + "-early-up-held", actionForButton(button));
            return true;
        }

        return false;
    }

    private boolean isEarlyCaptureEvent() {
        return SystemClock.elapsedRealtime() - captureStartElapsedMs <= NO_BUTTON_TIMEOUT_MS;
    }

    private String actionForButton(String button) {
        if ("menu".equals(button))
            return Y2BootState.getMenuAction(this);
        if ("center".equals(button))
            return Y2BootState.getCenterAction(this);
        if ("left".equals(button))
            return Y2BootState.getLeftAction(this);
        if ("right".equals(button))
            return Y2BootState.getRightAction(this);
        if ("play".equals(button))
            return Y2BootState.getPlayAction(this);
        return Y2BootState.BOOT_DEFAULT;
    }

    private static String buttonForKeyCode(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_MENU)
            return "menu";
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER)
            return "center";
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
            return "left";
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            return "right";
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return "play";
        return null;
    }

    private static String describeKeyEvent(KeyEvent event) {
        if (event == null)
            return "event=null";
        return "action=" + event.getAction()
                + " keyCode=" + event.getKeyCode()
                + " scanCode=" + event.getScanCode()
                + " repeat=" + event.getRepeatCount()
                + " flags=" + event.getFlags()
                + " source=" + event.getSource()
                + " deviceId=" + event.getDeviceId()
                + " downTime=" + event.getDownTime()
                + " eventTime=" + event.getEventTime();
    }

    private void startRawInputCapture(final String label, final String[] command) {
        new Thread(new Runnable() {
            public void run() {
                Process process = null;
                BufferedReader reader = null;
                long deadline = System.currentTimeMillis() + RAW_CAPTURE_MS;
                int logged = 0;
                try {
                    process = Runtime.getRuntime().exec(command);
                    final Process captureProcess = process;
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                Thread.sleep(RAW_CAPTURE_MS);
                                captureProcess.destroy();
                            } catch (Throwable ignored) {
                            }
                        }
                    }, "y2-raw-input-timeout-" + label).start();
                    reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()));
                    Y2Marker.write(Y2BootKeyCaptureActivity.this,
                            "RawInput:start label=" + label
                            + " captureMs=" + RAW_CAPTURE_MS);
                    String line;
                    while (!decided && System.currentTimeMillis() < deadline
                            && (line = reader.readLine()) != null) {
                        String button = buttonForRawLine(line);
                        if (button != null || logged < RAW_LOG_LIMIT) {
                            Y2Marker.write(Y2BootKeyCaptureActivity.this,
                                    "RawInput:line label=" + label
                                    + " text=" + line);
                            logged++;
                        }
                        if (button != null) {
                            applyRawButton(button, label, line);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Y2Marker.write(Y2BootKeyCaptureActivity.this,
                            "RawInput:exception label=" + label, t);
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (process != null) {
                        try {
                            process.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                    Y2Marker.write(Y2BootKeyCaptureActivity.this,
                            "RawInput:finish label=" + label
                            + " decided=" + decided);
                }
            }
        }, "y2-raw-input-" + label).start();
    }

    private void applyRawButton(final String button, final String label,
            final String line) {
        handler.post(new Runnable() {
            public void run() {
                if (decided)
                    return;
                Y2Marker.write(Y2BootKeyCaptureActivity.this,
                        "RawInput:detected button=" + button
                        + " label=" + label + " text=" + line);
                if ("menu".equals(button)) {
                    applyAction("menu", "raw-" + label,
                            Y2BootState.getMenuAction(Y2BootKeyCaptureActivity.this));
                } else if ("center".equals(button)) {
                    applyAction("center", "raw-" + label,
                            Y2BootState.getCenterAction(Y2BootKeyCaptureActivity.this));
                } else if ("left".equals(button)) {
                    applyAction("left", "raw-" + label,
                            Y2BootState.getLeftAction(Y2BootKeyCaptureActivity.this));
                } else if ("right".equals(button)) {
                    applyAction("right", "raw-" + label,
                            Y2BootState.getRightAction(Y2BootKeyCaptureActivity.this));
                } else if ("play".equals(button)) {
                    applyAction("play", "raw-" + label,
                            Y2BootState.getPlayAction(Y2BootKeyCaptureActivity.this));
                }
            }
        });
    }

    private static String buttonForRawLine(String line) {
        if (line == null)
            return null;
        String lower = line.toLowerCase();
        if (lower.indexOf("ev_key") < 0 && lower.indexOf("0001") < 0)
            return null;
        if (lower.indexOf(" up") >= 0 || lower.endsWith(" 00000000")
                || lower.indexOf(" value 0") >= 0)
            return null;
        if (lower.indexOf("key_menu") >= 0 || lower.indexOf(" 008b ") >= 0)
            return "menu";
        if (lower.indexOf("key_enter") >= 0
                || lower.indexOf("key_dpad_center") >= 0
                || lower.indexOf("key_select") >= 0
                || lower.indexOf(" 001c ") >= 0
                || lower.indexOf(" 0160 ") >= 0)
            return "center";
        if (lower.indexOf("key_left") >= 0
                || lower.indexOf("key_dpad_left") >= 0
                || lower.indexOf(" 0069 ") >= 0)
            return "left";
        if (lower.indexOf("key_right") >= 0
                || lower.indexOf("key_dpad_right") >= 0
                || lower.indexOf(" 006a ") >= 0)
            return "right";
        if (lower.indexOf("key_playpause") >= 0
                || lower.indexOf("key_play_pause") >= 0
                || lower.indexOf(" 00a4 ") >= 0)
            return "play";
        return null;
    }
}
