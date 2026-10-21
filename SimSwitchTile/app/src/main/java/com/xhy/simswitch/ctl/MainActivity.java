package com.xhy.simswitch.ctl;

import android.app.Activity;
import android.content.ComponentName;
import android.service.quicksettings.TileService;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private EditText etUrl;
    private EditText etPin;
    private EditText etMqttHost;
    private EditText etMqttUser;
    private EditText etMqttPass;
    private EditText etMqttDevice;
    private TextView tvResult;
    private TextView tvStatus;
    private SharedPreferences prefs;
    private int switchGeneration = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applyWindowInsets();
        prefs = getSharedPreferences(Controller.PREFS, MODE_PRIVATE);
        etUrl = findViewById(R.id.et_url);
        etPin = findViewById(R.id.et_pin);
        tvResult = findViewById(R.id.tv_result);
        tvStatus = findViewById(R.id.tv_status);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnTest = findViewById(R.id.btn_test);
        Button btnSim1 = findViewById(R.id.btn_sim1);
        Button btnSim2 = findViewById(R.id.btn_sim2);
        etMqttHost = findViewById(R.id.et_mqtt_host);
        etMqttUser = findViewById(R.id.et_mqtt_user);
        etMqttPass = findViewById(R.id.et_mqtt_pass);
        etMqttDevice = findViewById(R.id.et_mqtt_device);
        Button btnSaveMqtt = findViewById(R.id.btn_save_mqtt);
        Button btnMqttToggle = findViewById(R.id.btn_mqtt_toggle);
        btnSaveMqtt.setOnClickListener(v -> saveMqtt());
        btnMqttToggle.setOnClickListener(v -> {
            MqttCtl.setListener((ok, text) -> tvResult.setText(text));
            tvResult.setText("连接 MQTT…");
            executor.execute(() -> {
                MqttCtl.start(getApplicationContext());
                MqttCtl.test();
            });
        });
        loadMqtt();
        if (MqttCtl.isConfigured(this)) {
            executor.execute(() -> MqttCtl.start(getApplicationContext()));
        }

        etUrl.setText(prefs.getString("url", Controller.DEFAULT_URL));
        etPin.setText(prefs.getString("pin", ""));

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            TileService.requestListeningState(this, new ComponentName(this, SimTileService.class));
        }

        btnSave.setOnClickListener(v -> {
            prefs.edit()
                    .putString("url", etUrl.getText().toString().trim())
                    .putString("pin", etPin.getText().toString().trim())
                    .apply();
            tvResult.setText(R.string.saved);
        });
        btnTest.setOnClickListener(v -> refreshStatus());
        btnSim1.setOnClickListener(v -> switchTo(1));
        btnSim2.setOnClickListener(v -> switchTo(2));

        refreshStatus();
    }

    private void applyWindowInsets() {
        View content = findViewById(R.id.content_layout);
        int base = Math.round(20 * getResources().getDisplayMetrics().density);
        if (Build.VERSION.SDK_INT < 35) {
            content.setPadding(base, base, base, base);
            return;
        }
        View root = getWindow().getDecorView();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private String url() {
        return etUrl.getText().toString().trim();
    }

    private String pin() {
        return etPin.getText().toString().trim();
    }

    private void switchTo(int slot) {
        final int generation = ++switchGeneration;
        tvResult.setText("切换中…");
        final String lanUrl = Controller.switchUrl(url(), pin(), slot);
        executor.execute(() -> {
            String raw = null;
            long cost = 0L;
            if (!url().isEmpty()) {
                long start = SystemClock.elapsedRealtime();
                try {
                    raw = Controller.httpGet(lanUrl, 500, 2500);
                } catch (Throwable ignored) {
                }
                cost = SystemClock.elapsedRealtime() - start;
            }
            if (raw != null) {
                final String shown = formatSwitch(raw, cost);
                main.post(() -> {
                    if (generation == switchGeneration) {
                        tvResult.setText(shown);
                        refreshStatus();
                    }
                });
                return;
            }
            if (!MqttCtl.isConfigured(this)) {
                main.post(() -> {
                    if (generation == switchGeneration) {
                        tvResult.setText("局域网不可用，且没有配置 MQTT");
                    }
                });
                return;
            }
            MqttCtl.setListener((ok, text) -> {
                if (generation != switchGeneration) {
                    return;
                }
                tvResult.setText(text);
                main.postDelayed(this::refreshStatus, 600L);
            });
            boolean connected = MqttCtl.ensureConnected(getApplicationContext(), 800);
            if (!connected) {
                main.post(() -> {
                    if (generation == switchGeneration) {
                        tvResult.setText("局域网不可用，MQTT 也没连上");
                    }
                });
                return;
            }
            MqttCtl.switchTo(slot);
        });
    }
    private void switchOverLan(int slot) {
        run(Controller.switchUrl(url(), pin(), slot));
    }

    private void loadMqtt() {
        SharedPreferences prefs = getSharedPreferences(MqttCtl.PREFS, MODE_PRIVATE);
        etMqttHost.setText(prefs.getString("host", ""));
        etMqttUser.setText(prefs.getString("user", ""));
        etMqttPass.setText(prefs.getString("pass", ""));
        etMqttDevice.setText(prefs.getString("device", "xiaomi14"));
    }

    private void saveMqtt() {
        getSharedPreferences(MqttCtl.PREFS, MODE_PRIVATE).edit()
                .putString("host", etMqttHost.getText().toString().trim())
                .putString("user", etMqttUser.getText().toString().trim())
                .putString("pass", etMqttPass.getText().toString())
                .putString("device", etMqttDevice.getText().toString().trim())
                .putInt("port", 8883)
                .apply();
        // 断开旧连接可能阻塞，放到后台线程
        executor.execute(() -> {
            MqttCtl.stop();
            MqttCtl.start(getApplicationContext());
        });
        tvResult.setText(R.string.mqtt_saved);
    }

    private void run(String requestUrl) {
        tvResult.setText(R.string.requesting);
        executor.execute(() -> {
            long start = SystemClock.elapsedRealtime();
            String text;
            try {
                text = Controller.httpGet(requestUrl);
            } catch (Throwable t) {
                text = null;
                final String error = "失败: " + t;
                main.post(() -> tvResult.setText(error));
            }
            long cost = SystemClock.elapsedRealtime() - start;
            if (text != null) {
                final String shown = formatSwitch(text, cost);
                main.post(() -> {
                    tvResult.setText(shown);
                    refreshStatus();
                });
            }
        });
    }

    private String formatSwitch(String raw, long cost) {
        try {
            JSONObject json = new JSONObject(raw);
            boolean ok = json.optBoolean("ok", false);
            int slot = json.optInt("slot", -1);
            int subId = json.optInt("subId", -1);
            int def = json.optInt("default", -1);
            StringBuilder sb = new StringBuilder();
            sb.append(ok ? "切换成功" : "切换失败");
            if (slot >= 0) {
                sb.append("：卡").append(slot + 1);
            }
            sb.append("（subId=").append(subId).append("）");
            sb.append("\n当前默认 subId=").append(def);
            sb.append("，耗时 ").append(cost).append(" ms");
            if (!ok) {
                sb.append("\n").append(json.optString("error", raw));
            }
            return sb.toString();
        } catch (Throwable t) {
            return raw;
        }
    }

    private void refreshStatus() {
        if (tvStatus == null) {
            return;
        }
        tvStatus.setText("读取状态…");
        final String lanUrl = Controller.statusUrl(url(), pin());
        executor.execute(() -> {
            String text = null;
            try {
                text = formatStatus(Controller.httpGet(lanUrl, 500, 1000), "局域网");
            } catch (Throwable ignored) {
            }
            if (text == null) {
                String state = MqttCtl.getStateJson();
                if (state != null && !state.isEmpty()) {
                    try {
                        text = formatStatus(state, "MQTT");
                    } catch (Throwable ignored) {
                    }
                }
            }
            final String result = text != null ? text
                    : "状态不可用：局域网不通，MQTT 也还没收到状态";
            main.post(() -> tvStatus.setText(result));
        });
    }

    private String formatStatus(String raw, String source) throws Exception {
        JSONObject json = new JSONObject(raw);
        int def = json.optInt("default", -1);
        JSONArray sims = json.optJSONArray("sims");
        StringBuilder sb = new StringBuilder();
        sb.append("当前默认：").append(describe(def, sims)).append("\n");
        if (sims != null) {
            for (int i = 0; i < sims.length(); i++) {
                JSONObject sim = sims.getJSONObject(i);
                int subId = sim.optInt("subId");
                sb.append("卡").append(sim.optInt("slot") + 1).append("  ")
                        .append(sim.optString("name")).append("  subId=").append(subId);
                if (subId == def) {
                    sb.append("  ← 默认");
                }
                sb.append("\n");
            }
        }
        sb.append("来源：").append(source);
        sb.append("  ").append(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date()));
        return sb.toString();
    }

    private String describe(int subId, JSONArray sims) {
        if (sims != null) {
            for (int i = 0; i < sims.length(); i++) {
                JSONObject sim = sims.optJSONObject(i);
                if (sim != null && sim.optInt("subId") == subId) {
                    return "卡" + (sim.optInt("slot") + 1) + " " + sim.optString("name")
                            + "（subId=" + subId + "）";
                }
            }
        }
        return "subId=" + subId;
    }

    @Override
    protected void onDestroy() {
        switchGeneration++;
        MqttCtl.setListener(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
