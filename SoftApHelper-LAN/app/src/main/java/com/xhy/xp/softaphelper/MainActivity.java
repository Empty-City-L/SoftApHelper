package com.xhy.xp.softaphelper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String DEFAULT_WIFI_IP = "192.168.43.1";
    private static final String DEFAULT_USB_IP = "192.168.42.1";
    private static final String DEFAULT_BT_IP = "192.168.44.1";
    private static final String DEFAULT_P2P_IP = "192.168.49.1";
    private static final String DEFAULT_ETHERNET_IP = "192.168.45.1";

    private SharedPreferences mPrefs;

    private EditText editWifiIp;
    private EditText editUsbIp;
    private EditText editBtIp;
    private EditText editP2pIp;
    private EditText editEthernetIp;
    private Switch switchEth;
    private Switch switchUsb;
    private TextView tvSim;
    private EditText etPin;
    private EditText etMqttHost;
    private EditText etMqttUser;
    private EditText etMqttPass;
    private EditText etMqttDevice;
    private TextView tvHint;
    private final ExecutorService simExecutor = Executors.newSingleThreadExecutor();
    private final Handler simHandler = new Handler(Looper.getMainLooper());
    private View contentLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        contentLayout = findViewById(R.id.content_layout);
        migrateToDeviceProtectedStorage();
        mPrefs = Config.getPrefs(this);

        editWifiIp = findViewById(R.id.edit_wifi_ip);
        editUsbIp = findViewById(R.id.edit_usb_ip);
        editBtIp = findViewById(R.id.edit_bt_ip);
        editP2pIp = findViewById(R.id.edit_p2p_ip);
        editEthernetIp = findViewById(R.id.edit_ethernet_ip);
        switchEth = findViewById(R.id.switch_eth);
        switchUsb = findViewById(R.id.switch_usb);
        tvHint = findViewById(R.id.tv_hint);
        tvSim = findViewById(R.id.tv_sim);
        etPin = findViewById(R.id.et_pin);
        Button btnSavePin = findViewById(R.id.btn_save_pin);
        btnSavePin.setOnClickListener(v -> savePin());
        etMqttHost = findViewById(R.id.et_mqtt_host);
        etMqttUser = findViewById(R.id.et_mqtt_user);
        etMqttPass = findViewById(R.id.et_mqtt_pass);
        etMqttDevice = findViewById(R.id.et_mqtt_device);
        Button btnSaveMqtt = findViewById(R.id.btn_save_mqtt);
        btnSaveMqtt.setOnClickListener(v -> saveMqtt());
        loadMqtt();
        etPin.setText(Config.getPinPrefs(this).getString("pin", ""));
        Button btnSimRefresh = findViewById(R.id.btn_sim_refresh);
        btnSimRefresh.setOnClickListener(v -> refreshSim());
        ensureSimPin();
        refreshSim();
        Button btnSave = findViewById(R.id.btn_save);
        Button btnReset = findViewById(R.id.btn_reset);

        TextView tvVersion = findViewById(R.id.tv_version);
        tvVersion.setText(getString(R.string.version_prefix, BuildConfig.VERSION_NAME));

        loadValues();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveValues();
            }
        });

        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPrefs.edit().clear().apply();
                loadValues();
                tvHint.setText(R.string.hint_reset);
            }
        });

        applyWindowInsets();
    }

    // Android 15+ 强制 edge-to-edge，需要自己避开状态栏/导航栏；
    // 更老的系统窗口本来就不铺到系统栏下面，给固定 padding 即可，避免重复留白。
    private void applyWindowInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            int pad = dp(20);
            contentLayout.setPadding(pad, pad, pad, pad);
            return;
        }
        View root = getWindow().getDecorView();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int base = dp(20);
                contentLayout.setPadding(base + bars.left, base + bars.top,
                        base + bars.right, base + bars.bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void migrateToDeviceProtectedStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        SharedPreferences de = Config.getPrefs(this);
        if (!de.getAll().isEmpty()) {
            return;
        }
        SharedPreferences legacy = getSharedPreferences(Config.PREF_NAME, MODE_PRIVATE);
        java.util.Map<String, ?> all = legacy.getAll();
        if (all.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = de.edit();
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof String) {
                editor.putString(entry.getKey(), (String) value);
            } else if (value instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(entry.getKey(), (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(entry.getKey(), (Float) value);
            }
        }
        editor.apply();
    }

    private void ensureSimPin() {
        SharedPreferences prefs = Config.getPinPrefs(this);
        String pin = prefs.getString("pin", null);
        if (pin == null || pin.length() != 4) {
            pin = String.format("%04d", new Random().nextInt(10000));
            prefs.edit().putString("pin", pin).apply();
        }
    }

    private void loadMqtt() {
        android.content.SharedPreferences prefs =
                Config.getMqttPrefs(this);
        etMqttHost.setText(prefs.getString("host", ""));
        etMqttUser.setText(prefs.getString("user", ""));
        etMqttPass.setText(prefs.getString("pass", ""));
        etMqttDevice.setText(prefs.getString("device", "xiaomi14"));
        android.util.Log.i("MqttConfig", "load host=" + prefs.getString("host", "") + " user=" + prefs.getString("user", "") + " device=" + prefs.getString("device", ""));
    }

    private void saveMqtt() {
        Config.getMqttPrefs(this).edit()
                .putString("host", etMqttHost.getText().toString().trim())
                .putString("user", etMqttUser.getText().toString().trim())
                .putString("pass", etMqttPass.getText().toString())
                .putString("device", etMqttDevice.getText().toString().trim())
                .putInt("port", 8883)
                .apply();
        android.util.Log.i("MqttConfig", "save host=" + etMqttHost.getText() + " device=" + etMqttDevice.getText());
        toast(getString(R.string.mqtt_saved));
    }

    private void savePin() {
        String pin = etPin.getText().toString().trim();
        if (pin.length() != 4) {
            toast(getString(R.string.pin_invalid));
            return;
        }
        Config.getPinPrefs(this)
                .edit().putString("pin", pin).apply();
        toast(getString(R.string.pin_saved));
    }


    private void refreshSim() {
        tvSim.setText(R.string.reading);
        simExecutor.execute(() -> {
            String text = SimUtils.describe();
            text += systemServiceAlive()
                    ? "\nsystem_server 服务: 运行中"
                    : "\nsystem_server 服务: 未运行（更新模块后请重启手机）";
            final String result = text;
            simHandler.post(() -> tvSim.setText(result));
        });
    }

    private boolean systemServiceAlive() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 8080), 500);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }


    private void loadValues() {
        editWifiIp.setText(getPref(Config.KEY_WIFI_IP, DEFAULT_WIFI_IP));
        editUsbIp.setText(getPref(Config.KEY_USB_IP, DEFAULT_USB_IP));
        editBtIp.setText(getPref(Config.KEY_BT_IP, DEFAULT_BT_IP));
        editP2pIp.setText(getPref(Config.KEY_P2P_IP, DEFAULT_P2P_IP));
        editEthernetIp.setText(getPref(Config.KEY_ETHERNET_IP, DEFAULT_ETHERNET_IP));
        switchEth.setChecked(getBooleanPref(Config.KEY_ETH_AUTO, true));
        switchUsb.setChecked(getBooleanPref(Config.KEY_USB_AUTO, true));
    }

    private void saveValues() {
        String wifi = trim(editWifiIp);
        String usb = trim(editUsbIp);
        String bt = trim(editBtIp);
        String p2p = trim(editP2pIp);
        String eth = trim(editEthernetIp);

        if (!Config.isValidIpv4(wifi)) {
            toast(getString(R.string.toast_ip_invalid, getString(R.string.label_wifi), wifi));
            return;
        }
        if (!Config.isValidIpv4(usb)) {
            toast(getString(R.string.toast_ip_invalid, getString(R.string.label_usb), usb));
            return;
        }
        if (!Config.isValidIpv4(bt)) {
            toast(getString(R.string.toast_ip_invalid, getString(R.string.label_bt), bt));
            return;
        }
        if (!Config.isValidIpv4(p2p)) {
            toast(getString(R.string.toast_ip_invalid, getString(R.string.label_p2p), p2p));
            return;
        }
        if (!Config.isValidIpv4(eth)) {
            toast(getString(R.string.toast_ip_invalid, getString(R.string.label_ethernet), eth));
            return;
        }

        mPrefs.edit()
                .putString(Config.KEY_WIFI_IP, wifi)
                .putString(Config.KEY_USB_IP, usb)
                .putString(Config.KEY_BT_IP, bt)
                .putString(Config.KEY_P2P_IP, p2p)
                .putString(Config.KEY_ETHERNET_IP, eth)
                .putBoolean(Config.KEY_ETH_AUTO, switchEth.isChecked())
                .putBoolean(Config.KEY_USB_AUTO, switchUsb.isChecked())
                .apply();

        toast(getString(R.string.toast_saved));
        tvHint.setText(R.string.hint_saved);
    }

    private String getPref(String key, String def) {
        String value = mPrefs.getString(key, null);
        return (value != null && !value.isEmpty()) ? value : def;
    }

    private boolean getBooleanPref(String key, boolean def) {
        try {
            return mPrefs.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    @Override
    protected void onDestroy() {
        simExecutor.shutdownNow();
        super.onDestroy();
    }

    private String trim(EditText editText) {
        return editText.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
