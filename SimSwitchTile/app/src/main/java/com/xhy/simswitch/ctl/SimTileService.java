package com.xhy.simswitch.ctl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONArray;
import org.json.JSONObject;

public class SimTileService extends TileService {

    private static final long MQTT_WAIT_MS = 800L;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        // 展开快捷设置面板时先把 MQTT 连接预热，点磁贴时就不用再等握手
        Context app = getApplicationContext();
        if (MqttCtl.isConfigured(app)) {
            new Thread(() -> MqttCtl.start(app), "SimSwitch-Tile-Connect").start();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        // 先给用户即时反馈，网络操作全部放到后台线程
        updateTile("切换中…", Tile.STATE_ACTIVE);
        new Thread(this::doToggle, "SimSwitch-Tile").start();
    }

    private void doToggle() {
        Context app = getApplicationContext();
        if (MqttCtl.isConfigured(app) && MqttCtl.ensureConnected(app, MQTT_WAIT_MS)) {
            MqttCtl.setListener((ok, text) ->
                    updateTile(text, ok ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE));
            MqttCtl.toggle();
            return;
        }
        if (MqttCtl.isConfigured(app)) {
            // MQTT 这次来不及连上，直接走局域网，不再干等连接超时
            updateTile("MQTT 未连接，走局域网…", Tile.STATE_ACTIVE);
        }
        toggleOverLan();
    }

    private void toggleOverLan() {
        SharedPreferences prefs = getSharedPreferences(Controller.PREFS, MODE_PRIVATE);
        String baseUrl = prefs.getString("url", Controller.DEFAULT_URL);
        String pin = prefs.getString("pin", "");
        try {
            // 单次请求完成切换，省掉旧版 status + switch 的两次往返
            String body = Controller.httpGet(Controller.toggleUrl(baseUrl, pin), 2500, 4000);
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok", false)) {
                updateTile("切换失败: " + json.optString("error", ""), Tile.STATE_INACTIVE);
                return;
            }
            int slot = json.optInt("slot", -1);
            updateTile(slot >= 0 ? "已切到卡" + (slot + 1) : "已切换", Tile.STATE_ACTIVE);
        } catch (IllegalStateException e) {
            // 目标机还是旧版模块（没有 /api/toggle），退回 status + switch
            if (String.valueOf(e.getMessage()).contains("404")) {
                toggleOverLanLegacy(baseUrl, pin);
            } else {
                updateTile("连接失败", Tile.STATE_INACTIVE);
            }
        } catch (Throwable t) {
            updateTile("连接失败", Tile.STATE_INACTIVE);
        }
    }

    /** 兼容旧版 system_server 服务：先查状态找到另一张卡，再发 switch。 */
    private void toggleOverLanLegacy(String baseUrl, String pin) {
        try {
            String status = Controller.httpGet(Controller.statusUrl(baseUrl, pin), 2500, 4000);
            JSONObject json = new JSONObject(status);
            int current = json.optInt("default", -1);
            JSONArray sims = json.optJSONArray("sims");
            int targetSlot = -1;
            if (sims != null) {
                for (int i = 0; i < sims.length(); i++) {
                    JSONObject sim = sims.getJSONObject(i);
                    if (sim.optInt("subId") != current) {
                        targetSlot = sim.optInt("slot");
                    }
                }
            }
            if (targetSlot < 0) {
                updateTile("没有可切的卡", Tile.STATE_INACTIVE);
                return;
            }
            Controller.httpGet(Controller.switchUrl(baseUrl, pin, targetSlot + 1), 2500, 4000);
            updateTile("已切到卡" + (targetSlot + 1), Tile.STATE_ACTIVE);
        } catch (Throwable t) {
            updateTile("连接失败", Tile.STATE_INACTIVE);
        }
    }

    private void updateTile(String subtitle, int state) {
        main.post(() -> {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            tile.setLabel(getString(R.string.tile_label));
            tile.setSubtitle(subtitle);
            tile.setState(state);
            tile.updateTile();
        });
    }
}
