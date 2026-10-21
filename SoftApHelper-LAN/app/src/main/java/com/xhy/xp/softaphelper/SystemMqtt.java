package com.xhy.xp.softaphelper;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * 跑在 system_server 里的 MQTT：不受 App 冻结影响，切卡直接调用系统 API。
 */
public final class SystemMqtt {

    private static final String TAG = "SystemMqtt";

    private static MqttAsyncClient sClient;
    private static Context sContext;
    private static String sHost = "";
    private static String sUser = "";
    private static String sPass = "";
    private static String sDevice = "xiaomi14";
    private static String sPin = "";

    private static String sLastId = "";
    private static String sLastAck = "";

    private SystemMqtt() {
    }

    public static synchronized void apply(Context context, String host, String user, String pass,
                                          String device, String pin) {
        if (context == null) {
            return;
        }
        boolean same = sClient != null && sClient.isConnected()
                && host.equals(sHost) && user.equals(sUser) && pass.equals(sPass)
                && device.equals(sDevice) && pin.equals(sPin);
        if (same) {
            return;
        }
        stop();
        sContext = context;
        sHost = host;
        sUser = user;
        sPass = pass;
        sDevice = device;
        sPin = pin;
        if (host.isEmpty()) {
            Log.i(TAG, "MQTT not configured");
            return;
        }
        try {
            String clientId = "sys-" + device + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            sClient = new MqttAsyncClient("ssl://" + host + ":8883", clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            if (!user.isEmpty()) {
                options.setUserName(user);
                options.setPassword(pass.toCharArray());
            }
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(20);
            options.setMaxReconnectDelay(1000);

            sClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    try {
                        sClient.subscribe("simswitch/" + sDevice + "/cmd", 1);
                        publishState();
                        Log.i(TAG, "connected & subscribed");
                    } catch (Throwable t) {
                        Log.e(TAG, "subscribe failed", t);
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "connection lost: " + cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handle(new String(message.getPayload()));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            sClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    Log.i(TAG, "connect success");
                }

                @Override
                public void onFailure(IMqttToken token, Throwable exception) {
                    Log.e(TAG, "connect failure: " + exception);
                    // 首次连接失败（比如开机时网络还没就绪），关掉让循环下一轮重试
                    stop();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
        }
    }

    public static synchronized void stop() {
        try {
            if (sClient != null) {
                sClient.setCallback(null);
                sClient.disconnectForcibly(0, 1000);
                sClient.close(true);
            }
        } catch (Throwable ignored) {
        }
        sClient = null;
    }

    private static void handle(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String id = json.optString("id", "");
            if (!id.isEmpty() && id.equals(sLastId)) {
                publish("simswitch/" + sDevice + "/ack", sLastAck);
                return;
            }
            if (sPin.isEmpty() || !sPin.equals(json.optString("pin", ""))) {
                ack(id, false, "pin", -1, getDefaultSub());
                return;
            }
            String action = json.optString("action", "toggle");
            int targetSlot;
            if ("switch".equals(action)) {
                targetSlot = json.optInt("slot", 1) - 1;
            } else {
                targetSlot = otherSlot(getDefaultSub());
            }
            if (targetSlot < 0) {
                ack(id, false, "no-sim", -1, getDefaultSub());
                return;
            }
            sLastId = id;
            long start = SystemClock.elapsedRealtime();
            boolean ok = switchToSlot(targetSlot);
            long cost = SystemClock.elapsedRealtime() - start;
            ack(id, ok, ok ? "" : "switch-failed", targetSlot, getDefaultSub());
            Log.i(TAG, "switch slot=" + targetSlot + " ok=" + ok + " cost=" + cost + "ms");
            publishState();
        } catch (Throwable t) {
            Log.e(TAG, "handle failed", t);
            ack("", false, "error", -1, getDefaultSub());
        }
    }

    private static int otherSlot(int currentSub) {
        try {
            SubscriptionManager sm = subscriptionManager();
            if (sm == null) {
                return -1;
            }
            for (SubscriptionInfo info : sm.getActiveSubscriptionInfoList()) {
                if (info.getSubscriptionId() != currentSub) {
                    return info.getSimSlotIndex();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "otherSlot failed", t);
        }
        return -1;
    }

    private static SubscriptionManager subscriptionManager() {
        return (SubscriptionManager) sContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    private static boolean switchToSlot(int slot) {
        try {
            SubscriptionManager sm = subscriptionManager();
            if (sm == null) {
                return false;
            }
            int subId = -1;
            List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
            if (infos != null) {
                for (SubscriptionInfo info : infos) {
                    if (info.getSimSlotIndex() == slot) {
                        subId = info.getSubscriptionId();
                    }
                }
            }
            if (subId < 0) {
                return false;
            }
            Method method = SubscriptionManager.class.getDeclaredMethod("setDefaultDataSubId", int.class);
            method.setAccessible(true);
            method.invoke(sm, subId);
            long deadline = SystemClock.elapsedRealtime() + 800L;
            while (true) {
                if (getDefaultSub() == subId) {
                    return true;
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return false;
                }
                Thread.sleep(25L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "switchToSlot failed", t);
            return false;
        }
    }

    private static int getDefaultSub() {
        try {
            return Settings.Global.getInt(sContext.getContentResolver(), "multi_sim_data_call", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void ack(String id, boolean ok, String error, int slot, int current) {
        try {
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("ok", ok);
            out.put("slot", slot);
            out.put("default", current);
            out.put("error", error);
            out.put("ts", System.currentTimeMillis());
            sLastAck = out.toString();
            publish("simswitch/" + sDevice + "/ack", sLastAck);
        } catch (Throwable t) {
            Log.e(TAG, "ack failed", t);
        }
    }

    private static void publishState() {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("default", getDefaultSub());
            JSONArray sims = new JSONArray();
            SubscriptionManager sm = subscriptionManager();
            if (sm != null) {
                for (SubscriptionInfo info : sm.getActiveSubscriptionInfoList()) {
                    JSONObject item = new JSONObject();
                    item.put("slot", info.getSimSlotIndex());
                    item.put("subId", info.getSubscriptionId());
                    item.put("name", String.valueOf(info.getCarrierName()));
                    sims.put(item);
                }
            }
            out.put("sims", sims);
            MqttMessage message = new MqttMessage(out.toString().getBytes("UTF-8"));
            message.setQos(1);
            message.setRetained(true);
            if (sClient != null && sClient.isConnected()) {
                sClient.publish("simswitch/" + sDevice + "/state", message);
            }
        } catch (Throwable t) {
            Log.e(TAG, "state failed", t);
        }
    }

    private static void publish(String topic, String payload) {
        try {
            if (sClient != null && sClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.getBytes("UTF-8"));
                message.setQos(1);
                sClient.publish(topic, message);
            }
        } catch (Throwable t) {
            Log.e(TAG, "publish failed", t);
        }
    }
}
