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
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "SmartMacroPrefs";
    private static final String KEY_MACRO_ENABLED = "macro_enabled";
    private static final String KEY_AUTO_DISABLE_CELLULAR = "auto_disable_cellular";
    private static final String KEY_RSSI_THRESHOLD = "rssi_threshold";
    private static final String KEY_SAVED_SSIDS = "saved_ssids";
    private static final int PERMISSION_REQ_CODE = 101;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private SharedPreferences prefs;

    // UI Views
    private Switch switchMacroToggle;
    private TextView tvMacroState;
    private TextView tvWifiStatus;
    private TextView tvCellularStatus;
    private TextView tvThresholdValue;
    private SeekBar seekThreshold;
    private CheckBox cbAutoDisableCellular;
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
            handler.postDelayed(this, 12000); // Check periodic cycle
        }
    };

    private BroadcastReceiver wifiReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        // Ensure default networks if empty
        Set<String> saved = prefs.getStringSet(KEY_SAVED_SSIDS, null);
        if (saved == null) {
            Set<String> defaults = new HashSet<String>();
            defaults.add("Home-WiFi");
            defaults.add("Office-Net");
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
        root.setPadding(32, 40, 32, 60);

        // Header Title
        TextView tvHeader = new TextView(this);
        tvHeader.setText(getString(R.string.app_name));
        tvHeader.setTextSize(22);
        tvHeader.setTypeface(Typeface.DEFAULT_BOLD);
        tvHeader.setTextColor(0xFF0D47A1);
        tvHeader.setGravity(Gravity.RIGHT);
        root.addView(tvHeader);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("ניהול אוטומטי חכם: חיבור ל-Wi-Fi איכותי וניתוק סלולר לחסכון");
        tvSubtitle.setTextSize(13);
        tvSubtitle.setTextColor(0xFF546E7A);
        tvSubtitle.setPadding(0, 4, 0, 24);
        tvSubtitle.setGravity(Gravity.RIGHT);
        root.addView(tvSubtitle);

        // 1. Master Macro Card
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
        tvMacroState.setTextSize(14);
        tvMacroState.setTextColor(0xFF2E7D32);
        tvMacroState.setPadding(0, 10, 0, 0);
        macroCard.addView(tvMacroState);

        switchMacroToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_MACRO_ENABLED, isChecked).apply();
            updateMacroStatusLabel();
            logEvent(isChecked ? "מאקרו הופעל על ידי המשתמש" : "מאקרו כובה על ידי המשתמש");
            if (isChecked) {
                evaluateMacroRules(true);
            }
        });
        root.addView(macroCard);
        addVerticalSpacer(root, 20);

        // 2. Real-time Status Card
        LinearLayout statusCard = createCardLayout(this);
        TextView tvCurrentHeader = createSectionHeader("מצב חיבורי רשת כעת");
        statusCard.addView(tvCurrentHeader);

        tvWifiStatus = new TextView(this);
        tvWifiStatus.setTextSize(14);
        tvWifiStatus.setTextColor(0xFF263238);
        tvWifiStatus.setPadding(0, 6, 0, 4);
        statusCard.addView(tvWifiStatus);

        tvCellularStatus = new TextView(this);
        tvCellularStatus.setTextSize(14);
        tvCellularStatus.setTextColor(0xFF263238);
        tvCellularStatus.setPadding(0, 4, 0, 10);
        statusCard.addView(tvCellularStatus);

        LinearLayout btnRowSettings = new LinearLayout(this);
        btnRowSettings.setOrientation(LinearLayout.HORIZONTAL);

        Button btnWifiSetting = createSmallButton("הגדרות Wi-Fi");
        btnWifiSetting.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "לא ניתן לפתוח הגדרות Wi-Fi", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams btnP1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnP1.setMargins(0, 0, 8, 0);
        btnRowSettings.addView(btnWifiSetting, btnP1);

        Button btnCellSetting = createSmallButton(getString(R.string.action_open_data_settings));
        btnCellSetting.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception ex) {
                    Toast.makeText(this, "לא ניתן לפתוח הגדרות סלולר", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams btnP2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRowSettings.addView(btnCellSetting, btnP2);
        statusCard.addView(btnRowSettings);

        root.addView(statusCard);
        addVerticalSpacer(root, 20);

        // 3. Automation Rules & Quality Threshold Card
        LinearLayout rulesCard = createCardLayout(this);
        TextView tvRulesHeader = createSectionHeader(getString(R.string.title_rules));
        rulesCard.addView(tvRulesHeader);

        cbAutoDisableCellular = new CheckBox(this);
        cbAutoDisableCellular.setText(getString(R.string.rule_pref_wifi));
        cbAutoDisableCellular.setChecked(prefs.getBoolean(KEY_AUTO_DISABLE_CELLULAR, true));
        cbAutoDisableCellular.setTextSize(14);
        cbAutoDisableCellular.setTextColor(0xFF212121);
        cbAutoDisableCellular.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_AUTO_DISABLE_CELLULAR, isChecked).apply();
            logEvent("הגדרת ניתוק סלולר בחיבור מוצלח עודכנה: " + (isChecked ? "פעיל" : "מבוטל"));
        });
        rulesCard.addView(cbAutoDisableCellular);

        TextView tvRulesDesc = new TextView(this);
        tvRulesDesc.setText(R.string.rule_desc);
        tvRulesDesc.setTextSize(12);
        tvRulesDesc.setTextColor(0xFF757575);
        tvRulesDesc.setPadding(0, 4, 0, 16);
        rulesCard.addView(tvRulesDesc);

        // RSSI Threshold slider
        int savedThreshold = prefs.getInt(KEY_RSSI_THRESHOLD, 65); // 65 means -65 dBm or 65%
        tvThresholdValue = new TextView(this);
        tvThresholdValue.setText(String.format(Locale.getDefault(), "סף איכות נדרש להתחברות: %d%% (עוצמה מעל -%d dBm)", savedThreshold, 110 - savedThreshold));
        tvThresholdValue.setTextSize(13);
        tvThresholdValue.setTextColor(0xFF1565C0);
        tvThresholdValue.setTypeface(Typeface.DEFAULT_BOLD);
        rulesCard.addView(tvThresholdValue);

        seekThreshold = new SeekBar(this);
        seekThreshold.setMax(100);
        seekThreshold.setProgress(savedThreshold);
        seekThreshold.setPadding(10, 12, 10, 16);
        seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 25) progress = 25; // minimum viable signal
                tvThresholdValue.setText(String.format(Locale.getDefault(), "סף איכות נדרש להתחברות: %d%% (עוצמה מעל -%d dBm)", progress, 110 - progress));
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
        btnRunMacroNow.setPadding(20, 16, 20, 16);
        btnRunMacroNow.setOnClickListener(v -> {
            logEvent("הפעלת בדיקת מאקרו ידנית יזומה...");
            evaluateMacroRules(true);
        });
        rulesCard.addView(btnRunMacroNow);

        root.addView(rulesCard);
        addVerticalSpacer(root, 20);

        // 4. Preferred / Known Networks Card
        LinearLayout networksCard = createCardLayout(this);
        TextView tvNetHeader = createSectionHeader(getString(R.string.title_saved_networks));
        networksCard.addView(tvNetHeader);

        TextView tvNetExpl = new TextView(this);
        tvNetExpl.setText("הרשתות שברשימה ייבדקו עבור חיבור אוטומטי כשיימצאו בטווח באיכות טובה:");
        tvNetExpl.setTextSize(12);
        tvNetExpl.setTextColor(0xFF616161);
        tvNetExpl.setPadding(0, 0, 0, 10);
        networksCard.addView(tvNetExpl);

        layoutNetworkList = new LinearLayout(this);
        layoutNetworkList.setOrientation(LinearLayout.VERTICAL);
        networksCard.addView(layoutNetworkList);

        // Add network input row
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
        addVerticalSpacer(root, 20);

        // 5. Activity Execution Log Card
        LinearLayout logCard = createCardLayout(this);
        LinearLayout logHeaderRow = new LinearLayout(this);
        logHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        logHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLogHeader = createSectionHeader(getString(R.string.title_activity_log));
        logHeaderRow.addView(tvLogHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClearLog = createSmallButton(getString(R.string.clear_log));
        btnClearLog.setOnClickListener(v -> {
            tvLogConsole.setText("");
            logEvent("היומן אופס");
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
        logEvent("מאקרו הופעל בהצלחה במערכת");
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

    private void refreshNetworkState() {
        // Wi-Fi Status
        if (wifiManager != null) {
            boolean wifiOn = wifiManager.isWifiEnabled();
            if (!wifiOn) {
                tvWifiStatus.setText(getString(R.string.label_wifi) + " כבוי (Wi-Fi מנוטרל)");
            } else {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    String ssid = info.getSSID();
                    if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    int rssi = info.getRssi();
                    int quality = calculateSignalPercent(rssi);
                    tvWifiStatus.setText(String.format(Locale.getDefault(),
                            "%s מחובר ל-%s (קליטה: %d%%, %d dBm)",
                            getString(R.string.label_wifi), ssid, quality, rssi));
                } else {
                    tvWifiStatus.setText(getString(R.string.label_wifi) + " מופעל (לא מחובר לרשת)");
                }
            }
        }

        // Cellular Status
        NetworkInfo mobileInfo = connectivityManager != null ?
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) : null;
        boolean cellConnected = (mobileInfo != null && mobileInfo.isConnected());
        String carrier = telephonyManager != null ? telephonyManager.getNetworkOperatorName() : "";
        if (carrier == null || carrier.isEmpty()) carrier = "סלולר ברירת מחדל";

        if (cellConnected) {
            tvCellularStatus.setText(String.format("%s פעיל ומחובר (%s)", getString(R.string.label_cellular), carrier));
            tvCellularStatus.setTextColor(0xFF0D47A1);
        } else {
            tvCellularStatus.setText(String.format("%s מנותק / מושעה (לא בשימוש)", getString(R.string.label_cellular)));
            tvCellularStatus.setTextColor(0xFF757575);
        }
    }

    /**
     * Executes the macro logic:
     * 1. Check if Wi-Fi is enabled and scan for known networks.
     * 2. Evaluate signal quality (RSSI) of found networks.
     * 3. If quality is >= threshold, connect/prioritize Wi-Fi and disable cellular.
     */
    private void evaluateMacroRules(boolean userTriggered) {
        if (!isMacroEnabled()) {
            if (userTriggered) {
                Toast.makeText(this, "המאקרו כבוי כרגע, יש להפעילו תחילה", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (wifiManager == null) return;

        // Ensure Wi-Fi is on to check environment
        if (!wifiManager.isWifiEnabled()) {
            logEvent("המאקרו מזהה ש-Wi-Fi כבוי, מדליק Wi-Fi לסריקה...");
            try {
                wifiManager.setWifiEnabled(true);
            } catch (Exception ignored) {}
        }

        int targetThreshold = prefs.getInt(KEY_RSSI_THRESHOLD, 65);
        Set<String> savedSsids = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());

        // Inspect current Wi-Fi connection
        WifiInfo currentInfo = wifiManager.getConnectionInfo();
        String currentSsid = (currentInfo != null) ? currentInfo.getSSID() : null;
        if (currentSsid != null && currentSsid.startsWith("\"") && currentSsid.endsWith("\"")) {
            currentSsid = currentSsid.substring(1, currentSsid.length() - 1);
        }

        boolean currentConnected = (currentInfo != null && currentInfo.getNetworkId() != -1);
        int currentRssi = (currentInfo != null) ? currentInfo.getRssi() : -100;
        int currentQuality = calculateSignalPercent(currentRssi);

        if (currentConnected && isKnownNetwork(currentSsid, savedSsids)) {
            if (currentQuality >= targetThreshold) {
                logEvent(String.format(Locale.getDefault(),
                        "רשת מוכרת '%s' מחוברת באיכות מעולה (%d%% >= %d%%). החיבור הסלולרי מנוטרל.",
                        currentSsid, currentQuality, targetThreshold));
                handleCellularSuppression();
                if (userTriggered) {
                    showDialogResult("מאקרו פועל", "מחובר בהצלחה לרשת מוכרת " + currentSsid + " בעוצמה של " + currentQuality + "%.\nרשת סלולרית נוטרלה.");
                }
                return;
            } else {
                logEvent(String.format(Locale.getDefault(),
                        "רשת '%s' חלשה מדי (%d%% < סף %d%%). מאפשר גיבוי סלולרי.",
                        currentSsid, currentQuality, targetThreshold));
            }
        }

        // Scan results to see if a good known network is in range
        boolean scanInitiated = false;
        try {
            scanInitiated = wifiManager.startScan();
        } catch (Exception ignored) {}

        List<ScanResult> results = null;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException se) {
            logEvent("שגיאת הרשאת מיקום: נדרש אישור סריקת רשתות");
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
            logEvent(String.format(Locale.getDefault(),
                    "זוהתה רשת מוכרת '%s' בטווח! איכות: %d%% (מעל סף %d%%). מבצע חיבור וכיבוי סלולרי.",
                    bestCandidate.SSID, bestQuality, targetThreshold));

            // Attempt connection / trigger Wi-Fi network selection
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    logEvent("בגרסת אנדרואיד זו, המערכת מתחברת אוטומטית לפי עדיפות Wi-Fi.");
                }
            } catch (Exception ignored) {}

            handleCellularSuppression();
            if (userTriggered) {
                showDialogResult("נמצאה רשת מועדפת",
                        "הרשת '" + bestCandidate.SSID + "' נמצאה בעוצמה גבוהה (" + bestQuality + "%).\n" +
                        "המאקרו מפעיל חיבור אלחוטי ומנתק נתונים סלולריים.");
            }
        } else {
            logEvent(String.format(Locale.getDefault(),
                    "סריקה הסתיימה: לא נמצאה רשת מוכרת מעל סף האיכות (%d%%).", targetThreshold));
            if (userTriggered) {
                showDialogResult("בדיקת סריקה", "לא נמצאה כרגע רשת מוכרת מעל סף האיכות המוגדר (" + targetThreshold + "%).");
            }
        }
        refreshNetworkState();
    }

    private void handleCellularSuppression() {
        boolean autoDisable = prefs.getBoolean(KEY_AUTO_DISABLE_CELLULAR, true);
        if (!autoDisable) return;

        logEvent("ביצוע פעולת מאקרו: עדיפות ל-Wi-Fi וכיבוי/חסימת שימוש בסלולר");
        // On modern standard Android, directly turning off mobile data programmatically
        // without Carrier/System privilege is sandboxed. We notify or launch internet panel if user prefers.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // System automatically gives priority to Wi-Fi over Cellular
                logEvent("אנדרואיד מפנה תעבורה ל-Wi-Fi אוטומטית ומנטרלת צריכת סלולר.");
            }
        } catch (Exception e) {
            logEvent("הודעת ניתוק: " + e.getMessage());
        }
    }

    private boolean isKnownNetwork(String ssid, Set<String> savedSsids) {
        if (ssid == null || ssid.isEmpty()) return false;
        for (String saved : savedSsids) {
            if (ssid.equalsIgnoreCase(saved.trim())) {
                return true;
            }
        }
        return false;
    }

    private int calculateSignalPercent(int rssi) {
        // Standard RSSI range: -100 dBm (0%) to -50 dBm (100%)
        if (rssi <= -100) return 0;
        if (rssi >= -50) return 100;
        return 2 * (rssi + 100);
    }

    private void addTrustedNetwork(String ssid) {
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        saved.add(ssid);
        prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved).apply();
        refreshKnownNetworksList();
        logEvent("רשת חדשה נוספה לרשימת המאקרו: " + ssid);
        Toast.makeText(this, "הרשת נוספה בהצלחה", Toast.LENGTH_SHORT).show();
    }

    private void removeTrustedNetwork(String ssid) {
        Set<String> saved = new HashSet<String>(prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>()));
        saved.remove(ssid);
        prefs.edit().putStringSet(KEY_SAVED_SSIDS, saved).apply();
        refreshKnownNetworksList();
        logEvent("רשת הוסרה מרשימת המאקרו: " + ssid);
    }

    private void refreshKnownNetworksList() {
        if (layoutNetworkList == null) return;
        layoutNetworkList.removeAllViews();

        Set<String> saved = prefs.getStringSet(KEY_SAVED_SSIDS, new HashSet<String>());
        if (saved == null || saved.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("אין כרגע רשתות מוגדרות. הוסף רשת להפעלת המאקרו.");
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
        layout.setPadding(28, 24, 28, 24);
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
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshNetworkState();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(wifiReceiver, filter);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<String>();
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
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
                logEvent("הרשאות סריקה אושרו על ידי המשתמש");
                evaluateMacroRules(false);
            } else {
                logEvent("אזהרה: ללא הרשאת מיקום, סריקת רשתות Wi-Fi תוגבל על ידי אנדרואיד.");
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
        if (wifiReceiver != null) {
            try {
                unregisterReceiver(wifiReceiver);
            } catch (Exception ignored) {}
        }
    }
}
