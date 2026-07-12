package com.example.smstotelegram;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SMS_PERMISSION_REQUEST = 41;

    private EditText serverInput;
    private EditText tokenInput;
    private EditText simOneInput;
    private EditText simTwoInput;
    private EditText pinInput;
    private TextView statusText;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        serverInput.setText(SettingsStore.serverUrl(this));
        tokenInput.setText(SettingsStore.botToken(this));
        simOneInput.setText(SettingsStore.simOneWhitelist(this));
        simTwoInput.setText(SettingsStore.simTwoWhitelist(this));
        pinInput.setText(SettingsStore.certPin(this));
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_REQUEST) {
            refreshStatus();
        }
    }

    private ScrollView createContent() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        serverInput = field(R.string.server_url, true, 1);
        root.addView(serverInput, matchWrap());

        tokenInput = field(R.string.bot_token, true, 1);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenInput, matchWrap());

        simOneInput = field(R.string.sim_one_whitelist, false, 3);
        root.addView(simOneInput, matchWrap());

        simTwoInput = field(R.string.sim_two_whitelist, false, 3);
        root.addView(simTwoInput, matchWrap());

        pinInput = field(R.string.certificate_pin, true, 1);
        root.addView(pinInput, matchWrap());

        Button saveButton = new Button(this);
        saveButton.setText(R.string.save_and_sync);
        saveButton.setOnClickListener(view -> saveAndSync());
        root.addView(saveButton, matchWrap());

        Button permissionButton = new Button(this);
        permissionButton.setText(R.string.allow_sms);
        permissionButton.setOnClickListener(view -> requestSmsPermission());
        root.addView(permissionButton, matchWrap());

        LinearLayout tests = new LinearLayout(this);
        tests.setOrientation(LinearLayout.HORIZONTAL);
        Button testOne = new Button(this);
        testOne.setText(R.string.test_sim_one);
        testOne.setOnClickListener(view -> sendTest(0));
        tests.addView(testOne, weighted());
        Button testTwo = new Button(this);
        testTwo.setText(R.string.test_sim_two);
        testTwo.setOnClickListener(view -> sendTest(1));
        tests.addView(testTwo, weighted());
        root.addView(tests, matchWrap());

        Button retryButton = new Button(this);
        retryButton.setText(R.string.retry_pending);
        retryButton.setOnClickListener(view -> {
            DeliveryScheduler.schedule(this, "manual");
            refreshStatus();
        });
        root.addView(retryButton, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(16), 0, 0);
        root.addView(statusText, matchWrap());

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private EditText field(int hint, boolean singleLine, int lines) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(singleLine);
        if (!singleLine) {
            input.setMinLines(lines);
            input.setGravity(Gravity.TOP);
        }
        return input;
    }

    private void saveInputs() {
        SettingsStore.save(this,
                serverInput.getText().toString(),
                tokenInput.getText().toString(),
                simOneInput.getText().toString(),
                simTwoInput.getText().toString(),
                pinInput.getText().toString());
    }

    private void saveAndSync() {
        saveInputs();
        setStatus(getString(R.string.syncing));
        executor.execute(() -> {
            String result;
            try {
                result = RelaySender.configure(this)
                        ? getString(R.string.configuration_saved)
                        : getString(R.string.configuration_rejected);
            } catch (IOException e) {
                result = getString(R.string.network_error, e.getClass().getSimpleName());
            }
            String finalResult = result;
            mainHandler.post(() -> setStatus(finalResult + "\n\n" + statusSummary()));
        });
    }

    private void requestSmsPermission() {
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, SMS_PERMISSION_REQUEST);
        } else {
            refreshStatus();
        }
    }

    private void sendTest(int simSlot) {
        saveInputs();
        setStatus(getString(R.string.sending_test));
        executor.execute(() -> {
            String result;
            try {
                if (!RelaySender.configure(this)) {
                    result = getString(R.string.configuration_rejected);
                } else {
                    result = RelaySender.sendTest(this, simSlot)
                            ? getString(R.string.test_sent)
                            : getString(R.string.test_rejected);
                }
            } catch (IOException e) {
                result = getString(R.string.network_error, e.getClass().getSimpleName());
            }
            String finalResult = result;
            mainHandler.post(() -> setStatus(finalResult + "\n\n" + statusSummary()));
        });
    }

    private void refreshStatus() {
        setStatus(statusSummary());
    }

    private String statusSummary() {
        boolean smsAllowed = checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
        return getString(R.string.status,
                SettingsStore.isConfigured(this) ? getString(R.string.yes) : getString(R.string.no),
                smsAllowed ? getString(R.string.granted) : getString(R.string.missing),
                SmsQueue.count(this));
    }

    private void setStatus(String value) {
        statusText.setText(value);
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(7), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
