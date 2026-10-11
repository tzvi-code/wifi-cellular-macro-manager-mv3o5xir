package com.generated.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "SmartMacroPrefs";
    private static final String KEY_MACRO_ENABLED = "macro_enabled";
    private static final String KEY_AUTO_CELLULAR = "auto_cellular_control";
    private static final String KEY_SHOW_SETTINGS_PANEL = "show_settings_panel";
    private static final String KEY_RSSI_THRESHOLD = "rssi_threshold";
    private static final String KEY_SAVED_SSIDS = "saved_ssids";
    private static final int PERMISSION_REQ_CODE = 101;

    // Macro State
    private static final int STATE_DISCONNECTED_CELLULAR_ON = 1;
    private static final int STATE_CONNECTED_CELLULAR_OFF = 2;
    private static final int STATE_SCANNING = 3;
    private int currentMacroState = STATE_SCANNING;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private SharedPreferences prefs;

    // UI elements
    private Switch switchMacroToggle;
    private TextView tvMacroState;
    private TextView tvOverallStatusBanner;
    private TextView tvWifiStatus;
    private TextView tvCellularStatus;
    private TextView tvThresholdValue;
    private SeekBar seekThreshold;
    private CheckBox cbAutoCellular;
    private CheckBox cbShowPanelPrompt;
    private LinearLayout layoutNetworkList;
    private TextView tvLogConsole;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            refreshNetworkState();
            if (isMacroEnabled()) {
                evaluateMacroRules(false);
            }
            handler.postDelayed(this, 10000); // 10 second continuous evaluation cycle
        }
    };

    private BroadcastReceiver networkReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        // Prepopulate defaults if first run
        Set<String> saved = prefs.getStringSet(KEY_SAVED_SSIDS, null);
        if (saved == null) {
            Set<String> defaults = new HashSet<String>();
            // Try to auto-import current connected wifi if available
            String current = getCurrentConnectedSsid();
            if (current != null && !current.isEmpty()) {
                defaults.add(current);
            } else {
                defaults.add("Home-WiFi");
            }
            prefs.edit().putStringSet(KEY_SAVED_SSIDS, defaults).apply();
        }

        buildUI();
        registerReceivers();
        checkPermissions();
    }

    private void buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setBackgroundColor(0xFFF4F6F9);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(30, 36, 30, 60);

        // Header Title
        TextView tvHeader = new TextView(this);
        tvHeader.setText(getString(R.string.app_name));
        tvHeader.setTextSize(22);
        tvHeader.setTypeface(Typeface.DEFAULT_BOLD);
        tvHeader.setTextColor(0xFF0D47A1);
        tvHeader.setGravity(Gravity.RIGHT);
        root.addView(tvHeader);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("התחברות אוטומטית לרשתות מוכרות בטווח וכיבוי סלולר, או הדלקת סלולר בעת התרחקות");
        tvSubtitle.setTextSize(13);
        tvSubtitle.setTextColor(0xFF546E7A);
        tvSubtitle.setPadding(0, 4, 0, 20);
        tvSubtitle.setGravity(Gravity.RIGHT);
        root.addView(tvSubtitle);

        // 1. Live Overall Status Banner
        tvOverallStatusBanner = new TextView(this);
        tvOverallStatusBanner.setTextSize(14);
        tvOverallStatusBanner.setTypeface(Typeface.DEFAULT_BOLD);
        tvOverallStatusBanner.setBackgroundResource(R.drawable.bg_status_badge);
        tvOverallStatusBanner.setPadding(24, 18, 24, 18);
        tvOverallStatusBanner.setTextColor(0xFF1B5E20);
        tvOverallStatusBanner.setGravity(Gravity.CENTER);
        root.addView(tvOverallStatusBanner);
        addVerticalSpacer(root, 16);

        // 2. Master Toggle Card
        LinearLayout macroCard = createCardLayout(this);
        macroCard.setBackgroundResource(R.drawable.bg_card_active);

        LinearLayout macroHeaderRow = new LinearLayout(this);
        macroHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        macroHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvMacroTitle = new TextView(this);
        tvMacroTitle.setText(R.string.title_macro_status);
        tvMacroTitle.setTextSize(16);
        tvMacroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvMacroTitle.setTextColor(0xFF1B5E20);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        macroHeaderRow.addView(tvMacroTitle, titleParams);

        switchMacroToggle = new Switch(this);
        switchMacroToggle.setChecked(prefs.getBoolean(KEY_MACRO_ENABLED, true));
        macroHeaderRow.addView(switchMacroToggle);
        macroCard.addView(macroHeaderRow);

        tvMacroState = new TextView(this);
        tvMacroState.setTextSize(13);
        tvMacroState.setPadding(0, 8, 0, 0);
        macroCard.addView(tvMacroState);

        switchMacroToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_MACRO_ENABLED, isChecked).apply();
            updateMacroStatusLabel();
            logEvent(isChecked ? "מאקרו הופעל: מנטר הימצאות רשתות מוכרות בטווח" : "מאקרו כובה ע״י המשתמש");
            if (isChecked) {
                evaluateMacroRules(true);
            }
        });
        root.addView(macroCard);
        addVerticalSpacer(root, 16);

        // 3. Current Live Hardware State Card
        LinearLayout statusCard = createCardLayout(this);
        TextView tvCurrentHeader = createSectionHeader("מצב קישוריות נוכחי במכשיר");
        statusCard.addView(tvCurrentHeader);

        tvWifiStatus = new TextView(this);
        tvWifiStatus.setTextSize(14);
        tvWifiStatus.setTextColor(0xFF263238);
        tvWifiStatus.setPadding(0, 6, 0, 4);
        statusCard.addView(tvWifiStatus);

        tvCellularStatus = new TextView(this);
        tvCellularStatus.setTextSize(14);
        tvCellularStatus.setTextColor(0xFF263238);
        tvCellularStatus.setPadding(0, 4, 0, 12);
        statusCard.addView(tvCellularStatus);

        LinearLayout btnRowSettings = new LinearLayout(this);
        btnRowSettings.setOrientation(LinearLayout.HORIZONTAL);

        Button btnWifiSetting = createSmallButton(getString(R.string.action_open_wifi_settings));
        btnWifiSetting.setOnClickListener(v -> openWifiSettings());
        LinearLayout.LayoutParams btnP1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnP1.setMargins(0, 0, 8, 0);
        btnRowSettings.addView(btnWifiSetting, btnP1);

        Button btnCellSetting = createSmallButton(getString(R.string.action_open_data_settings));
        btnCellSetting.setOnClickListener(v -> openCellularSettings());
        LinearLayout.LayoutParams btnP2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRowSettings.addView(btnCellSetting, btnP2);
        statusCard.addView(btnRowSettings);

        root.addView(statusCard);
        addVerticalSpacer(root, 16);

        // 4. Automation Rules Card
        LinearLayout rulesCard = createCardLayout(this);
        TextView tvRulesHeader = createSectionHeader(getString(R.string.title_rules));
        rulesCard.addView(tvRulesHeader);

        cbAutoCellular = new CheckBox(this);
        cbAutoCellular.setText(getString(R.string.rule_auto_switch));
        cbAutoCellular.setChecked(prefs.getBoolean(KEY_AUTO_CELLULAR, true));
        cbAutoCellular.setTextSize(14);
        cbAutoCellular.setTextColor(0xFF212121);
        cbAutoCellular.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_AUTO_CELLULAR, isChecked).apply();
            logEvent("הגדרת מעבר אוטומטי עודכנה ל: " + (isChecked ? "פעיל" : "מבוטל"));
        });
        rulesCard.addView(cbAutoCellular);

        cbShowPanelPrompt = new CheckBox(this);
        cbShowPanelPrompt.setText(getString(R.string.rule_open_settings_prompt));
        cbShowPanelPrompt.setChecked(prefs.getBoolean(KEY_SHOW_SETTINGS_PANEL, false));
        cbShowPanelPrompt.setTextSize(13);
        cbShowPanelPrompt.setTextColor(0xFF546E7A);
        cbShowPanelPrompt.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SHOW_SETTINGS_PANEL, isChecked).apply();
        });
        rulesCard.addView(cbShowPanelPrompt);

        TextView tvRulesDesc = new TextView(this);
        tvRulesDesc.setText(R.string.rule_desc);
        tvRulesDesc.setTextSize(12);
        tvRulesDesc.setTextColor(0xFF757575);
        tvRulesDesc.setPadding(0, 6, 0, 14);
        rulesCard.addView(tvRulesDesc);

        // Signal Quality Threshold Slider
        int savedThreshold = prefs.getInt(KEY_RSSI_THRESHOLD, 60);
        tvThresholdValue = new TextView(this);
        updateThresholdLabel(savedThreshold);
        tvThresholdValue.setTextSize(13);
        tvThresholdValue.setTextColor(0xFF1565C0);
        tvThresholdValue.setTypeface(Typeface.DEFAULT_BOLD);
        rulesCard.addView(tvThresholdValue);

        seekThreshold = new SeekBar(this);
        seekThreshold.setMax(100);
        seekThreshold.setProgress(savedThreshold);
        seekThreshold.setPadding(10, 10, 10, 16);
        seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 25) progress = 25;
                updateThresholdLabel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int val = Math.max(25, seekBar.getProgress());
                prefs.edit().putInt(KEY_RSSI_THRESHOLD, val).apply();
                logEvent("סף איכות קליטה עודכן ל-" + val + "%");
            }
        });
        rulesCard.addView(seekThreshold);

        Button btnRunMacroNow = new Button(this);
        btnRunMacroNow.setText(getString(R.string.action_evaluate));
        btnRunMacroNow.setBackgroundResource(R.drawable.btn_primary);
        btnRunMacroNow.setTextColor(0xFFFFFFFF);
        btnRunMacroNow.setTextSize(15);
        btnRunMacroNow.setPadding(20, 14, 20, 14);
        btnRunMacroNow.setOnClickListener(v -> {
            logEvent("הפעלת בדיקת מאקרו יזומה...");
            evaluateMacroRules(true);
        });
        rulesCard.addView(btnRunMacroNow);

        root.addView(rulesCard);
        addVerticalSpacer(root, 16);

        // 5. Trusted Networks & Import from Device Card
        LinearLayout networksCard = createCardLayout(this);
        TextView tvNetHeader = createSectionHeader(getString(R.string.title_saved_networks));
        networksCard.addView(tvNetHeader);

        TextView tvNetExpl = new TextView(this);
        tvNetExpl.setText("ניתן להוסיף רשתות ידנית או לייבא ישירות מרשתות המכשיר ומהסריקה:");
        tvNetExpl.setTextSize(12);
        tvNetExpl.setTextColor(0xFF616161);
        tvNetExpl.setPadding(0, 0, 0, 10);
        networksCard.addView(tvNetExpl);

        // Button to IMPORT from device configured or scanned networks
        Button btnImportNetworks = new Button(this);
        btnImportNetworks.setText("📲 " + getString(R.string.import_device_networks));
        btnImportNetworks.setBackgroundResource(R.drawable.btn_secondary);
        btnImportNetworks.setTextColor(0xFF0D47A1);
        btnImportNetworks.setTextSize(14);
        btnImportNetworks.setPadding(16, 12, 16, 12);
        btnImportNetworks.setOnClickListener(v -> showDeviceNetworksDialog());
        networksCard.addView(btnImportNetworks);

        addVerticalSpacer(networksCard, 10);

        layoutNetworkList = new LinearLayout(this);
        layoutNetworkList.setOrientation(LinearLayout.VERTICAL);
        networksCard.addView(layoutNetworkList);

        // Manual network input row
        LinearLayout addNetRow = new LinearLayout(this);
        addNetRow.setOrientation(LinearLayout.HORIZONTAL);
        addNetRow.setPadding(0, 10, 0, 0);

        final EditText etSsidInput = new EditText(this);
        etSsidInput.setHint(R.string.add_network_hint);
        etSsidInput.setInputType(InputType.TYPE_CLASS_TEXT);
        etSsidInput.setTextSize(14);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addNetRow.addView(etSsidInput, etParams);

        Button btnAdd = createSmallButton(getString(R.string.add_button));
        btnAdd.setOnClickListener(v -> {
            String ssid = etSsidInput.getText().toString().trim();
            if (!ssid.isEmpty()) {
                addTrustedNetwork(ssid);
                etSsidInput.setText("");
            } else {
                Toast.makeText(this, "נא להזין שם רשת SSID", Toast.LENGTH_SHORT).show();
            }
        });
        addNetRow.addView(btnAdd);
        networksCard.addView(addNetRow);

        root.addView(networksCard);
        addVerticalSpacer(root, 16);

        // 6. Activity Execution Log Card
        LinearLayout logCard = createCardLayout(this);
        LinearLayout logHeaderRow = new LinearLayout(this);
        logHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        logHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLogHeader = createSectionHeader(getString(R.string.title_activity_log));
        logHeaderRow.addView(tvLogHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClearLog = createSmallButton(getString(R.string.clear_log));
        btnClearLog.setOnClickListener(v -> {
            tvLogConsole.setText("");
            logEvent("היומן נוקה");
        });
        logHeaderRow.addView(btnClearLog);
        logCard.addView(logHeaderRow);

        tvLogConsole = new TextView(this);
        tvLogConsole.setTextSize(12);
        tvLogConsole.setTextColor(0xFF37474F);
        tvLogConsole.setBackgroundColor(0xFFF1F5F9);
        tvLogConsole.setPadding(16, 16, 16, 16);
        tvLogConsole.setTextIsSelectable(true);
        tvLogConsole.setMinLines(5);
        logCard.addView(tvLogConsole);

        root.addView(logCard);

        scrollView.addView(root);
        setContentView(scrollView);

        updateMacroStatusLabel();
        refreshKnownNetworksList();
        logEvent("המאקרו הופעל ומנטר אוטומטית");
    }

    private void updateThresholdLabel(int value) {
        if (tvThresholdValue != null) {
            tvThresholdValue.setText(String.format(Locale.getDefault(),
                    "סף איכות קליטה להתחברות: %d%% (מינימום -%d dBm)", value, 110 - value));
        }
    }

    private void updateMacroStatusLabel() {
        boolean enabled = isMacroEnabled();
        if (enabled) {
            tvMacroState.setText("● " + getString(R.string.macro_active));
            tvMacroState.setTextColor(0xFF2E7D32);
        } else {
            tvMacroState.setText("○ " + getString(R.string.macro_inactive));
            tvMacroState.setTextColor(0xFFC62828);
        }
    }

    private boolean isMacroEnabled() {
        return prefs.getBoolean(KEY_MACRO_ENABLED, true);
    }

    private String getCurrentConnectedSsid() {
        if (wifiManager == null) return null;
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info != null && info.getNetworkId() != -1) {
            String ssid = info.getSSID();
            if (ssid != null) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (!ssid.equals("<unknown ssid>") && !ssid.isEmpty()) {
                    return ssid;
                }
            }
        }
        return null;
    }

    private void refreshNetworkState() {
        // Wi-Fi Status
        String currentSsid = getCurrentConnectedSsid();
        boolean wifiEnabled = wifiManager != null && wifiManager.isWifiEnabled();

        if (!wifiEnabled) {
            tvWifiStatus.setText(getString(R.string.label_wifi) + " כבוי במכשיר");
        } else if (currentSsid != null) {
            WifiInfo info = wifiManager.getConnectionInfo();
            int rssi = (info != null) ? info.getRssi() : -100;
            int qual = calculateSignalPercent(rssi);
            tvWifiStatus.setText(String.format(Locale.getDefault(),
                    "%s מחובר ל-%s | איכות: %d%% (%d dBm)",
                    getString(R.string.label_wifi), currentSsid, qual, rssi));
        } else {
            tvWifiStatus.setText(getString(R.string.label_wifi) + " דולק (לא מחובר לרשת)");
        }

        // Cellular Status
        NetworkInfo mobileInfo = (connectivityManager != null) ?
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) : null;
        boolean cellConnected = (mobileInfo != null && mobileInfo.isConnected());
        String carrier = (telephonyManager != null) ? telephonyManager.getNetworkOperatorName() : "";
        if (carrier == null || carrier.isEmpty()) carrier = "ספק סלולרי";

        if (cellConnected) {
            tvCellularStatus.setText(String.format("%s פעילה ומחוברת (%s)", getString(R.string.label_cellular), carrier));
            tvCellularStatus.setTextColor(0xFF0D47A1);
        } else {
            tvCellularStatus.setText(String.format("%s כבויה / במצב השהייה (לא בשימוש)", getString(R.string.label_cellular)));
            tvCellularStatus.setTextColor(0xFF546E7A);
        }
    }

    /**
     * Core automation algorithm:
     * 1. Check if any known network is in range above threshold.
     * 2. If YES:
     *    - Connect to that Wi-Fi network.
     *    - Turn OFF / suppress cellular data.
     *    - Update banner to: מחובר לרשת מוכרת (סלולר כבוי).
     * 3. If NO (out of range / signal dropped / moved away):
     *    - Turn ON cellular data to guarantee internet continuity.
     *    - Update banner to: מחוץ לטווח Wi-Fi (סלולר מופעל).
     */
    private void evaluateMacroRules(boolean userTriggered) {
        if (!isMacroEnabled()) {
            if (userTriggered) {
                Toast.makeText(this, "המאקרו כבוי כרגע, יש להפעילו תחילה", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (wifiManager == null) return;

        // Ensure Wi-Fi is enabled so we can scan / maintain connection
        if (!wifiManager.isWifiEnabled()) {
            try {
                wifiManager.setWifiEnabled(true);
                logEvent("הדלקת Wi-Fi על ידי המאקרו לסריקת רשתות מוכרות...");
            } catch (Exception ignored) {}
        }

        int targetThreshold = prefs.getInt(KEY_RSSI_THRESHOLD, 60);
        Set<String> savedSsids = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());
        String currentSsid = getCurrentConnectedSsid();

        // 1. Inspect current active connection
        if (currentSsid != null && isKnownNetwork(currentSsid, savedSsids)) {
            WifiInfo info = wifiManager.getConnectionInfo();
            int rssi = (info != null) ? info.getRssi() : -100;
            int quality = calculateSignalPercent(rssi);

            if (quality >= targetThreshold) {
                // Connected and signal is solid!
                applyStateWifiConnected(currentSsid, quality);
                if (userTriggered) {
                    showDialogResult("מאקרו פועל בהצלחה",
                            "מחובר לרשת המוכרת '" + currentSsid + "' בעוצמה גבוהה (" + quality + "%).\n" +
                            "הרשת הסלולרית כבויה לחיסכון בסוללה ובחבילה.");
                }
                refreshNetworkState();
                return;
            } else {
                logEvent(String.format(Locale.getDefault(),
                        "התרחקות זוהתה: אות רשת '%s' נחלש (%d%% < סף %d%%). מתכונן להפעלת סלולר.",
                        currentSsid, quality, targetThreshold));
            }
        }

        // 2. Scan to see if a known network is in range
        try {
            wifiManager.startScan();
        } catch (Exception ignored) {}

        List<ScanResult> results = null;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException se) {
            logEvent("נדרשת הרשאת מיקום לסריקת רשתות");
            checkPermissions();
            return;
        }

        ScanResult bestCandidate = null;
        int bestQuality = 0;

        if (results != null) {
            for (ScanResult sr : results) {
                if (sr.SSID != null && isKnownNetwork(sr.SSID, savedSsids)) {
                    int qual = calculateSignalPercent(sr.level);
                    if (qual >= targetThreshold && qual > bestQuality) {
                        bestCandidate = sr;
                        bestQuality = qual;
                    }
                }
            }
        }

        if (bestCandidate != null) {
            // Found a known network in range!
            logEvent(String.format(Locale.getDefault(),
                    "נמצאה רשת מוכרת בטווח: '%s' (איכות %d%% >= %d%%). מבצע חיבור וכיבוי סלולר.",
                    bestCandidate.SSID, bestQuality, targetThreshold));

            attemptConnectToSsid(bestCandidate.SSID);
            applyStateWifiConnected(bestCandidate.SSID, bestQuality);

            if (userTriggered) {
                showDialogResult("נמצאה רשת מוכרת בטווח",
                        "הרשת המוכרת '" + bestCandidate.SSID + "' זוהתה בקליטה מצוינת (" + bestQuality + "%).\n" +
                        "מתחבר אליה ומכבה רשת סלולרית.");
            }
        } else {
            // NO known network in range OR moved away!
            logEvent(String.format(Locale.getDefault(),
                    "אין רשת מוכרת בטווח (או מתרחק מה-Wi-Fi). מפעיל רשת סלולרית!", targetThreshold));

            applyStateOutOfRangeCellularOn();

            if (userTriggered) {
                showDialogResult("מחוץ לטווח Wi-Fi",
                        "לא אותרה רשת מוכרת מעל סף האיכות (" + targetThreshold + "%).\n" +
                        "הרשת הסלולרית הודלקה לפעולה לשמירה על רציפות הגלישה.");
            }
        }

        refreshNetworkState();
    }

    private void applyStateWifiConnected(String ssid, int quality) {
        currentMacroState = STATE_CONNECTED_CELLULAR_OFF;
        runOnUiThread(() -> {
            tvOverallStatusBanner.setText("✔ " + getString(R.string.state_wifi_active) + " (" + ssid + " " + quality + "%)");
            tvOverallStatusBanner.setTextColor(0xFF1B5E20);
            tvOverallStatusBanner.setBackgroundResource(R.drawable.bg_status_badge);
        });

        boolean autoCellular = prefs.getBoolean(KEY_AUTO_CELLULAR, true);
        if (autoCellular) {
            logEvent("פעולת מאקרו: עדיפות ל-Wi-Fi -> כיבוי/חסימת צריכת סלולר");
            promptCellularToggleIfNeeded(false);
        }
    }

    private void applyStateOutOfRangeCellularOn() {
        currentMacroState = STATE_DISCONNECTED_CELLULAR_ON;
        runOnUiThread(() -> {
            tvOverallStatusBanner.setText("⚡ " + getString(R.string.state_cellular_active));
            tvOverallStatusBanner.setTextColor(0xFFE65100);
            tvOverallStatusBanner.setBackgroundColor(0xFFFFF3E0);
        });

        boolean autoCellular = prefs.getBoolean(KEY_AUTO_CELLULAR, true);
        if (autoCellular) {
            logEvent("פעולת מאקרו: התרחקות מ-Wi-Fi -> הדלקת רשת סלולרית לשמירה על אינטרנט רציף!");
            promptCellularToggleIfNeeded(true);
        }
    }

    private void promptCellularToggleIfNeeded(boolean turnOn) {
        boolean showPrompt = prefs.getBoolean(KEY_SHOW_SETTINGS_PANEL, false);
        if (showPrompt) {
            openCellularSettings();
        }
    }

    /**
     * Tries to associate / prioritize the known network
     */
    private void attemptConnectToSsid(String targetSsid) {
        if (wifiManager == null || targetSsid == null) return;
        try {
            List<WifiConfiguration> configs = null;
            try {
                configs = wifiManager.getConfiguredNetworks();
            } catch (SecurityException ignored) {}

            if (configs != null) {
                for (WifiConfiguration config : configs) {
                    if (config.SSID != null) {
                        String clean = config.SSID;
                        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
                            clean = clean.substring(1, clean.length() - 1);
                        }
                        if (clean.equalsIgnoreCase(targetSsid)) {
                            wifiManager.disconnect();
                            wifiManager.enableNetwork(config.networkId, true);
                            wifiManager.reconnect();
                            logEvent("נשלחה פקודת חיבור ישירה למכשיר עבור " + targetSsid);
                            return;
                        }
                    }
                }
            }
            wifiManager.reconnect();
        } catch (Exception e) {
            logEvent("ניסיון חיבור: " + e.getMessage());
        }
    }

    /**
     * Dialog to import configured networks from device or scanned networks nearby
     */
    private void showDeviceNetworksDialog() {
        final List<String> availableSSIDs = new ArrayList<String>();
        Set<String> alreadySaved = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());

        // 1. Current connected network
        String current = getCurrentConnectedSsid();
        if (current != null && !current.isEmpty()) {
            availableSSIDs.add(current);
        }

        // 2. Device configured networks (if permitted by Android API)
        try {
            if (wifiManager != null) {
                List<WifiConfiguration> configured = wifiManager.getConfiguredNetworks();
                if (configured != null) {
                    for (WifiConfiguration config : configured) {
                        if (config.SSID != null) {
                            String name = config.SSID;
                            if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
                                name = name.substring(1, name.length() - 1);
                            }
                            if (!name.isEmpty() && !availableSSIDs.contains(name)) {
                                availableSSIDs.add(name);
                            }
                        }
                    }
                }
            }
        } catch (SecurityException se) {
            logEvent("הערה: קריאת רשתות מוגדרות במכשיר דורשת הרשאה.");
        }

        // 3. Current scan results
        try {
            if (wifiManager != null) {
                List<ScanResult> scan = wifiManager.getScanResults();
                if (scan != null) {
                    for (ScanResult sr : scan) {
                        if (sr.SSID != null && !sr.SSID.isEmpty() && !availableSSIDs.contains(sr.SSID)) {
                            availableSSIDs.add(sr.SSID);
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {}

        Collections.sort(availableSSIDs);

        if (availableSSIDs.isEmpty()) {
            Toast.makeText(this, "לא נמצאו רשתות במכשיר כעת (הפעל Wi-Fi או סרוק שוב)", Toast.LENGTH_LONG).show();
            return;
        }

        final CharSequence[] items = new CharSequence[availableSSIDs.size()];
        final boolean[] checkedItems = new boolean[availableSSIDs.size()];

        for (int i = 0; i < availableSSIDs.size(); i++) {
            String name = availableSSIDs.get(i);
            boolean isSaved = isKnownNetwork(name, alreadySaved);
            items[i] = name + (isSaved ? " (כבר שמור)" : "");
            checkedItems[i] = isSaved;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("בחר רשתות להגדרה כרשתות מוכרות");
        builder.setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> {
            checkedItems[which] = isChecked;
        });

        builder.setPositiveButton("שמור בחירה", (dialog, which) -> {
            Set<String> newSet = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
            for (int i = 0; i < availableSSIDs.size(); i++) {
                String name = availableSSIDs.get(i);
                if (checkedItems[i]) {
                    newSet.add(name);
                } else {
                    newSet.remove(name);
                }
            }
            prefs.edit().putStringSet(KEY_SAVED_SSIDS, newSet).apply();
            refreshKnownNetworksList();
            logEvent("רשימת הרשתות המוכרות עודכנה מתוך רשתות המכשיר");
            Toast.makeText(MainActivity.this, "רשימת הרשתות עודכנה בהצלחה", Toast.LENGTH_SHORT).show();
            evaluateMacroRules(false);
        });

        builder.setNegativeButton("ביטול", null);
        builder.show();
    }

    private boolean isKnownNetwork(String ssid, Set<String> savedSsids) {
        if (ssid == null || ssid.isEmpty() || savedSsids == null) return false;
        for (String saved : savedSsids) {
            if (ssid.equalsIgnoreCase(saved.trim())) {
                return true;
            }
        }
        return false;
    }

    private int calculateSignalPercent(int rssi) {
        if (rssi <= -100) return 0;
        if (rssi >= -50) return 100;
        return 2 * (rssi + 100);
    }

    private void addTrustedNetwork(String ssid) {
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        saved.add(ssid);
        prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved).apply();
        refreshKnownNetworksList();
        logEvent("רשת הוגדרה כרשת מוכרת: " + ssid);
        Toast.makeText(this, "הרשת נוספה בהצלחה", Toast.LENGTH_SHORT).show();
        evaluateMacroRules(false);
    }

    private void removeTrustedNetwork(String ssid) {
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        saved.remove(ssid);
        prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved).apply();
        refreshKnownNetworksList();
        logEvent("רשת הוסרה: " + ssid);
        evaluateMacroRules(false);
    }

    private void refreshKnownNetworksList() {
        if (layoutNetworkList == null) return;
        layoutNetworkList.removeAllViews();

        Set<String> saved = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());
        if (saved == null || saved.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("אין רשתות מוגדרות. לחץ על 'ייבא מרשתות המכשיר' או הוסף ידנית.");
            emptyTv.setTextSize(13);
            emptyTv.setTextColor(0xFF9E9E9E);
            emptyTv.setPadding(0, 8, 0, 8);
            layoutNetworkList.addView(emptyTv);
            return;
        }

        for (final String ssid : saved) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);

            TextView tvName = new TextView(this);
            tvName.setText("📶 " + ssid);
            tvName.setTextSize(14);
            tvName.setTextColor(0xFF212121);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(tvName, lp);

            Button btnDelete = new Button(this);
            btnDelete.setText("מחק");
            btnDelete.setTextSize(12);
            btnDelete.setTextColor(0xFFC62828);
            btnDelete.setBackgroundResource(R.drawable.badge_bg);
            btnDelete.setPadding(16, 6, 16, 6);
            btnDelete.setOnClickListener(v -> removeTrustedNetwork(ssid));
            row.addView(btnDelete);

            layoutNetworkList.addView(row);
        }
    }

    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "לא ניתן לפתוח הגדרות Wi-Fi", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCellularSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Settings Panel if available
                Intent panel = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                startActivity(panel);
                return;
            }
        } catch (Exception ignored) {}

        try {
            startActivity(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ex) {
                Toast.makeText(this, "לא ניתן לפתוח הגדרות סלולר", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void logEvent(String message) {
        String time = dateFormat.format(new Date());
        final String entry = "[" + time + "] " + message + "\n";
        runOnUiThread(() -> {
            if (tvLogConsole != null) {
                tvLogConsole.append(entry);
            }
        });
    }

    private void showDialogResult(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("אישור", null)
                .show();
    }

    private LinearLayout createCardLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bg_card);
        layout.setPadding(26, 22, 26, 22);
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return layout;
    }

    private TextView createSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFF1565C0);
        tv.setPadding(0, 0, 0, 8);
        return tv;
    }

    private Button createSmallButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setTextColor(0xFF1976D2);
        btn.setBackgroundResource(R.drawable.btn_secondary);
        btn.setPadding(20, 10, 20, 10);
        return btn;
    }

    private void addVerticalSpacer(LinearLayout layout, int heightDp) {
        View spacer = new View(this);
        int px = (int) (heightDp * getResources().getDisplayMetrics().density);
        spacer.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px));
        layout.addView(spacer);
    }

    private void registerReceivers() {
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshNetworkState();
                if (isMacroEnabled()) {
                    evaluateMacroRules(false);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkReceiver, filter);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<String>();
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), PERMISSION_REQ_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                logEvent("הרשאות סריקת רשתות אושרו");
                evaluateMacroRules(false);
            } else {
                logEvent("אזהרה: ללא הרשאת מיקום, סריקת רשתות סמוכות תהיה מוגבלת.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNetworkState();
        handler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkReceiver != null) {
            try {
                unregisterReceiver(networkReceiver);
            } catch (Exception ignored) {}
        }
    }
}
