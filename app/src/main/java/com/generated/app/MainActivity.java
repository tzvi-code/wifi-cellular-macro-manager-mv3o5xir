package com.generated.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.TransportInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private static final String KEY_DISCONNECT_FAR = "disconnect_far_wifi";
    private static final String KEY_SHOW_SETTINGS_PANEL = "show_settings_panel";
    private static final String KEY_RSSI_THRESHOLD = "rssi_threshold";
    private static final String KEY_SAVED_SSIDS = "saved_ssids";
    private static final String PREF_PASS_PREFIX = "wifi_pass_";
    private static final int PERMISSION_REQ_CODE = 101;

    // Macro State
    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_DISCONNECTED_CELLULAR_ON = 1;
    private static final int STATE_CONNECTED_CELLULAR_OFF = 2;
    private int currentMacroState = STATE_UNKNOWN;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private LocationManager locationManager;
    private SharedPreferences prefs;

    // Active network callback for Android 10+ explicit connections
    private ConnectivityManager.NetworkCallback activeNetworkCallback;
    private String currentlyBoundSsid = null;

    // UI elements
    private Switch switchMacroToggle;
    private TextView tvMacroState;
    private TextView tvOverallStatusBanner;
    private TextView tvLivePingStatus;
    private LinearLayout locationWarningBanner;
    private LinearLayout layoutPromptBanner;
    private TextView tvPromptText;
    private Button btnPromptAction;

    private TextView tvWifiStatus;
    private TextView tvCellularStatus;
    private Button btnQuickAddCurrent;
    private Button btnDisconnectNow;
    private Button btnToggleWifiHardware;
    private Button btnTestInternetPing;

    private TextView tvThresholdValue;
    private SeekBar seekThreshold;
    private CheckBox cbAutoSwitch;
    private CheckBox cbDisconnectFar;
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
            handler.postDelayed(this, 8000);
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
        checkActiveInternetConnection();
    }

    private void buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setBackgroundColor(0xFFF4F6F9);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(28, 32, 28, 50);

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
        tvSubtitle.setPadding(0, 4, 0, 14);
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

        // In-App Notification / Helper Banner
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

        btnPromptAction = createSmallButton("פתח לוח בקרה מהיר");
        btnPromptAction.setOnClickListener(v -> openInternetSettingsPanel());
        promptBtnRow.addView(btnPromptAction);

        Button btnPromptDismiss = createSmallButton("הסתר");
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
        tvOverallStatusBanner.setPadding(22, 16, 22, 16);
        tvOverallStatusBanner.setTextColor(0xFF1B5E20);
        tvOverallStatusBanner.setGravity(Gravity.CENTER);
        tvOverallStatusBanner.setText("בודק סטטוס חיבור וגלישה...");
        root.addView(tvOverallStatusBanner);

        // Live Ping Indicator
        tvLivePingStatus = new TextView(this);
        tvLivePingStatus.setTextSize(12);
        tvLivePingStatus.setTextColor(0xFF37474F);
        tvLivePingStatus.setGravity(Gravity.CENTER);
        tvLivePingStatus.setPadding(0, 6, 0, 12);
        tvLivePingStatus.setText("בדיקת זרימת נתונים חיה: בודק...");
        root.addView(tvLivePingStatus);
        addVerticalSpacer(root, 6);

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
            logEvent(isChecked ? "מאקרו הופעל: יבצע חיבור וניתוק אמיתיים" : "מאקרו הושבת ע״י המשתמש");
            if (isChecked) {
                evaluateMacroRules(false);
            }
        });
        root.addView(macroCard);
        addVerticalSpacer(root, 14);

        // 3. Current Live Hardware State & Direct Action Card
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
                addTrustedNetwork(current, null);
            } else {
                Toast.makeText(this, "לא זוהה שם רשת פעילה, נסה להפעיל מיקום או להזין ידנית", Toast.LENGTH_SHORT).show();
            }
        });
        statusCard.addView(btnQuickAddCurrent);

        tvCellularStatus = new TextView(this);
        tvCellularStatus.setTextSize(14);
        tvCellularStatus.setTextColor(0xFF263238);
        tvCellularStatus.setPadding(0, 6, 0, 10);
        statusCard.addView(tvCellularStatus);

        // REAL DIRECT ACTION BUTTONS (Works actually!)
        LinearLayout directActionsRow = new LinearLayout(this);
        directActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        directActionsRow.setPadding(0, 4, 0, 8);

        btnDisconnectNow = new Button(this);
        btnDisconnectNow.setText("⚡ " + getString(R.string.action_disconnect_now));
        btnDisconnectNow.setBackgroundResource(R.drawable.btn_danger);
        btnDisconnectNow.setTextColor(0xFFFFFFFF);
        btnDisconnectNow.setTextSize(13);
        LinearLayout.LayoutParams lpBtnDis = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpBtnDis.setMargins(6, 0, 0, 0);
        btnDisconnectNow.setLayoutParams(lpBtnDis);
        btnDisconnectNow.setOnClickListener(v -> {
            logEvent("ביצוע ניתוק יזום מ-Wi-Fi...");
            performRealDisconnect(true);
        });
        directActionsRow.addView(btnDisconnectNow);

        btnToggleWifiHardware = new Button(this);
        btnToggleWifiHardware.setText("📶 הפעל/כבה Wi-Fi");
        btnToggleWifiHardware.setBackgroundResource(R.drawable.btn_secondary);
        btnToggleWifiHardware.setTextColor(0xFF0D47A1);
        btnToggleWifiHardware.setTextSize(13);
        LinearLayout.LayoutParams lpBtnTog = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnToggleWifiHardware.setLayoutParams(lpBtnTog);
        btnToggleWifiHardware.setOnClickListener(v -> toggleWifiHardware());
        directActionsRow.addView(btnToggleWifiHardware);

        statusCard.addView(directActionsRow);

        LinearLayout btnRowSettings = new LinearLayout(this);
        btnRowSettings.setOrientation(LinearLayout.HORIZONTAL);

        btnTestInternetPing = createSmallButton("🌐 " + getString(R.string.action_test_ping));
        btnTestInternetPing.setOnClickListener(v -> checkActiveInternetConnection());
        LinearLayout.LayoutParams btnP0 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        btnP0.setMargins(6, 0, 0, 0);
        btnRowSettings.addView(btnTestInternetPing, btnP0);

        Button btnWifiSetting = createSmallButton(getString(R.string.action_open_wifi_settings));
        btnWifiSetting.setOnClickListener(v -> openWifiSettings());
        LinearLayout.LayoutParams btnP1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnP1.setMargins(6, 0, 0, 0);
        btnRowSettings.addView(btnWifiSetting, btnP1);

        Button btnCellSetting = createSmallButton(getString(R.string.action_open_data_settings));
        btnCellSetting.setOnClickListener(v -> openCellularSettings());
        LinearLayout.LayoutParams btnP2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRowSettings.addView(btnCellSetting, btnP2);
        statusCard.addView(btnRowSettings);

        root.addView(statusCard);
        addVerticalSpacer(root, 14);

        // 4. Automation Rules Card
        LinearLayout rulesCard = createCardLayout(this);
        TextView tvRulesHeader = createSectionHeader(getString(R.string.title_rules));
        rulesCard.addView(tvRulesHeader);

        cbAutoSwitch = new CheckBox(this);
        cbAutoSwitch.setText(getString(R.string.rule_auto_switch));
        cbAutoSwitch.setChecked(prefs.getBoolean(KEY_AUTO_CELLULAR, true));
        cbAutoSwitch.setTextSize(14);
        cbAutoSwitch.setTextColor(0xFF212121);
        cbAutoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_AUTO_CELLULAR, isChecked).apply();
            logEvent("הגדרת חיבור אוטומטי אמיתי: " + (isChecked ? "פעיל" : "מבוטל"));
        });
        rulesCard.addView(cbAutoSwitch);

        cbDisconnectFar = new CheckBox(this);
        cbDisconnectFar.setText(getString(R.string.rule_disconnect_when_far));
        cbDisconnectFar.setChecked(prefs.getBoolean(KEY_DISCONNECT_FAR, true));
        cbDisconnectFar.setTextSize(13);
        cbDisconnectFar.setTextColor(0xFF2E7D32);
        cbDisconnectFar.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_DISCONNECT_FAR, isChecked).apply();
            logEvent("הגדרת ניתוק מ-Wi-Fi חלש: " + (isChecked ? "פעיל" : "מבוטל"));
        });
        rulesCard.addView(cbDisconnectFar);

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
        tvRulesDesc.setPadding(0, 6, 0, 12);
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
            logEvent("הפעלת מאקרו יזומה על פי כללים...");
            evaluateMacroRules(true);
        });
        rulesCard.addView(btnRunMacroNow);

        root.addView(rulesCard);
        addVerticalSpacer(root, 14);

        // 5. Trusted Networks Card
        LinearLayout networksCard = createCardLayout(this);
        TextView tvNetHeader = createSectionHeader(getString(R.string.title_saved_networks));
        networksCard.addView(tvNetHeader);

        TextView tvNetExpl = new TextView(this);
        tvNetExpl.setText("הרשתות שלהלן יזוהו אוטומטית ויחובר/ינותק אליהן החיבור בפועל:");
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

        // Manual network input
        LinearLayout addNetBox = new LinearLayout(this);
        addNetBox.setOrientation(LinearLayout.VERTICAL);
        addNetBox.setBackgroundResource(R.drawable.badge_bg);
        addNetBox.setPadding(16, 12, 16, 12);

        final EditText etSsidInput = new EditText(this);
        etSsidInput.setHint(R.string.add_network_hint);
        etSsidInput.setInputType(InputType.TYPE_CLASS_TEXT);
        etSsidInput.setTextSize(13);
        addNetBox.addView(etSsidInput);

        final EditText etPassInput = new EditText(this);
        etPassInput.setHint(R.string.add_password_hint);
        etPassInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassInput.setTextSize(13);
        addNetBox.addView(etPassInput);

        Button btnAdd = new Button(this);
        btnAdd.setText("➕ הוסף רשת למאקרו");
        btnAdd.setBackgroundResource(R.drawable.btn_success);
        btnAdd.setTextColor(0xFFFFFFFF);
        btnAdd.setTextSize(13);
        btnAdd.setOnClickListener(v -> {
            String ssid = cleanSsid(etSsidInput.getText().toString().trim());
            String pass = etPassInput.getText().toString().trim();
            if (ssid != null && !ssid.isEmpty()) {
                addTrustedNetwork(ssid, pass.isEmpty() ? null : pass);
                etSsidInput.setText("");
                etPassInput.setText("");
            } else {
                Toast.makeText(this, "נא להזין שם רשת SSID", Toast.LENGTH_SHORT).show();
            }
        });
        addNetBox.addView(btnAdd);
        networksCard.addView(addNetBox);

        root.addView(networksCard);
        addVerticalSpacer(root, 14);

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
        logEvent("מאקרו אותחל: מוכן לפקודות חיבור וניתוק אמיתיות");
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

        if (wifiManager != null) {
            try {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    String s = cleanSsid(info.getSSID());
                    if (s != null) return s;
                }
            } catch (Exception ignored) {}
        }

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
            if (btnDisconnectNow != null) btnDisconnectNow.setEnabled(false);
        } else if (isConnected) {
            if (btnDisconnectNow != null) btnDisconnectNow.setEnabled(true);
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
            if (btnDisconnectNow != null) btnDisconnectNow.setEnabled(false);
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
     * Real Active Internet Ping - verifies actual traffic throughput
     */
    private void checkActiveInternetConnection() {
        tvLivePingStatus.setText("בודק מעבר נתונים באינטרנט...");
        new Thread(() -> {
            boolean hasInternet = false;
            String type = "ללא חיבור";
            long timeTaken = 0;
            try {
                long start = System.currentTimeMillis();
                URL url = new URL("https://connectivitycheck.gstatic.com/generate_204");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                timeTaken = System.currentTimeMillis() - start;
                hasInternet = (code == 204 || code == 200);
                conn.disconnect();
            } catch (Exception e) {
                hasInternet = false;
            }

            if (isWifiConnected()) {
                type = "Wi-Fi";
            } else {
                NetworkInfo mobile = connectivityManager != null ? connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) : null;
                if (mobile != null && mobile.isConnected()) {
                    type = "סלולר";
                }
            }

            final boolean success = hasInternet;
            final String netType = type;
            final long pingMs = timeTaken;
            runOnUiThread(() -> {
                if (success) {
                    tvLivePingStatus.setText(String.format(Locale.getDefault(),
                            "🌐 גלישה פעילה ותקינה דרך: %s (%dms)", netType, pingMs));
                    tvLivePingStatus.setTextColor(0xFF1B5E20);
                } else {
                    tvLivePingStatus.setText("⚠️ אין גישה לאינטרנט כרגע (" + netType + ")");
                    tvLivePingStatus.setTextColor(0xFFC62828);
                }
            });
        }).start();
    }

    /**
     * Real hardware or settings panel toggle
     */
    private void toggleWifiHardware() {
        if (wifiManager == null) return;
        boolean currentState = wifiManager.isWifiEnabled();
        boolean targetState = !currentState;

        boolean directSuccess = false;
        try {
            directSuccess = wifiManager.setWifiEnabled(targetState);
        } catch (Exception ignored) {}

        if (directSuccess) {
            logEvent("Wi-Fi " + (targetState ? "הופעל" : "כובה") + " בהצלחה ישירות");
            Toast.makeText(this, targetState ? "Wi-Fi הופעל" : "Wi-Fi כובה", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> {
                refreshNetworkState();
                evaluateMacroRules(false);
            }, 1000);
        } else {
            // Android 10+ restricts setWifiEnabled from 3rd party apps -> open Instant Panel
            logEvent("הפעלה ישירה נחסמה ע״י מערכת ההפעלה -> פתיחת לוח בקרה מהיר");
            openInternetSettingsPanel();
        }
    }

    /**
     * ACTUALLY connects to the specified SSID on ALL Android versions:
     * - Uses modern WifiNetworkSpecifier & NetworkCallback on Android 10+
     * - Uses WifiNetworkSuggestion for OS-level auto-switch
     * - Uses WifiConfiguration and enableNetwork on older Android versions
     */
    public void performRealConnect(final String targetSsid, final boolean userInitiated) {
        if (targetSsid == null || targetSsid.isEmpty()) return;
        logEvent("מתחיל פעולת חיבור אמיתית אל: " + targetSsid);
        Toast.makeText(this, "מתחבר ל-" + targetSsid + "...", Toast.LENGTH_SHORT).show();

        // 1. Ensure Wi-Fi is enabled
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            try {
                wifiManager.setWifiEnabled(true);
            } catch (Exception ignored) {}
        }

        final String storedPassword = prefs.getString(PREF_PASS_PREFIX + targetSsid, null);

        // 2. Modern Android 10+ (API 29+) explicit connection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && connectivityManager != null) {
            try {
                // Clear any previous network callback
                if (activeNetworkCallback != null) {
                    try {
                        connectivityManager.unregisterNetworkCallback(activeNetworkCallback);
                    } catch (Exception ignored) {}
                    activeNetworkCallback = null;
                }

                WifiNetworkSpecifier.Builder specifierBuilder = new WifiNetworkSpecifier.Builder()
                        .setSsid(targetSsid);

                if (storedPassword != null && !storedPassword.isEmpty()) {
                    specifierBuilder.setWpa2Passphrase(storedPassword);
                }

                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        .setNetworkSpecifier(specifierBuilder.build())
                        .build();

                activeNetworkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        currentlyBoundSsid = targetSsid;
                        logEvent("✔ חיבור הושלם בהצלחה לרשת: " + targetSsid);
                        runOnUiThread(() -> {
                            refreshNetworkState();
                            checkActiveInternetConnection();
                            Toast.makeText(MainActivity.this, "מחובר ל-" + targetSsid, Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        logEvent("רשת אבדה: " + targetSsid);
                        currentlyBoundSsid = null;
                        runOnUiThread(() -> {
                            refreshNetworkState();
                            evaluateMacroRules(false);
                        });
                    }

                    @Override
                    public void onUnavailable() {
                        super.onUnavailable();
                        logEvent("לא ניתן היה להשלים חיבור אל " + targetSsid);
                    }
                };

                connectivityManager.requestNetwork(request, activeNetworkCallback);
                logEvent("נשלחה בקשת חיבור מערכתית (Specifier) ל-" + targetSsid);

                // Also register suggestion for OS priority
                try {
                    WifiNetworkSuggestion.Builder suggBuilder = new WifiNetworkSuggestion.Builder()
                            .setSsid(targetSsid)
                            .setIsAppInteractionRequired(false);
                    if (storedPassword != null && !storedPassword.isEmpty()) {
                        suggBuilder.setWpa2Passphrase(storedPassword);
                    }
                    List<WifiNetworkSuggestion> list = new ArrayList<WifiNetworkSuggestion>();
                    list.add(suggBuilder.build());
                    wifiManager.addNetworkSuggestions(list);
                } catch (Exception ignored) {}

            } catch (Exception e) {
                logEvent("שגיאת חיבור אנדרואיד 10+: " + e.getMessage());
            }
        }

        // 3. Fallback / Native WifiConfiguration for API <= 28 or OEM support
        if (wifiManager != null) {
            try {
                int targetNetId = -1;
                List<WifiConfiguration> configured = null;
                try {
                    configured = wifiManager.getConfiguredNetworks();
                } catch (SecurityException ignored) {}

                if (configured != null) {
                    for (WifiConfiguration config : configured) {
                        String clean = cleanSsid(config.SSID);
                        if (clean != null && clean.equalsIgnoreCase(targetSsid)) {
                            targetNetId = config.networkId;
                            break;
                        }
                    }
                }

                if (targetNetId == -1) {
                    WifiConfiguration newConf = new WifiConfiguration();
                    newConf.SSID = "\"" + targetSsid + "\"";
                    if (storedPassword != null && !storedPassword.isEmpty()) {
                        newConf.preSharedKey = "\"" + storedPassword + "\"";
                    } else {
                        newConf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    }
                    targetNetId = wifiManager.addNetwork(newConf);
                }

                if (targetNetId != -1) {
                    wifiManager.disconnect();
                    boolean enabled = wifiManager.enableNetwork(targetNetId, true);
                    boolean reconnected = wifiManager.reconnect();
                    logEvent(String.format(Locale.getDefault(),
                            "פקודת EnableNetwork ל-NetId %d: תוצאה=%b, reconnect=%b",
                            targetNetId, enabled, reconnected));
                } else {
                    wifiManager.reconnect();
                }
            } catch (Exception e) {
                logEvent("הפעלת חיבור: " + e.getMessage());
            }
        }

        handler.postDelayed(() -> {
            refreshNetworkState();
            checkActiveInternetConnection();
        }, 3000);
    }

    /**
     * ACTUALLY disconnects Wi-Fi so cellular data immediately routes the device traffic
     */
    public void performRealDisconnect(boolean userInitiated) {
        logEvent("מבצע ניתוק Wi-Fi אמיתי לשחרור נתונים לסלולר...");

        // 1. Release active NetworkCallback if any
        if (activeNetworkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(activeNetworkCallback);
                logEvent("שוחרר NetworkCallback של החיבור");
            } catch (Exception ignored) {}
            activeNetworkCallback = null;
            currentlyBoundSsid = null;
        }

        // 2. Disconnect and Disable network via WifiManager
        if (wifiManager != null) {
            try {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    wifiManager.disableNetwork(info.getNetworkId());
                }
                wifiManager.disconnect();
                logEvent("נשלחה פקודת wifiManager.disconnect() ונטרול רשת");
            } catch (Exception e) {
                logEvent("שגיאת ניתוק: " + e.getMessage());
            }

            // If user explicitly asked or rule says disconnect far, try setWifiEnabled(false) on older versions
            if (prefs.getBoolean(KEY_DISCONNECT_FAR, true) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    wifiManager.setWifiEnabled(false);
                    logEvent("כיבוי מתג Wi-Fi למעבר מיידי לסלולר");
                } catch (Exception ignored) {}
            }
        }

        Toast.makeText(this, "Wi-Fi נותק - גלישה סלולרית פעילה", Toast.LENGTH_SHORT).show();

        handler.postDelayed(() -> {
            refreshNetworkState();
            checkActiveInternetConnection();
        }, 1500);
    }

    /**
     * Core automation evaluation
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

        // 1. Currently connected to Wi-Fi?
        if (connectedToWifi) {
            WifiInfo info = wifiManager.getConnectionInfo();
            int rssi = (info != null) ? info.getRssi() : -65;
            int quality = calculateSignalPercent(rssi);

            boolean isTrusted = (currentSsid != null && isKnownNetwork(currentSsid, savedSsids));

            // If connected to a trusted network with strong signal:
            if (isTrusted && quality >= targetThreshold) {
                applyStateWifiConnected(currentSsid, quality, userTriggered);
                return;
            }

            // If connected to trusted network BUT signal degraded below threshold:
            if (isTrusted && quality < targetThreshold && prefs.getBoolean(KEY_DISCONNECT_FAR, true)) {
                logEvent(String.format(Locale.getDefault(),
                        "איכות קליטה של '%s' ירדה ל-%d%% (מתחת ל-%d%%). מנתק למעבר חלק לסלולר!",
                        currentSsid, quality, targetThreshold));
                performRealDisconnect(false);
                applyStateOutOfRangeCellularOn(userTriggered, false, targetThreshold);
                return;
            }

            if (isTrusted) {
                applyStateWifiConnected(currentSsid, quality, userTriggered);
                return;
            }
        }

        // 2. Scan for trusted candidate in range
        try {
            wifiManager.startScan();
        } catch (Exception ignored) {}

        List<ScanResult> results = null;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException ignored) {}

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
                    "רשת מוכרת '%s' זוהתה בטווח (איכות %d%%) -> מבצע חיבור!",
                    candidateSsid, bestQuality));

            if (prefs.getBoolean(KEY_AUTO_CELLULAR, true)) {
                performRealConnect(candidateSsid, false);
            }
            applyStateWifiConnected(candidateSsid, bestQuality, userTriggered);
        } else {
            // Out of range: If previously connected to weak wifi or hanging, disconnect
            if (connectedToWifi && prefs.getBoolean(KEY_DISCONNECT_FAR, true)) {
                logEvent("מחוץ לטווח רשת מוכרת -> מנתק Wi-Fi לטובת סלולר");
                performRealDisconnect(false);
            }
            applyStateOutOfRangeCellularOn(userTriggered, savedSsids.isEmpty(), targetThreshold);
        }

        refreshNetworkState();
    }

    private void applyStateWifiConnected(String ssid, int quality, boolean userTriggered) {
        currentMacroState = STATE_CONNECTED_CELLULAR_OFF;

        runOnUiThread(() -> {
            tvOverallStatusBanner.setText("✔ " + getString(R.string.state_wifi_active) + " (" + ssid + " " + quality + "%)");
            tvOverallStatusBanner.setTextColor(0xFF1B5E20);
            tvOverallStatusBanner.setBackgroundResource(R.drawable.bg_status_badge);

            boolean promptRequested = prefs.getBoolean(KEY_SHOW_SETTINGS_PANEL, false);
            if (promptRequested) {
                tvPromptText.setText("📶 מחובר ל-Wi-Fi (" + ssid + "). ניתן לכבות סלולר לחיסכון:");
                btnPromptAction.setText("הגדרות סלולר");
                btnPromptAction.setOnClickListener(v -> openCellularSettings());
                layoutPromptBanner.setVisibility(View.VISIBLE);
            }
        });

        if (userTriggered) {
            showDialogResult("מאקרו - Wi-Fi מחובר",
                    "מכשירך מחובר לרשת המוכרת '" + ssid + "' באיכות מעולה (" + quality + "%).\n" +
                            "תעבורת הנתונים מתבצעת כעת דרך ה-Wi-Fi.");
        }
    }

    private void applyStateOutOfRangeCellularOn(boolean userTriggered, boolean noSaved, int threshold) {
        currentMacroState = STATE_DISCONNECTED_CELLULAR_ON;

        runOnUiThread(() -> {
            tvOverallStatusBanner.setText("⚡ " + getString(R.string.state_cellular_active));
            tvOverallStatusBanner.setTextColor(0xFFE65100);
            tvOverallStatusBanner.setBackgroundResource(R.drawable.bg_warning_badge);

            boolean promptRequested = prefs.getBoolean(KEY_SHOW_SETTINGS_PANEL, false);
            if (promptRequested) {
                tvPromptText.setText("⚡ מחוץ לטווח Wi-Fi: פתח בקרה לוודא שסלולר דולק:");
                btnPromptAction.setText("לוח בקרה מהיר");
                btnPromptAction.setOnClickListener(v -> openInternetSettingsPanel());
                layoutPromptBanner.setVisibility(View.VISIBLE);
            }
        });

        if (userTriggered) {
            String reason = noSaved ?
                    "טרם הוגדרו רשתות מוכרות ברשימה. הוסף את הרשת הביתית כדי שהמאקרו יתחבר אליה." :
                    "לא אותרה רשת מוכרת בטווח מעל סף האיכות (" + threshold + "%).\nהחיבור מנותק לטובת גלישה סלולרית רציפה.";
            showDialogResult("מחוץ לטווח Wi-Fi", reason);
        }
    }

    private void showDeviceNetworksDialog() {
        final List<String> availableSSIDs = new ArrayList<String>();
        Set<String> alreadySaved = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());

        // Current connected
        String current = cleanSsid(getCurrentConnectedSsid());
        if (current != null && !current.isEmpty()) {
            availableSSIDs.add(current);
        }

        // Scan results
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

        // Configured networks
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
            handler.postDelayed(this::showDeviceNetworksDialog, 800);
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

    private void addTrustedNetwork(String ssid, String password) {
        String clean = cleanSsid(ssid);
        if (clean == null || clean.isEmpty()) return;
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        saved.add(clean);
        SharedPreferences.Editor editor = prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved);
        if (password != null && !password.isEmpty()) {
            editor.putString(PREF_PASS_PREFIX + clean, password);
        }
        editor.apply();

        refreshKnownNetworksList();
        refreshNetworkState();
        logEvent("רשת נוספה למאקרו: " + clean);
        Toast.makeText(this, "רשת " + clean + " נוספה בהצלחה", Toast.LENGTH_SHORT).show();
        evaluateMacroRules(false);
    }

    private void removeTrustedNetwork(String ssid) {
        String clean = cleanSsid(ssid);
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        if (clean != null) saved.remove(clean);
        saved.remove(ssid);
        prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved)
                .remove(PREF_PASS_PREFIX + (clean != null ? clean : ssid))
                .apply();
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
            emptyTv.setText("אין רשתות מוגדרות. הקש 'ייבא מרשתות המכשיר' או הוסף ידנית למטה.");
            emptyTv.setTextSize(13);
            emptyTv.setTextColor(0xFF9E9E9E);
            emptyTv.setPadding(0, 8, 0, 8);
            layoutNetworkList.addView(emptyTv);
            return;
        }

        String currentActive = cleanSsid(getCurrentConnectedSsid());

        for (final String ssid : saved) {
            boolean isCur = (currentActive != null && currentActive.equalsIgnoreCase(ssid));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(isCur ? R.drawable.badge_connected : R.drawable.badge_bg);
            row.setPadding(14, 10, 14, 10);

            TextView tvName = new TextView(this);
            tvName.setText((isCur ? "🟢 " : "📶 ") + ssid);
            tvName.setTextSize(14);
            tvName.setTextColor(isCur ? 0xFF1B5E20 : 0xFF212121);
            tvName.setTypeface(isCur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(tvName, lp);

            // Direct Connect Button
            Button btnConnect = new Button(this);
            btnConnect.setText(isCur ? "מחובר" : "התחבר");
            btnConnect.setTextSize(12);
            btnConnect.setTextColor(0xFFFFFFFF);
            btnConnect.setBackgroundResource(isCur ? R.drawable.btn_success : R.drawable.btn_primary);
            btnConnect.setPadding(14, 4, 14, 4);
            btnConnect.setOnClickListener(v -> performRealConnect(ssid, true));
            row.addView(btnConnect);

            Button btnDelete = new Button(this);
            btnDelete.setText("מחק");
            btnDelete.setTextSize(12);
            btnDelete.setTextColor(0xFFC62828);
            btnDelete.setBackgroundResource(R.drawable.badge_bg);
            btnDelete.setPadding(12, 4, 12, 4);
            LinearLayout.LayoutParams lpDel = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpDel.setMargins(8, 0, 0, 0);
            btnDelete.setLayoutParams(lpDel);
            btnDelete.setOnClickListener(v -> removeTrustedNetwork(ssid));
            row.addView(btnDelete);

            layoutNetworkList.addView(row);
            addVerticalSpacer(layoutNetworkList, 6);
        }
    }

    private void openInternetSettingsPanel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Opens system mini bottom-sheet panel directly inside the screen!
                Intent panelIntent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                startActivity(panelIntent);
                return;
            } catch (Exception ignored) {}
            try {
                Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                startActivity(panelIntent);
                return;
            } catch (Exception ignored) {}
        }
        openWifiSettings();
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
        layout.setPadding(24, 20, 24, 20);
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
        btn.setPadding(18, 10, 18, 10);
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
                checkActiveInternetConnection();
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
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.NEARBY_WIFI_DEVICES");
                }
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
                logEvent("הרשאות רשת ומיקום אושרו בהצלחה");
                refreshNetworkState();
                evaluateMacroRules(false);
            } else {
                logEvent("הערה: חלק מההרשאות לא אושרו - סריקת רשתות עשויה להיות מוגבלת ע״י אנדרואיד.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLocationState();
        refreshNetworkState();
        checkActiveInternetConnection();
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
        if (activeNetworkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(activeNetworkCallback);
            } catch (Exception ignored) {}
            activeNetworkCallback = null;
        }
    }
}
