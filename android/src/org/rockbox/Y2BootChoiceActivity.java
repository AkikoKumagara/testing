package org.rockbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;

public class Y2BootChoiceActivity extends Activity {
    private static final long STOCK_TIMEOUT_MS = 15000L;
    private final Handler handler = new Handler();
    private boolean decided = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Y2Marker.installUncaughtExceptionHandler();
        Y2Marker.write(this, "Y2BootChoiceActivity:onCreate");
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                chooseStock("timeout");
            }
        }, STOCK_TIMEOUT_MS);

        new AlertDialog.Builder(this)
                .setTitle("Y2 Boot")
                .setMessage("Choose firmware for this boot.")
                .setPositiveButton("Rockbox", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        launchRockbox("dialog");
                    }
                })
                .setNeutralButton("Boot Settings", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        openBootSettings("dialog");
                    }
                })
                .setNegativeButton("Stock", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        chooseStock("dialog");
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        chooseStock("cancel");
                    }
                })
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Y2Marker.write(this, "Y2BootChoiceActivity:onKeyDown keyCode=" + keyCode);
        if (keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            launchRockbox("keyCode=" + keyCode);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            chooseStock("back");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void launchRockbox(String reason) {
        if (decided)
            return;
        decided = true;
        Y2BootState.chooseDefaultFirmware(this, Y2BootState.BOOT_ROCKBOX);
        Y2BootState.prepareRockboxLaunch(this, "boot-choice-" + reason);
        Y2SystemControl.suppressStockForRockbox(this, "boot-choice-" + reason);
        Y2Marker.write(this, "Y2BootChoiceActivity:launchRockbox reason=" + reason
                + " " + Y2BootState.describe(this));
        if (!Y2BootState.canLaunchRockbox(this, "boot-choice-" + reason)) {
            finish();
            return;
        }
        Intent activity = new Intent(this, RockboxActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(activity);
        Y2SystemControl.requestRockboxForeground(this, "boot-choice-" + reason);
        finish();
    }

    private void chooseStock(String reason) {
        if (decided)
            return;
        decided = true;
        Y2BootState.chooseDefaultFirmware(this, Y2BootState.BOOT_STOCK);
        Y2BootState.blockRockboxLaunch(this, Y2BootState.BOOT_STOCK,
                "boot-choice-" + reason);
        Y2SystemControl.enableStockForStockRoute(this, "boot-choice-" + reason);
        Y2Marker.write(this, "Y2BootChoiceActivity:chooseStock reason=" + reason
                + " " + Y2BootState.describe(this));
        finish();
    }

    private void openBootSettings(String reason) {
        if (decided)
            return;
        decided = true;
        Y2BootState.blockRockboxLaunch(this, Y2BootState.BOOT_SETTINGS,
                "boot-choice-" + reason);
        Y2Marker.write(this, "Y2BootChoiceActivity:openBootSettings reason=" + reason);
        Intent activity = new Intent(this, Y2BootSettingsActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(activity);
        finish();
    }
}
