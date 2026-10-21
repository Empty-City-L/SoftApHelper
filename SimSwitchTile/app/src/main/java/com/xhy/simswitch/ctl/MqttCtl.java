package com.xhy.simswitch.ctl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 控制端 MQTT：卡1/卡2 发 switch 命令，带 id 和重试，收到 ack 后把结果回给界面。
 *
 * 关键点：MQTT 连接可能很慢（TLS + 公网），所以切卡前先用 {@link #ensureConnected}
 * 限时等待；连不上就让调用方立刻回落到局域网 HTTP，而不是干等 10 秒连接超时。
 */
public final class MqttCtl {

    public static final String PREFS = "mqtt";
    private static final String TAG = "MqttCtl";
    private static final int MAX_TRIES = 3;
    private static final long RETRY_MS = 1000L;
    private static final long COMMAND_DEADLINE_MS = 3000L;

    public interface Listener {
        void onAck(boolean ok, String text);
    }

    private static volatile MqttAsyncClient sClient;
    private static volatile Context sContext;
    private static volatile String sDevice = "xiaomi14";
    private static String sHost = "";
    private static String sUser = "";
    private static int sPort = 8883;

    private static volatile Listener sListener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService RETRY =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MqttCtl-Retry");
                thread.setDaemon(true);
                return thread;
            });
    private static ScheduledFuture<?> sRetryFuture;

    private static final Object CONNECT_LOCK = new Object();

    private static volatile String sPendingId = "";
    private static volatile int sPendingSlot = -1;
    private static volatile int sTries = 0;
    private static volatile boolean sPending = false;
    private static volatile boolean sPublished = false;
    private static volatile boolean sConnectFailed = false;
    private static volatile String sStateJson = "";
    private static volatile long sDeadline = 0L;
    private static volatile long sCommandStartedAt = 0L;

    private MqttCtl() {
    }

    public static void setListener(Listener listener) {
        sListener = listener;
    }

    public static boolean isConfigured(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getString("host", "").trim().isEmpty();
    }

    public static synchronized void start(Context context) {
        if (context == null) {
            return;
        }
        sContext = context.getApplicationContext();
        SharedPreferences prefs = sContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String host = prefs.getString("host", "").trim();
        String user = prefs.getString("user", "").trim();
        String pass = prefs.getString("pass", "");
        sDevice = prefs.getString("device", "xiaomi14").trim();
        int port = prefs.getInt("port", 8883);

        // 配置没变就不要重建客户端：autoReconnect 正在重连时把它掐掉反而更慢。
        if (sClient != null && host.equals(sHost) && user.equals(sUser) && port == sPort) {
            return;
        }
        stopLocked(false);
        if (host.isEmpty()) {
            sPending = false;
            cancelRetryLocked();
            return;
        }
        sHost = host;
        sUser = user;
        sPort = port;
        sConnectFailed = false;
        try {
            String clientId = "ctl-" + sDevice + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            sClient = new MqttAsyncClient("ssl://" + host + ":" + port, clientId, new MemoryPersistence());
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
                        sClient.subscribe("simswitch/" + sDevice + "/ack", 1);
                        sClient.subscribe("simswitch/" + sDevice + "/state", 1);
                    } catch (Throwable t) {
                        Log.e(TAG, "subscribe failed", t);
                    }
                    sConnectFailed = false;
                    synchronized (CONNECT_LOCK) {
                        CONNECT_LOCK.notifyAll();
                    }
                    if (sPending) {
                        publishCommand();
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "MQTT lost: " + cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    if (topic.endsWith("/ack")) {
                        handleAck(payload);
                    } else if (topic.endsWith("/state")) {
                        sStateJson = payload;
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            sClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    // 不在连接成功时刷界面：切卡流程里这会和“请求中/已切换”抢同一行提示
                    Log.i(TAG, "MQTT connected");
                }

                @Override
                public void onFailure(IMqttToken token, Throwable exception) {
                    sConnectFailed = true;
                    synchronized (CONNECT_LOCK) {
                        CONNECT_LOCK.notifyAll();
                    }
                    postResult(false, "MQTT 失败: " + exception.getMessage());
                    // 首次连接失败（网络未就绪/账号错误）时清掉客户端，下次操作会重新连接
                    stop();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
            sConnectFailed = true;
            synchronized (CONNECT_LOCK) {
                CONNECT_LOCK.notifyAll();
            }
            postResult(false, "MQTT 连接失败");
        }
    }

    /**
     * 启动连接并限时等待。返回 false 表示这次来不及连上，调用方应走局域网。
     */
    public static boolean ensureConnected(Context context, long timeoutMs) {
        if (context == null || !isConfigured(context)) {
            return false;
        }
        if (isConnected()) {
            return true;
        }
        start(context);
        if (isConnected()) {
            return true;
        }
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (CONNECT_LOCK) {
            while (true) {
                if (isConnected()) {
                    return true;
                }
                if (sConnectFailed) {
                    return false;
                }
                long remain = deadline - SystemClock.elapsedRealtime();
                if (remain <= 0) {
                    return false;
                }
                try {
                    CONNECT_LOCK.wait(Math.min(remain, 100L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    public static String getStateJson() {
        return sStateJson;
    }

    public static boolean isConnected() {
        MqttAsyncClient client = sClient;
        return client != null && client.isConnected();
    }

    /** 本次切卡命令是否已经真正发到 broker（用于判断能否安全回落局域网）。 */
    public static boolean wasPublished() {
        return sPublished;
    }

    public static synchronized void stop() {
        stopLocked(true);
    }

    /**
     * @param clearCommand true 表示用户主动断开，连未完成的切卡命令一起放弃；
     *                     start() 内部重建连接时传 false，避免把正在等待的重试弄丢。
     */
    private static synchronized void stopLocked(boolean clearCommand) {
        if (clearCommand) {
            sPending = false;
            cancelRetryLocked();
        }
        sConnectFailed = true;
        try {
            if (sClient != null) {
                sClient.setCallback(null);
                sClient.disconnectForcibly(0, 1000);
                sClient.close(true);
            }
        } catch (Throwable ignored) {
        }
        sClient = null;
        synchronized (CONNECT_LOCK) {
            CONNECT_LOCK.notifyAll();
        }
    }

    /** 测试按钮：只检查连接状态。 */
    public static void test() {
        if (isConnected()) {
            postResult(true, "MQTT 已连接，可以切卡");
            return;
        }
        postResult(false, "正在连接 MQTT…");
        if (sContext != null) {
            start(sContext);
        }
        MAIN.postDelayed(() -> postResult(isConnected(),
                isConnected() ? "MQTT 已连接，可以切卡" : "MQTT 未连接"), 1500L);
    }

    /** 卡1/卡2 按钮：发 switch 命令，带重试。 */
    public static synchronized void switchTo(int slot) {
        beginCommand(slot);
    }

    /** 磁贴用：切到另一张卡。 */
    public static synchronized void toggle() {
        beginCommand(-1);
    }

    private static void beginCommand(int slot) {
        if (sClient == null && sContext != null) {
            start(sContext);
        }
        sPendingId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        sPendingSlot = slot;
        sTries = 0;
        sPublished = false;
        sDeadline = SystemClock.elapsedRealtime() + COMMAND_DEADLINE_MS;
        sCommandStartedAt = SystemClock.elapsedRealtime();
        sPending = true;
        publishCommand();
        scheduleRetry();
    }

    /** 放弃当前命令：在回落到局域网之前调用，避免 MQTT 恢复后又发一遍造成切两次。 */
    public static synchronized void cancelPending() {
        sPending = false;
        cancelRetryLocked();
    }

    private static void publishCommand() {
        synchronized (MqttCtl.class) {
            try {
                if (!sPending) {
                    return;
                }
                MqttAsyncClient client = sClient;
                if (client == null || !client.isConnected()) {
                    return;
                }
                SharedPreferences prefs = sContext.getSharedPreferences("ctl", Context.MODE_PRIVATE);
                JSONObject command = new JSONObject();
                command.put("id", sPendingId);
                command.put("pin", prefs.getString("pin", ""));
                if (sPendingSlot > 0) {
                    command.put("action", "switch");
                    command.put("slot", sPendingSlot);
                } else {
                    command.put("action", "toggle");
                }
                MqttMessage message = new MqttMessage(command.toString().getBytes("UTF-8"));
                message.setQos(1);
                client.publish("simswitch/" + sDevice + "/cmd", message);
                sTries++;
                sPublished = true;
                if (sPendingSlot > 0) {
                    postResult(true, "正在切到卡" + sPendingSlot + "…（第 " + sTries + " 次）");
                }
            } catch (Throwable t) {
                Log.e(TAG, "publish failed", t);
            }
        }
    }

    private static void scheduleRetry() {
        cancelRetryLocked();
        sRetryFuture = RETRY.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (MqttCtl.class) {
                    if (!sPending) {
                        return;
                    }
                    if (SystemClock.elapsedRealtime() >= sDeadline) {
                        sPending = false;
                        long cost = SystemClock.elapsedRealtime() - sCommandStartedAt;
                        postResult(false, "目标机无响应（耗时 " + cost + " ms）");
                        return;
                    }
                    if (sTries >= MAX_TRIES) {
                        // 已经发满次数，剩余时间只等 ack；到 sDeadline 还没回就报无响应
                        scheduleRetry();
                        return;
                    }
                    if (!isConnected()) {
                        // 连接断了就重新发起；等待下一轮，不消耗重试次数
                        if (sContext != null) {
                            start(sContext);
                        }
                    } else {
                        publishCommand();
                    }
                    scheduleRetry();
                }
            }
        }, RETRY_MS, TimeUnit.MILLISECONDS);
    }

    private static void cancelRetryLocked() {
        if (sRetryFuture != null) {
            sRetryFuture.cancel(false);
            sRetryFuture = null;
        }
    }

    private static synchronized void handleAck(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String id = json.optString("id", "");
            if (!sPending || !id.equals(sPendingId)) {
                return;
            }
            sPending = false;
            cancelRetryLocked();
            boolean ok = json.optBoolean("ok", false);
            int current = json.optInt("default", -1);
            String error = json.optString("error", "");
            long cost = SystemClock.elapsedRealtime() - sCommandStartedAt;
            if (ok) {
                postResult(true, "切换成功，当前默认：" + describeSub(current) + "，耗时 " + cost + " ms");
            } else {
                postResult(false, "切换失败: " + error + "，当前默认：" + describeSub(current) + "，耗时 " + cost + " ms");
            }
        } catch (Throwable t) {
            Log.e(TAG, "bad ack", t);
        }
    }

    private static String describeSub(int subId) {
        try {
            JSONObject state = new JSONObject(sStateJson);
            JSONArray sims = state.optJSONArray("sims");
            if (sims != null) {
                for (int i = 0; i < sims.length(); i++) {
                    JSONObject sim = sims.getJSONObject(i);
                    if (sim.optInt("subId") == subId) {
                        return "卡" + (sim.optInt("slot") + 1) + " " + sim.optString("name")
                                + "（subId=" + subId + "）";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "subId=" + subId;
    }

    private static void postResult(boolean ok, String text) {
        MAIN.post(() -> {
            Listener listener = sListener;
            if (listener != null) {
                listener.onAck(ok, text);
            }
        });
    }
}
