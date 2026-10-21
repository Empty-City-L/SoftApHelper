package com.xhy.xp.softaphelper;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 跑在 system_server 里的局域网 HTTP 服务：锁屏、App 未打开时也能访问。
 * 端口和接口与旧版 App 内服务一致。
 *
 * 性能要点：
 * - accept 之后立刻丢给线程池，慢请求（切卡）不会把后来的请求堵在 accept 循环里；
 * - /api/toggle 一次请求就能切到另一张卡，省掉控制器先查 status 再 switch 的往返；
 * - TCP_NODELAY，避免小响应被 Nagle 延迟。
 */
public final class SystemHttp {

    public static final int PORT = 8080;
    private static final String TAG = "SystemHttp";

    private static final Object SWITCH_LOCK = new Object();
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SystemHttp-Worker");
        thread.setDaemon(true);
        return thread;
    });

    private static ServerSocket sServer;
    private static Thread sThread;
    private static volatile Context sContext;
    private static volatile String sPin = "";

    private SystemHttp() {
    }

    public static synchronized void apply(Context context, String pin) {
        if (context == null) {
            return;
        }
        sContext = context;
        sPin = pin == null ? "" : pin;
        if (sServer == null || sServer.isClosed()) {
            start();
        }
    }

    private static void start() {
        final ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT), 32);
        } catch (Throwable t) {
            Log.e(TAG, "bind " + PORT + " failed", t);
            sServer = null;
            return;
        }
        sServer = server;
        sThread = new Thread(() -> acceptLoop(server), "SystemHttp");
        sThread.setDaemon(true);
        sThread.start();
        Log.i(TAG, "listening on " + PORT);
    }

    private static void acceptLoop(ServerSocket server) {
        while (true) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (Throwable t) {
                Log.w(TAG, "accept stopped: " + t);
                try {
                    server.close();
                } catch (Throwable ignored) {
                }
                synchronized (SystemHttp.class) {
                    if (sServer == server) {
                        sServer = null;
                    }
                }
                return;
            }
            try {
                socket.setTcpNoDelay(true);
                WORKERS.execute(() -> serve(socket));
            } catch (Throwable t) {
                Log.w(TAG, "dispatch failed: " + t);
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void serve(Socket socket) {
        try {
            handle(socket);
        } catch (Throwable ignored) {
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void handle(Socket socket) throws Exception {
        socket.setSoTimeout(4000);
        socket.setTcpNoDelay(true);
        long startedAt = SystemClock.elapsedRealtime();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null) {
            return;
        }
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // ignore headers
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            respond(socket, 400, "text/plain; charset=utf-8", "bad request");
            return;
        }
        String target = parts[1];
        String path = target;
        Map<String, String> params = new LinkedHashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            }
        }

        int code = 200;
        String type = "application/json; charset=utf-8";
        String body;
        if ("/".equals(path) || "/index.html".equals(path)) {
            type = "text/html; charset=utf-8";
            body = HTML;
        } else if (sPin.isEmpty() || !sPin.equals(params.get("pin"))) {
            code = 401;
            body = "{\"ok\":false,\"error\":\"pin\"}";
        } else if ("/api/status".equals(path)) {
            body = statusJson();
        } else if ("/api/toggle".equals(path)) {
            body = switchJson(true, null);
        } else if ("/api/switch".equals(path)) {
            body = switchJson(false, params.get("slot"));
        } else {
            code = 404;
            type = "text/plain; charset=utf-8";
            body = "not found";
        }
        respond(socket, code, type, body);
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            Log.i(TAG, path + " " + code + " "
                    + (SystemClock.elapsedRealtime() - startedAt) + "ms");
        }
    }

    private static SubscriptionManager subscriptionManager() {
        return (SubscriptionManager) sContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    private static int getDefaultSub() {
        try {
            return Settings.Global.getInt(sContext.getContentResolver(), "multi_sim_data_call", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String statusJson() {
        JSONObject out = new JSONObject();
        try {
            int def = getDefaultSub();
            out.put("ok", true);
            out.put("default", def);
            out.put("ip", localIp());
            out.put("port", PORT);
            JSONArray sims = new JSONArray();
            SubscriptionManager sm = subscriptionManager();
            List<SubscriptionInfo> infos = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (infos != null) {
                for (SubscriptionInfo info : infos) {
                    JSONObject item = new JSONObject();
                    item.put("slot", info.getSimSlotIndex());
                    item.put("subId", info.getSubscriptionId());
                    item.put("name", String.valueOf(info.getCarrierName()));
                    sims.put(item);
                }
            }
            out.put("sims", sims);
        } catch (Throwable t) {
            Log.e(TAG, "status failed", t);
        }
        return out.toString();
    }

    /**
     * @param toggle true 表示切到“另一张卡”，false 表示切到 slotText 指定的卡槽
     */
    private static String switchJson(boolean toggle, String slotText) {
        // 串行化切卡：连点两下时第二次会等第一次完成后再根据新的默认卡计算目标，
        // 避免两个请求同时读到同一个 currentSub 而都切到同一张卡。
        synchronized (SWITCH_LOCK) {
            JSONObject out = new JSONObject();
            try {
                int current = getDefaultSub();
                int slot;
                if (toggle) {
                    slot = otherSlot(current);
                } else {
                    slot = Integer.parseInt(slotText) - 1;
                }
                if (slot < 0) {
                    out.put("ok", false);
                    out.put("error", "no sim in slot");
                    return out.toString();
                }
                int subId = subIdForSlot(slot);
                if (subId < 0) {
                    out.put("ok", false);
                    out.put("error", "no sim in slot");
                    return out.toString();
                }
                boolean ok = switchToSub(subId);
                out.put("ok", ok);
                out.put("slot", slot);
                out.put("subId", subId);
                out.put("default", getDefaultSub());
                if (!ok) {
                    out.put("error", "switch-failed");
                }
            } catch (Throwable t) {
                try {
                    out.put("ok", false);
                    out.put("error", "bad request");
                } catch (Throwable ignored) {
                }
            }
            return out.toString();
        }
    }

    private static int otherSlot(int currentSub) {
        try {
            SubscriptionManager sm = subscriptionManager();
            if (sm != null) {
                List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
                if (infos != null) {
                    for (SubscriptionInfo info : infos) {
                        if (info.getSubscriptionId() != currentSub) {
                            return info.getSimSlotIndex();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "otherSlot failed", t);
        }
        return -1;
    }

    private static int subIdForSlot(int slot) {
        try {
            SubscriptionManager sm = subscriptionManager();
            if (sm != null) {
                List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
                if (infos != null) {
                    for (SubscriptionInfo info : infos) {
                        if (info.getSimSlotIndex() == slot) {
                            return info.getSubscriptionId();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static boolean switchToSub(int subId) {
        try {
            SubscriptionManager sm = subscriptionManager();
            if (sm == null) {
                return false;
            }
            java.lang.reflect.Method method =
                    SubscriptionManager.class.getDeclaredMethod("setDefaultDataSubId", int.class);
            method.setAccessible(true);
            method.invoke(sm, subId);

            // 正常情况下设置会立刻更新，这里只做短轮询确认；失败最多等 800ms，
            // 不再占用 HTTP 工作线程太久。
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
            Log.e(TAG, "switch failed", t);
            return false;
        }
    }

    private static String localIp() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "0.0.0.0";
    }

    private static void respond(Socket socket, int code, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : (code == 401 ? "Unauthorized" : (code == 404 ? "Not Found" : "Bad Request"));
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static final String HTML = ""
            + "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>SimSwitch</title>"
            + "<style>body{font-family:system-ui,sans-serif;padding:20px;background:#f5f6f8}"
            + ".card{background:#fff;border-radius:12px;padding:16px;margin-bottom:12px}"
            + "button{border:0;border-radius:10px;padding:12px 16px;font-size:15px;background:#1a73e8;color:#fff;margin:6px 8px 0 0}"
            + "input{border:1px solid #ccc;border-radius:8px;padding:10px;width:90px}"
            + "pre{white-space:pre-wrap}</style></head><body>"
            + "<h2>SimSwitch</h2><div class=\"card\"><input id=\"pin\" type=\"password\" placeholder=\"PIN\">"
            + "<button onclick=\"load()\">刷新</button></div>"
            + "<div class=\"card\"><pre id=\"st\">…</pre></div>"
            + "<div class=\"card\"><button onclick=\"tog()\">切到另一张卡</button>"
            + "<button onclick=\"sw(1)\">卡1</button><button onclick=\"sw(2)\">卡2</button></div>"
            + "<script>const p=()=>document.getElementById('pin').value;"
            + "async function load(){const r=await fetch('/api/status?pin='+p());"
            + "if(r.status===401){document.getElementById('st').textContent='PIN 不对';return;}"
            + "const j=await r.json();let s='';for(const x of j.sims){s+='卡槽'+(x.slot+1)+' '+x.name+' subId='+x.subId+(x.subId===j.default?' ← 当前':'')+'\\n';}"
            + "s+='默认 '+j.default;document.getElementById('st').textContent=s;}"
            + "async function sw(n){await fetch('/api/switch?pin='+p()+'&slot='+n);await load();}"
            + "async function tog(){await fetch('/api/toggle?pin='+p());await load();}load();"
            + "</script></body></html>";
}
