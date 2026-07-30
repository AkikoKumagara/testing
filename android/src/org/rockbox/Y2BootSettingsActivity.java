package org.rockbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class Y2BootSettingsActivity extends Activity {
    private static final int ROCKBOX_CHOICE_ID = 1001;
    private static final int STOCK_CHOICE_ID = 1002;

    private RadioGroup normalBootChoices;
    private String initialNormalBoot;
    private Button saveButton;
    private boolean routeShieldReadySent;
    private boolean saveInProgress;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Y2Marker.installUncaughtExceptionHandler();
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Y2BootState.ensureV26Defaults(this);
        Y2BootState.blockRockboxLaunch(this, Y2BootState.BOOT_SETTINGS,
                "boot-settings-open");
        Y2BootState.setBootSettingsActive(this, true);
        Y2Marker.write(this, "BootSettings:onCreate " + Y2BootState.describe(this));

        setContentView(createContentView());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || routeShieldReadySent)
            return;
        routeShieldReadySent = true;
        try {
            android.content.Intent ready = new android.content.Intent(
                    this, Y2RouteShieldService.class);
            ready.setAction(Y2RouteShieldService.ACTION_SETTINGS_READY);
            startService(ready);
            Y2Marker.write(this,
                    "BootSettings:first focused window signalled to route shield");
        } catch (Throwable t) {
            Y2Marker.write(this,
                    "BootSettings:route shield readiness signal failed", t);
        }
    }

    private View createContentView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 18);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Boot Settings");
        title.setTextSize(22);
        root.addView(title);

        TextView prompt = new TextView(this);
        prompt.setText("Normal boot");
        prompt.setTextSize(16);
        prompt.setPadding(0, 18, 0, 6);
        root.addView(prompt);

        initialNormalBoot = normalizeNormalBoot(
                Y2BootState.getNoButtonAction(this));

        normalBootChoices = new RadioGroup(this);
        normalBootChoices.setOrientation(RadioGroup.VERTICAL);
        root.addView(normalBootChoices);

        RadioButton rockbox = new RadioButton(this);
        rockbox.setId(ROCKBOX_CHOICE_ID);
        rockbox.setText("Rockbox");
        rockbox.setTextSize(18);
        normalBootChoices.addView(rockbox);

        RadioButton stock = new RadioButton(this);
        stock.setId(STOCK_CHOICE_ID);
        stock.setText("Y2 Stock");
        stock.setTextSize(18);
        normalBootChoices.addView(stock);

        normalBootChoices.check(Y2BootState.BOOT_STOCK.equals(initialNormalBoot)
                ? STOCK_CHOICE_ID : ROCKBOX_CHOICE_ID);

        saveButton = new Button(this);
        saveButton.setText("Save & Restart");
        saveButton.setPadding(0, 12, 0, 0);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveAndExit();
            }
        });
        root.addView(saveButton);
        return scroll;
    }

    private void saveAndExit() {
        if (saveInProgress)
            return;
        final String normalBoot = selectedNormalBoot();
        final String volumeUp = oppositePlayer(normalBoot);
        final String volumeDown = Y2BootState.BOOT_SETTINGS;

        saveInProgress = true;
        saveButton.setText("Saving...");
        saveButton.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                final boolean persisted =
                        Y2SystemControl.persistNormalBootTarget(
                                Y2BootSettingsActivity.this, normalBoot);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!persisted) {
                            saveInProgress = false;
                            saveButton.setText("Save & Restart");
                            saveButton.setEnabled(true);
                            Toast.makeText(Y2BootSettingsActivity.this,
                                    "Could not save. Try again.",
                                    Toast.LENGTH_LONG).show();
                            Y2Marker.write(Y2BootSettingsActivity.this,
                                    "BootSettings:save not acknowledged; "
                                    + "previous choice retained");
                            return;
                        }
                        Y2BootState.setVolumeBootActions(
                                Y2BootSettingsActivity.this,
                                normalBoot, volumeUp, volumeDown);
                        Y2BootState.blockRockboxLaunch(
                                Y2BootSettingsActivity.this,
                                Y2BootState.BOOT_SETTINGS,
                                "boot-settings-save");
                        Y2Marker.write(Y2BootSettingsActivity.this,
                                "BootSettings:saved normalBoot=" + normalBoot
                                + " volumeUp=" + volumeUp
                                + " volumeDown=" + volumeDown
                                + " " + Y2BootState.describe(
                                        Y2BootSettingsActivity.this));
                        saveButton.setText("Saved - restarting...");
                        Y2Marker.write(Y2BootSettingsActivity.this,
                                "BootSettings:root verified save; "
                                + "waiting for root reboot");
                    }
                });
            }
        }, "y2-save-normal-boot-target").start();
    }

    private String selectedNormalBoot() {
        return normalBootChoices.getCheckedRadioButtonId() == STOCK_CHOICE_ID
                ? Y2BootState.BOOT_STOCK : Y2BootState.BOOT_ROCKBOX;
    }

    private static String normalizeNormalBoot(String value) {
        return Y2BootState.BOOT_STOCK.equals(value)
                ? Y2BootState.BOOT_STOCK : Y2BootState.BOOT_ROCKBOX;
    }

    private static String oppositePlayer(String normalBoot) {
        return Y2BootState.BOOT_ROCKBOX.equals(normalBoot)
                ? Y2BootState.BOOT_STOCK : Y2BootState.BOOT_ROCKBOX;
    }

    private boolean hasUnsavedChanges() {
        return !initialNormalBoot.equals(selectedNormalBoot());
    }

    @Override
    public void onBackPressed() {
        if (saveInProgress) {
            Toast.makeText(this, "Saving boot choice...",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasUnsavedChanges()) {
            Y2Marker.write(this, "BootSettings:back no-unsaved "
                    + Y2BootState.describe(this));
            finish();
            return;
        }
        Y2Marker.write(this, "BootSettings:back unsaved-prompt "
                + Y2BootState.describe(this));
        new AlertDialog.Builder(this)
                .setTitle("Unsaved boot choice")
                .setMessage("Save this normal boot choice and restart?")
                .setPositiveButton("Save & Restart",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        saveAndExit();
                    }
                })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        Y2BootState.setBootSettingsActive(this, false);
        Y2Marker.write(this, "BootSettings:finish " + Y2BootState.describe(this));
        super.onDestroy();
    }
}
