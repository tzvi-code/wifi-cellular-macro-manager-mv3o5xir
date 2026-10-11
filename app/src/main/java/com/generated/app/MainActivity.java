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
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TransportInfo;
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
    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_DISCONNECTED_CELLULAR_ON = 1;
    private static final int STATE_CONNECTED_CELLULAR_OFF = 2;
    private static final int STATE_SCANNING = 3;
    private int currentMacroState = STATE_UNKNOWN;
    private boolean isFirstRunEvaluation = true;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private LocationManager locationManager;
    private SharedPreferences prefs;

    // UI elements
    private Switch switchMacroToggle;
    private TextView tvMacroState;
    private TextView tvOverallStatusBanner;
    private LinearLayout locationWarningBanner;
    private LinearLayout layoutPromptBanner;
    private TextView tvPromptText;
    private Button btnPromptAction;
    private Button btnPromptDismiss;

    private TextView tvWifiStatus;
    private TextView tvCellularStatus;
    private Button btnQuickAddCurrent;
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
            refreshLocationState();
            refreshNetworkState();
            if (isMacroEnabled()) {
                evaluateMacroRules(false);
            }
            handler.postDelayed(this, 10000); // 10 second monitoring loop
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
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Prepopulate defaults if first run
        Set<String> saved = prefs.getStringSet(KEY_SAVED_SSIDS, null);
        if (saved == null) {
            Set<String> defaults = new HashSet<String>();
            String current = cleanSsid(getCurrentConnectedSsid());
            if (current != null && !current.isEmpty()) {
                defaults.add(current);
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
        tvSubtitle.setText(getString(R.string.app_subtitle));
        tvSubtitle.setTextSize(13);
        tvSubtitle.setTextColor(0xFF546E7A);
        tvSubtitle.setPadding(0, 4, 0, 16);
        tvSubtitle.setGravity(Gravity.RIGHT);
        root.addView(tvSubtitle);

        // Location Services Warning Banner
        locationWarningBanner = new LinearLayout(this);
        locationWarningBanner.setOrientation(LinearLayout.VERTICAL);
        locationWarningBanner.setBackgroundResource(R.drawable.bg_warning_badge);
        locationWarningBanner.setPadding(20, 16, 20, 16);
        locationWarningBanner.setVisibility(View.GONE);

        TextView tvLocWarning = new TextView(this);
        tvLocWarning.setText(getString(R.string.location_needed_explanation));
        tvLocWarning.setTextSize(12);
        tvLocWarning.setTextColor(0xFFBF360C);
        tvLocWarning.setTypeface(Typeface.DEFAULT_BOLD);
        locationWarningBanner.addView(tvLocWarning);

        Button btnOpenLoc = createSmallButton(getString(R.string.action_open_location_settings));
        btnOpenLoc.setBackgroundResource(R.drawable.badge_bg);
        btnOpenLoc.setTextColor(0xFFD84315);
        btnOpenLoc.setOnClickListener(v -> openLocationSettings());
        LinearLayout.LayoutParams lpBtnLoc = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpBtnLoc.topMargin = 10;
        locationWarningBanner.addView(btnOpenLoc, lpBtnLoc);
        root.addView(locationWarningBanner);
        addVerticalSpacer(root, 10);

        // In-App Non-intrusive Recommendation Banner (NEVER opens system dialog forcefully)
        layoutPromptBanner = new LinearLayout(this);
        layoutPromptBanner.setOrientation(LinearLayout.VERTICAL);
        layoutPromptBanner.setBackgroundResource(R.drawable.bg_suggestion_badge);
        layoutPromptBanner.setPadding(20, 16, 20, 16);
        layoutPromptBanner.setVisibility(View.GONE);

        tvPromptText = new TextView(this);
        tvPromptText.setTextSize(13);
        tvPromptText.setTextColor(0xFF0D47A1);
        tvPromptText.setTypeface(Typeface.DEFAULT_BOLD);
        layoutPromptBanner.addView(tvPromptText);

        LinearLayout promptBtnRow = new LinearLayout(this);
        promptBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        promptBtnRow.setPadding(0, 10, 0, 0);

        btnPromptAction = createSmallButton("פתח הגדרות סלולר");
        btnPromptAction.setOnClickListener(v -> openCellularSettings());
        promptBtnRow.addView(btnPromptAction);

        btnPromptDismiss = createSmallButton("הסתר");
        btnPromptDismiss.setBackgroundResource(R.drawable.badge_bg);
        btnPromptDismiss.setTextColor(0xFF546E7A);
        LinearLayout.LayoutParams lpDismiss = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpDismiss.setMargins(12, 0, 0, 0);
        btnPromptDismiss.setLayoutParams(lpDismiss);
        btnPromptDismiss.setOnClickListener(v -> layoutPromptBanner.setVisibility(View.GONE));
        promptBtnRow.addView(btnPromptDismiss);

        layoutPromptBanner.addView(promptBtnRow);
        root.addView(layoutPromptBanner);
        addVerticalSpacer(root, 10);

        // 1. Live Overall Status Banner
        tvOverallStatusBanner = new TextView(this);
        tvOverallStatusBanner.setTextSize(14);
        tvOverallStatusBanner.setTypeface(Typeface.DEFAULT_BOLD);
        tvOverallStatusBanner.setBackgroundResource(R.drawable.bg_status_badge);
        tvOverallStatusBanner.setPadding(24, 18, 24, 18);
        tvOverallStatusBanner.setTextColor(0xFF1B5E20);
        tvOverallStatusBanner.setGravity(Gravity.CENTER);
        tvOverallStatusBanner.setText("מתחבר ובודק מצב רשתות...");
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
            logEvent(isChecked ? "מאקרו הופעל: מנטר רשתות מוכרות" : "מאקרו הושבת ע״י המשתמש");
            if (isChecked) {
                evaluateMacroRules(false);
            }
        });
        root.addView(macroCard);
        addVerticalSpacer(root, 16);

        // 3. Current Live Hardware State Card
        LinearLayout statusCard = createCardLayout(this);
        TextView tvCurrentHeader = createSectionHeader(getString(R.string.label_current_state));
        statusCard.addView(tvCurrentHeader);

        tvWifiStatus = new TextView(this);
        tvWifiStatus.setTextSize(14);
        tvWifiStatus.setTextColor(0xFF263238);
        tvWifiStatus.setPadding(0, 6, 0, 4);
        statusCard.addView(tvWifiStatus);

        btnQuickAddCurrent = createSmallButton(getString(R.string.action_add_current_wifi));
        btnQuickAddCurrent.setVisibility(View.GONE);
        btnQuickAddCurrent.setOnClickListener(v -> {
            String current = cleanSsid(getCurrentConnectedSsid());
            if (current != null && !current.isEmpty()) {
                addTrustedNetwork(current);
            } else {
                Toast.makeText(this, "לא זוהה שם רשת פעילה, נסה להפעיל מיקום או להזין ידנית", Toast.LENGTH_SHORT).show();
            }
        });
        statusCard.addView(btnQuickAddCurrent);

        tvCellularStatus = new TextView(this);
        tvCellularStatus.setTextSize(14);
        tvCellularStatus.setTextColor(0xFF263238);
        tvCellularStatus.setPadding(0, 8, 0, 12);
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
            logEvent("הגדרת ניטור אוטומטי: " + (isChecked ? "פעיל" : "מבוטל"));
        });
        rulesCard.addView(cbAutoCellular);

        cbShowPanelPrompt = new CheckBox(this);
        cbShowPanelPrompt.setText(getString(R.string.rule_open_settings_prompt));
        // Default to false so no external panels or intrusive prompts trigger unexpectedly
        cbShowPanelPrompt.setChecked(prefs.getBoolean(KEY_SHOW_SETTINGS_PANEL, false));
        cbShowPanelPrompt.setTextSize(13);
        cbShowPanelPrompt.setTextColor(0xFF546E7A);
        cbShowPanelPrompt.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SHOW_SETTINGS_PANEL, isChecked).apply();
            if (!isChecked && layoutPromptBanner != null) {
                layoutPromptBanner.setVisibility(View.GONE);
            }
        });
        rulesCard.addView(cbShowPanelPrompt);

        TextView tvRulesDesc = new TextView(this);
        tvRulesDesc.setText(R.string.rule_desc);
        tvRulesDesc.setTextSize(12);
        tvRulesDesc.setTextColor(0xFF757575);
        tvRulesDesc.setPadding(0, 6, 0, 14);
        rulesCard.addView(tvRulesDesc);

        // Signal Quality Threshold Slider
        int savedThreshold = prefs.getInt(KEY_RSSI_THRESHOLD, 35);
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
                if (progress < 15) progress = 15;
                updateThresholdLabel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int val = Math.max(15, seekBar.getProgress());
                prefs.edit().putInt(KEY_RSSI_THRESHOLD, val).apply();
                logEvent("סף איכות קליטה הוגדר ל-" + val + "%");
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
            logEvent("בדיקת מאקרו יזומה...");
            evaluateMacroRules(true);
        });
        rulesCard.addView(btnRunMacroNow);

        root.addView(rulesCard);
        addVerticalSpacer(root, 16);

        // 5. Trusted Networks Card
        LinearLayout networksCard = createCardLayout(this);
        TextView tvNetHeader = createSectionHeader(getString(R.string.title_saved_networks));
        networksCard.addView(tvNetHeader);

        TextView tvNetExpl = new TextView(this);
        tvNetExpl.setText("הוסף את הרשת הביתית שלך כדי שהמאקרו יזהה אותה ויבצע את המעבר:");
        tvNetExpl.setTextSize(12);
        tvNetExpl.setTextColor(0xFF616161);
        tvNetExpl.setPadding(0, 0, 0, 10);
        networksCard.addView(tvNetExpl);

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
            String ssid = cleanSsid(etSsidInput.getText().toString().trim());
            if (ssid != null && !ssid.isEmpty()) {
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
        refreshLocationState();
        refreshNetworkState();
        logEvent("המאקרו הופעל ומנטר ברקע");
    }

    private void updateThresholdLabel(int value) {
        if (tvThresholdValue != null) {
            tvThresholdValue.setText(String.format(Locale.getDefault(),
                    "סף איכות קליטה לזיהוי: %d%% (מינימום -%d dBm)", value, 110 - value));
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

    private boolean isLocationEnabled() {
        if (locationManager == null) return false;
        boolean gps = false;
        boolean network = false;
        try {
            gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {}
        try {
            network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {}
        return gps || network;
    }

    private void refreshLocationState() {
        boolean locOn = isLocationEnabled();
        if (locationWarningBanner != null) {
            locationWarningBanner.setVisibility(locOn ? View.GONE : View.VISIBLE);
        }
    }

    private String cleanSsid(String ssid) {
        if (ssid == null) return null;
        String s = ssid.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.equalsIgnoreCase("<unknown ssid>") || s.equalsIgnoreCase("0x") || s.isEmpty()) {
            return null;
        }
        return s;
    }

    private String getCurrentConnectedSsid() {
        // Strategy 1: NetworkCapabilities (Android 10+ / Q+)
        if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Network activeNet = connectivityManager.getActiveNetwork();
                if (activeNet != null) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNet);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        TransportInfo transportInfo = caps.getTransportInfo();
                        if (transportInfo instanceof WifiInfo) {
                            String s = cleanSsid(((WifiInfo) transportInfo).getSSID());
                            if (s != null) return s;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Strategy 2: WifiManager ConnectionInfo
        if (wifiManager != null) {
            try {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    String s = cleanSsid(info.getSSID());
                    if (s != null) return s;
                }
            } catch (Exception ignored) {}
        }

        // Strategy 3: NetworkInfo ExtraInfo
        if (connectivityManager != null) {
            try {
                NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnected()) {
                    String extra = cleanSsid(netInfo.getExtraInfo());
                    if (extra != null) return extra;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private boolean isWifiConnected() {
        if (connectivityManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network activeNet = connectivityManager.getActiveNetwork();
                    if (activeNet != null) {
                        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNet);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            return true;
                        }
                    }
                } else {
                    NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                    return netInfo != null && netInfo.isConnected();
                }
            } catch (Exception ignored) {}
        }

        if (wifiManager != null) {
            try {
                WifiInfo info = wifiManager.getConnectionInfo();
                return info != null && info.getNetworkId() != -1 && info.getRssi() > -127;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void refreshNetworkState() {
        String currentSsid = getCurrentConnectedSsid();
        boolean wifiHardwareOn = wifiManager != null && wifiManager.isWifiEnabled();
        boolean isConnected = isWifiConnected();

        if (!wifiHardwareOn) {
            tvWifiStatus.setText(getString(R.string.label_wifi) + " כבוי במכשיר");
            if (btnQuickAddCurrent != null) btnQuickAddCurrent.setVisibility(View.GONE);
        } else if (isConnected) {
            WifiInfo info = (wifiManager != null) ? wifiManager.getConnectionInfo() : null;
            int rssi = (info != null) ? info.getRssi() : -65;
            int qual = calculateSignalPercent(rssi);

            String displayName = (currentSsid != null) ? currentSsid : "רשת פעילה";
            tvWifiStatus.setText(String.format(Locale.getDefault(),
                    "%s מחובר ל-%s | איכות: %d%% (%d dBm)",
                    getString(R.string.label_wifi), displayName, qual, rssi));

            if (btnQuickAddCurrent != null) {
                Set<String> saved = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());
                if (currentSsid != null && !isKnownNetwork(currentSsid, saved)) {
                    btnQuickAddCurrent.setText("➕ הוסף את '" + currentSsid + "' לרשימת הרשתות המוכרות");
                    btnQuickAddCurrent.setVisibility(View.VISIBLE);
                } else {
                    btnQuickAddCurrent.setVisibility(View.GONE);
                }
            }
        } else {
            tvWifiStatus.setText(getString(R.string.label_wifi) + " דולק (מחפש רשת...)");
            if (btnQuickAddCurrent != null) btnQuickAddCurrent.setVisibility(View.GONE);
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
            tvCellularStatus.setText(String.format("%s כבויה / בהמתנה (חיסכון בסוללה)", getString(R.string.label_cellular)));
            tvCellularStatus.setTextColor(0xFF546E7A);
        }
    }

    /**
     * Core automation algorithm: Safe, non-intrusive, never traps user with system dialogs
     */
    private void evaluateMacroRules(boolean userTriggered) {
        if (!isMacroEnabled()) {
            if (userTriggered) {
                Toast.makeText(this, "המאקרו כבוי כרגע, יש להפעילו תחילה", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (wifiManager == null) return;

        int targetThreshold = prefs.getInt(KEY_RSSI_THRESHOLD, 35);
        Set<String> savedSsids = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());
        String currentSsid = cleanSsid(getCurrentConnectedSsid());
        boolean connectedToWifi = isWifiConnected();

        // 1. Are we ALREADY connected to Wi-Fi?
        if (connectedToWifi) {
            WifiInfo info = wifiManager.getConnectionInfo();
            int rssi = (info != null) ? info.getRssi() : -65;
            int quality = calculateSignalPercent(rssi);

            boolean isTrusted = (currentSsid != null && isKnownNetwork(currentSsid, savedSsids));
            if (!isTrusted && currentSsid != null && savedSsids.isEmpty()) {
                addTrustedNetwork(currentSsid);
                savedSsids = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());
                isTrusted = true;
            }

            if (isTrusted || (currentSsid == null && !savedSsids.isEmpty())) {
                String networkName = (currentSsid != null) ? currentSsid : "Wi-Fi מוכר";
                applyStateWifiConnected(networkName, quality, userTriggered);
                refreshNetworkState();
                isFirstRunEvaluation = false;
                return;
            }
        }

        // 2. Scan for saved SSIDs in range
        try {
            wifiManager.startScan();
        } catch (Exception ignored) {}

        List<ScanResult> results = null;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException se) {
            if (userTriggered) {
                logEvent("הערה: נדרשת הרשאת מיקום לסריקת שמות רשתות.");
            }
        }

        ScanResult bestCandidate = null;
        int bestQuality = 0;

        if (results != null && !results.isEmpty()) {
            for (ScanResult sr : results) {
                String cleanSrSsid = cleanSsid(sr.SSID);
                if (cleanSrSsid != null && isKnownNetwork(cleanSrSsid, savedSsids)) {
                    int qual = calculateSignalPercent(sr.level);
                    if (qual >= targetThreshold && qual > bestQuality) {
                        bestCandidate = sr;
                        bestQuality = qual;
                    }
                }
            }
        }

        if (bestCandidate != null) {
            String candidateSsid = cleanSsid(bestCandidate.SSID);
            logEvent(String.format(Locale.getDefault(),
                    "רשת מוכרת זוהתה בטווח: '%s' (איכות %d%%)",
                    candidateSsid, bestQuality));

            attemptConnectToSsid(candidateSsid);
            applyStateWifiConnected(candidateSsid, bestQuality, userTriggered);
        } else {
            // Not connected and no trusted network found in range
            applyStateOutOfRangeCellularOn(userTriggered, savedSsids.isEmpty(), targetThreshold);
        }

        refreshNetworkState();
        isFirstRunEvaluation = false;
    }

    private void applyStateWifiConnected(String ssid, int quality, boolean userTriggered) {
        boolean stateChanged = (currentMacroState != STATE_CONNECTED_CELLULAR_OFF);
        currentMacroState = STATE_CONNECTED_CELLULAR_OFF;

        runOnUiThread(() -> {
            tvOverallStatusBanner.setText("✔ " + getString(R.string.state_wifi_active) + " (" + ssid + " " + quality + "%)");
            tvOverallStatusBanner.setTextColor(0xFF1B5E20);
            tvOverallStatusBanner.setBackgroundResource(R.drawable.bg_status_badge);

            boolean promptRequested = prefs.getBoolean(KEY_SHOW_SETTINGS_PANEL, false);
            if (promptRequested && stateChanged && !isFirstRunEvaluation) {
                tvPromptText.setText("📶 התחברת ל-Wi-Fi (" + ssid + "). ניתן לכבות סלולר לחיסכון בסוללה:");
                layoutPromptBanner.setVisibility(View.VISIBLE);
            }
        });

        if (userTriggered) {
            showDialogResult("מאקרו פועל - Wi-Fi מחובר",
                    "מכשירך מחובר לרשת המוכרת '" + ssid + "' באיכות מעולה (" + quality + "%).\n" +
                    "מומלץ לכבות גלישה סלולרית לחיסכון בסוללה.");
        }
    }

    private void applyStateOutOfRangeCellularOn(boolean userTriggered, boolean noSaved, int threshold) {
        boolean stateChanged = (currentMacroState != STATE_DISCONNECTED_CELLULAR_ON);
        currentMacroState = STATE_DISCONNECTED_CELLULAR_ON;

        runOnUiThread(() -> {
            tvOverallStatusBanner.setText("⚡ " + getString(R.string.state_cellular_active));
            tvOverallStatusBanner.setTextColor(0xFFE65100);
            tvOverallStatusBanner.setBackgroundResource(R.drawable.bg_warning_badge);

            boolean promptRequested = prefs.getBoolean(KEY_SHOW_SETTINGS_PANEL, false);
            if (promptRequested && stateChanged && !isFirstRunEvaluation) {
                tvPromptText.setText("⚡ יצאת מטווח Wi-Fi: ודא שגלישה סלולרית פעילה לרציפות גלישה:");
                layoutPromptBanner.setVisibility(View.VISIBLE);
            }
        });

        if (userTriggered) {
            String reason = noSaved ?
                    "טרם הוגדרו רשתות מוכרות ברשימה. נא להוסיף את הרשת הביתית שלך תחילה." :
                    "לא אותרה רשת מוכרת בטווח מעל סף האיכות (" + threshold + "%).\nמומלץ לוודא שהרשת הסלולרית פעילה לשמירה על אינטרנט רציף.";
            showDialogResult("מחוץ לטווח Wi-Fi", reason);
        }
    }

    private void attemptConnectToSsid(String targetSsid) {
        if (wifiManager == null || targetSsid == null) return;
        try {
            List<WifiConfiguration> configs = null;
            try {
                configs = wifiManager.getConfiguredNetworks();
            } catch (SecurityException ignored) {}

            if (configs != null) {
                for (WifiConfiguration config : configs) {
                    String clean = cleanSsid(config.SSID);
                    if (clean != null && clean.equalsIgnoreCase(targetSsid)) {
                        wifiManager.disconnect();
                        wifiManager.enableNetwork(config.networkId, true);
                        wifiManager.reconnect();
                        logEvent("שליחת פקודת התחברות ל-" + targetSsid);
                        return;
                    }
                }
            }
            wifiManager.reconnect();
        } catch (Exception e) {
            logEvent("ניסיון חיבור: " + e.getMessage());
        }
    }

    private void showDeviceNetworksDialog() {
        final List<String> availableSSIDs = new ArrayList<String>();
        Set<String> alreadySaved = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());

        // 1. Current connected network
        String current = cleanSsid(getCurrentConnectedSsid());
        if (current != null && !current.isEmpty()) {
            availableSSIDs.add(current);
        }

        // 2. Current scan results
        try {
            if (wifiManager != null) {
                wifiManager.startScan();
                List<ScanResult> scan = wifiManager.getScanResults();
                if (scan != null) {
                    for (ScanResult sr : scan) {
                        String clean = cleanSsid(sr.SSID);
                        if (clean != null && !clean.isEmpty() && !availableSSIDs.contains(clean)) {
                            availableSSIDs.add(clean);
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {}

        // 3. Device configured networks
        try {
            if (wifiManager != null) {
                List<WifiConfiguration> configured = wifiManager.getConfiguredNetworks();
                if (configured != null) {
                    for (WifiConfiguration config : configured) {
                        String clean = cleanSsid(config.SSID);
                        if (clean != null && !clean.isEmpty() && !availableSSIDs.contains(clean)) {
                            availableSSIDs.add(clean);
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {}

        Collections.sort(availableSSIDs);

        if (availableSSIDs.isEmpty()) {
            AlertDialog.Builder noNetDialog = new AlertDialog.Builder(this);
            noNetDialog.setTitle("לא אותרו שמות רשתות בסריקה");
            String msg = "מערכת אנדרואיד דורשת הפעלת שירותי מיקום (GPS) והרשאה מתאימה כדי להציג שמות רשתות Wi-Fi.\n\n" +
                    "אם אתה מחובר לרשת כעת, תוכל להקליד את שמה ישירות בתיבת הטקסט או להפעיל מיקום.";
            noNetDialog.setMessage(msg);
            noNetDialog.setPositiveButton("הגדרות מיקום", (d, w) -> openLocationSettings());
            noNetDialog.setNegativeButton("סגור", null);
            noNetDialog.show();
            return;
        }

        final CharSequence[] items = new CharSequence[availableSSIDs.size()];
        final boolean[] checkedItems = new boolean[availableSSIDs.size()];

        for (int i = 0; i < availableSSIDs.size(); i++) {
            String name = availableSSIDs.get(i);
            boolean isSaved = isKnownNetwork(name, alreadySaved);
            boolean isCur = (current != null && current.equalsIgnoreCase(name));
            String suffix = "";
            if (isCur) suffix = " (הרשת המחוברת כרגע)";
            else if (isSaved) suffix = " (שמור במאקרו)";

            items[i] = name + suffix;
            checkedItems[i] = isSaved || isCur;
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
            refreshNetworkState();
            logEvent("רשימת הרשתות המוכרות עודכנה בהצלחה");
            Toast.makeText(MainActivity.this, "הרשתות נשמרו בהצלחה", Toast.LENGTH_SHORT).show();
            evaluateMacroRules(false);
        });

        builder.setNeutralButton("רענן סריקה", (dialog, which) -> {
            if (wifiManager != null) wifiManager.startScan();
            handler.postDelayed(this::showDeviceNetworksDialog, 1000);
        });

        builder.setNegativeButton("ביטול", null);
        builder.show();
    }

    private boolean isKnownNetwork(String ssid, Set<String> savedSsids) {
        String cleanCandidate = cleanSsid(ssid);
        if (cleanCandidate == null || cleanCandidate.isEmpty() || savedSsids == null) return false;
        for (String saved : savedSsids) {
            String cleanSaved = cleanSsid(saved);
            if (cleanSaved != null && cleanCandidate.equalsIgnoreCase(cleanSaved)) {
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
        String clean = cleanSsid(ssid);
        if (clean == null || clean.isEmpty()) return;
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        saved.add(clean);
        prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved).apply();
        refreshKnownNetworksList();
        refreshNetworkState();
        logEvent("רשת הוגדרה כרשת מוכרת: " + clean);
        Toast.makeText(this, "רשת " + clean + " נוספה בהצלחה", Toast.LENGTH_SHORT).show();
        evaluateMacroRules(false);
    }

    private void removeTrustedNetwork(String ssid) {
        String clean = cleanSsid(ssid);
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        if (clean != null) saved.remove(clean);
        saved.remove(ssid);
        prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved).apply();
        refreshKnownNetworksList();
        refreshNetworkState();
        logEvent("רשת הוסרה: " + ssid);
        evaluateMacroRules(false);
    }

    private void refreshKnownNetworksList() {
        if (layoutNetworkList == null) return;
        layoutNetworkList.removeAllViews();

        Set<String> saved = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());
        if (saved == null || saved.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("אין רשתות מוגדרות. הקש 'ייבא מרשתות המכשיר' או הוסף ידנית למעלה.");
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

    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "לא ניתן לפתוח הגדרות מיקום", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens Cellular / Wireless settings safely when explicitly requested by user click
     */
    private void openCellularSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent wireless = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                startActivity(wireless);
            } catch (Exception ex) {
                try {
                    Intent settings = new Intent(Settings.ACTION_SETTINGS);
                    startActivity(settings);
                } catch (Exception exc) {
                    Toast.makeText(this, "לא ניתן לפתוח הגדרות סלולר", Toast.LENGTH_SHORT).show();
                }
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
                refreshLocationState();
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
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
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
                logEvent("הרשאות סריקת רשתות אושרו בהצלחה");
                refreshNetworkState();
                evaluateMacroRules(false);
            } else {
                logEvent("אזהרה: ללא הרשאת מיקום, זיהוי שמות רשתות נחסם ע״י אנדרואיד.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLocationState();
        refreshNetworkState();
        handler.removeCallbacks(statusUpdater);
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
