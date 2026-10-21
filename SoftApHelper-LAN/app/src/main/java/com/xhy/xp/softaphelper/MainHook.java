package com.xhy.xp.softaphelper;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

import android.content.ContentResolver;
import android.content.IntentFilter;
import android.content.Context;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.Uri;
import android.os.Build;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "SoftApHelper";

    private static final String CLASS_NAME_P = "com.android.server.connectivity.tethering.TetherInterfaceStateMachine";
    private static final String CLASS_NAME_Q = "android.net.ip.IpServer";
    private static final String METHOD_NAME_P_Q = "getRandomWifiIPv4Address";
    private static final String METHOD_NAME_R = "requestIpv4Address";
    private static final String CALLER_METHOD = "configureIPv4";

    private static final String DEFAULT_WIFI_IP = "192.168.43.1";
    private static final String DEFAULT_USB_IP = "192.168.42.1";
    private static final String DEFAULT_BT_IP = "192.168.44.1";
    private static final String DEFAULT_P2P_IP = "192.168.49.1";
    private static final String DEFAULT_ETHERNET_IP = "192.168.45.1";

    // TetheringType
    private static final int TETHERING_INVALID = -1;
    public static final int TETHERING_WIFI = 0;
    private static final int TETHERING_USB = 1;
    private static final int TETHERING_BLUETOOTH = 2;
    private static final int TETHERING_WIFI_P2P = 3;
    private static final int TETHERING_ETHERNET = 5;

    private static final long CONFIG_TTL_MS = 3000L;
    private static final long CONFLICT_BACKOFF_MS = 60000L;

    private static final Object CONFIG_LOCK = new Object();

    private static volatile Map<Integer, String> sAddressMap = buildAddressMap(
            DEFAULT_WIFI_IP, DEFAULT_USB_IP, DEFAULT_BT_IP, DEFAULT_P2P_IP, DEFAULT_ETHERNET_IP);
    private static volatile long sLastConfigLoad = 0L;

    // Android 16+: IpServer 不再持有 mPrivateAddressCoordinator，冲突通过异步回调通知。
    // 收到冲突后在一段时间内不再强制，避免 "强制 -> 冲突 -> 重启 -> 再强制" 的循环。
    private static final Map<Integer, Long> sConflictBackoff = new ConcurrentHashMap<>();
    private static final Set<Class<?>> sConflictHooked = Collections.synchronizedSet(new HashSet<Class<?>>());

    // 共享常驻保活（以太网 / USB）
    private static volatile boolean sEthAuto = true;
    private static volatile boolean sUsbAuto = true;
    private static volatile boolean sEthActive = false;
    private static volatile boolean sUsbActive = false;
    private static volatile boolean sUsbConnected = false;
    private static volatile boolean sShareLoopStarted = false;
    private static volatile boolean sMqttLoopStarted = false;
    private static volatile long sShareConfigAt = 0L;
    private static volatile long sEthNextTry = 0L;
    private static volatile long sUsbNextTry = 0L;

    private static Map<Integer, String> buildAddressMap(String wifi, String usb, String bt, String p2p, String eth) {
        Map<Integer, String> map = new LinkedHashMap<>();
        map.put(TETHERING_WIFI, wifi + "/24");
        map.put(TETHERING_USB, usb + "/24");
        map.put(TETHERING_BLUETOOTH, bt + "/24");
        map.put(TETHERING_WIFI_P2P, p2p + "/24");
        map.put(TETHERING_ETHERNET, eth + "/24");
        return Collections.unmodifiableMap(map);
    }

    private static String sanitizeIpv4(String value, String def) {
        return Config.isValidIpv4(value) ? value.trim() : def;
    }

    private static void log(String message) {
        XposedBridge.log("[" + TAG + "] " + message);
    }

    private static boolean isTargetProcess(String processName) {
        if (processName == null) {
            return false;
        }
        return "android".equals(processName)
                || processName.contains("tethering")
                || processName.contains("networkstack");
    }

    private static String hostAddress(int tetheringType) {
        String cidr = sAddressMap.get(tetheringType);
        if (cidr == null) {
            return null;
        }
        int slash = cidr.indexOf('/');
        return slash > 0 ? cidr.substring(0, slash) : cidr;
    }

    private static Context resolveContext(ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = classLoader.loadClass("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            if (activityThread != null) {
                // 普通 app 进程（比如 com.android.networkstack.tethering）靠这个拿 Application
                Object application = activityThreadClass.getMethod("getApplication").invoke(activityThread);
                if (application instanceof Context) {
                    return (Context) application;
                }
                // system_server 里 getSystemContext() 才有值
                Object systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
                if (systemContext instanceof Context) {
                    return (Context) systemContext;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void loadConfig(ClassLoader classLoader) {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastConfigLoad < CONFIG_TTL_MS) {
            return;
        }
        synchronized (CONFIG_LOCK) {
            now = SystemClock.elapsedRealtime();
            if (now - sLastConfigLoad < CONFIG_TTL_MS) {
                return;
            }
            sLastConfigLoad = now;
            try {
                Bundle bundle = readConfigBundle(classLoader);
                if (bundle == null) {
                    // 拿不到 context 或 provider 未就绪，稍后重试
                    sLastConfigLoad = now - CONFIG_TTL_MS + 1000L;
                    return;
                }
                sAddressMap = buildAddressMap(
                        sanitizeIpv4(getStr(bundle, Config.KEY_WIFI_IP, DEFAULT_WIFI_IP), DEFAULT_WIFI_IP),
                        sanitizeIpv4(getStr(bundle, Config.KEY_USB_IP, DEFAULT_USB_IP), DEFAULT_USB_IP),
                        sanitizeIpv4(getStr(bundle, Config.KEY_BT_IP, DEFAULT_BT_IP), DEFAULT_BT_IP),
                        sanitizeIpv4(getStr(bundle, Config.KEY_P2P_IP, DEFAULT_P2P_IP), DEFAULT_P2P_IP),
                        sanitizeIpv4(getStr(bundle, Config.KEY_ETHERNET_IP, DEFAULT_ETHERNET_IP), DEFAULT_ETHERNET_IP));

            } catch (Throwable t) {
                log("loadConfig failed: " + t);
                sLastConfigLoad = now - CONFIG_TTL_MS + 1000L;
            }
        }
    }

    private static Bundle readConfigBundle(ClassLoader classLoader) {
        Context context = resolveContext(classLoader);
        if (context == null) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        return resolver.call(Uri.parse("content://" + ConfigProvider.AUTHORITY),
                ConfigProvider.METHOD_GET_ALL, null, null);
    }

    private static String getStr(Bundle bundle, String key, String def) {
        String value = bundle.getString(key);
        return (value != null && !value.isEmpty()) ? value : def;
    }

    private boolean isConflictPrefix(Object coordinator, IpPrefix prefix) throws Exception {
        Class<?> coordinatorClass = coordinator.getClass();
        // Android 12+
        Method getConflictPrefix = ReflectUtils.findMethod(coordinatorClass, "getConflictPrefix");
        if (getConflictPrefix != null) {
            return getConflictPrefix.invoke(coordinator, prefix) != null;
        }

        // Android 11
        Method isDownstreamPrefixInUse = ReflectUtils.findMethod(coordinatorClass, "isDownstreamPrefixInUse");
        Method isConflictWithUpstream = ReflectUtils.findMethod(coordinatorClass, "isConflictWithUpstream");
        if (isDownstreamPrefixInUse != null && isConflictWithUpstream != null) {
            return (boolean) isDownstreamPrefixInUse.invoke(coordinator, prefix) ||
                    (boolean) isConflictWithUpstream.invoke(coordinator, prefix);
        }

        log("[isConflictPrefix] method not found in " + coordinatorClass.getName());
        return false;
    }

    private static boolean isConflictBackoff(int tetheringType) {
        long now = SystemClock.elapsedRealtime();
        Long all = sConflictBackoff.get(TETHERING_INVALID);
        if (all != null && all > now) {
            return true;
        }
        Long own = sConflictBackoff.get(tetheringType);
        return own != null && own > now;
    }

    private static void ensureConflictHook(Class<?> ipServerClass, Object ipServer) {
        try {
            Field requestField = ReflectUtils.findField(ipServerClass, "mIpv4PrefixRequest");
            if (requestField == null) {
                return;
            }
            Object callback = requestField.get(ipServer);
            if (callback == null) {
                return;
            }
            Class<?> callbackClass = callback.getClass();
            if (!sConflictHooked.add(callbackClass)) {
                return;
            }
            XposedBridge.hookAllMethods(callbackClass, "onIpv4PrefixConflict", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object outer = findOuterInstance(param.thisObject);
                        int tetheringType = TETHERING_INVALID;
                        if (outer != null) {
                            Field typeField = ReflectUtils.findField(outer.getClass(), "mInterfaceType");
                            if (typeField != null) {
                                tetheringType = typeField.getInt(outer);
                            }
                        }
                        sConflictBackoff.put(tetheringType,
                                SystemClock.elapsedRealtime() + CONFLICT_BACKOFF_MS);
                        log("prefix conflict reported (type " + tetheringType + "), back off forcing");
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static Object findOuterInstance(Object inner) throws IllegalAccessException {
        Class<?> klass = inner.getClass();
        while (klass != null) {
            for (Field field : klass.getDeclaredFields()) {
                if (field.isSynthetic() && field.getName().startsWith("this$")) {
                    field.setAccessible(true);
                    return field.get(inner);
                }
            }
            klass = klass.getSuperclass();
        }
        return null;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!isTargetProcess(lpparam.processName)) {
            return;
        }
        final ClassLoader classLoader = lpparam.classLoader;

        hookFixedIp(classLoader, lpparam.processName);
        if ("android".equals(lpparam.processName)) {
            startTetheringKeepAlive(classLoader);
            startSystemMqttLoop(classLoader);
        }
    }

    private static void startSystemMqttLoop(final ClassLoader classLoader) {
        if (sMqttLoopStarted) {
            return;
        }
        sMqttLoopStarted = true;
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Bundle bundle = readConfigBundle(classLoader);
                    Context context = resolveContext(classLoader);
                    if (bundle != null && context != null) {
                        SystemMqtt.apply(context,
                                bundle.getString("mqtt_host", ""),
                                bundle.getString("mqtt_user", ""),
                                bundle.getString("mqtt_pass", ""),
                                bundle.getString("mqtt_device", "xiaomi14"),
                                bundle.getString("mqtt_pin", ""));
                        SystemHttp.apply(context, bundle.getString("mqtt_pin", ""));
                    }
                } catch (Throwable t) {
                    log("mqtt loop: " + t);
                }
                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "SystemMqttLoop");
        thread.setDaemon(true);
        thread.start();
    }

    private static void readShareFlags(ClassLoader classLoader) {
        long now = SystemClock.elapsedRealtime();
        if (now - sShareConfigAt < 30000L) {
            return;
        }
        sShareConfigAt = now;
        try {
            Bundle bundle = readConfigBundle(classLoader);
            if (bundle != null) {
                sEthAuto = !"false".equals(getStr(bundle, Config.KEY_ETH_AUTO, "true"));
                sUsbAuto = !"false".equals(getStr(bundle, Config.KEY_USB_AUTO, "true"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasEthernetInterface() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = networkInterface.getName();
                if (name != null && name.startsWith("eth") && networkInterface.isUp()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String typeName(int type) {
        return type == TETHERING_ETHERNET ? "ethernet" : "usb";
    }

    private static void startTethering(TetheringManager tetheringManager, Executor executor, final int type) {
        try {
            TetheringManager.TetheringRequest request =
                    new TetheringManager.TetheringRequest.Builder(type).build();
            tetheringManager.startTethering(request, executor, new TetheringManager.StartTetheringCallback() {
                @Override
                public void onTetheringStarted() {
                    log("startTethering(" + typeName(type) + ") requested");
                }

                @Override
                public void onTetheringFailed(int error) {
                    if (type == TETHERING_ETHERNET) {
                        sEthNextTry = SystemClock.elapsedRealtime() + 60000L;
                    } else {
                        sUsbNextTry = SystemClock.elapsedRealtime() + 60000L;
                    }
                    log("startTethering(" + typeName(type) + ") failed: " + error);
                }
            });
        } catch (Throwable t) {
            if (type == TETHERING_ETHERNET) {
                sEthNextTry = SystemClock.elapsedRealtime() + 60000L;
            } else {
                sUsbNextTry = SystemClock.elapsedRealtime() + 60000L;
            }
            log("startTethering(" + typeName(type) + ") threw: " + t);
        }
    }

    private static void startTetheringKeepAlive(final ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || sShareLoopStarted) {
            return;
        }
        sShareLoopStarted = true;
        try {
            final Context context = resolveContext(classLoader);
            if (context == null) {
                sShareLoopStarted = false;
                return;
            }
            final TetheringManager tetheringManager = context.getSystemService(TetheringManager.class);
            if (tetheringManager == null) {
                log("TetheringManager unavailable, share keep-alive disabled");
                return;
            }


            HandlerThread thread = new HandlerThread("SoftApHelper-Share");
            thread.start();
            final Handler handler = new Handler(thread.getLooper());
            final Executor executor = context.getMainExecutor();

            tetheringManager.registerTetheringEventCallback(executor,
                    new TetheringManager.TetheringEventCallback() {
                        @Override
                        public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
                            boolean eth = false;
                            boolean usb = false;
                            for (TetheringInterface tetheringInterface : interfaces) {
                                if (tetheringInterface.getType() == TETHERING_ETHERNET) {
                                    eth = true;
                                } else if (tetheringInterface.getType() == TETHERING_USB) {
                                    usb = true;
                                }
                            }
                            if (eth != sEthActive) {
                                sEthActive = eth;
                                log("ethernet tethering " + (eth ? "active" : "inactive"));
                            }
                            if (usb != sUsbActive) {
                                sUsbActive = usb;
                                log("usb tethering " + (usb ? "active" : "inactive"));
                            }
                        }
                    });

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        readShareFlags(classLoader);
                        long now = SystemClock.elapsedRealtime();
                        if (sEthAuto && !sEthActive && hasEthernetInterface() && now >= sEthNextTry) {
                            startTethering(tetheringManager, executor, TETHERING_ETHERNET);
                        }
                        if (sUsbAuto && !sUsbActive && now >= sUsbNextTry) {
                            // 没插线时会失败，退避 60s 再试；插上线后最多一分钟内会自动开起来
                            sUsbNextTry = now + 15000L;
                            startTethering(tetheringManager, executor, TETHERING_USB);
                        }
                    } catch (Throwable t) {
                        log("share keep-alive error: " + t);
                    }
                    handler.postDelayed(this, 10000);
                }
            }, 30000);
            log("share keep-alive started");
        } catch (Throwable t) {
            log("start share keep-alive failed: " + t);
        }
    }
    private static Integer readTetheringType(Class<?> klass, Object instance) {
        String[] names = {"mInterfaceType", "mTetheringType", "mType"};
        for (String name : names) {
            Field field = ReflectUtils.findField(klass, name);
            if (field != null) {
                try {
                    return field.getInt(instance);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private void hookFixedIp(final ClassLoader classLoader, final String processName) {
        final String className = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ? CLASS_NAME_P : CLASS_NAME_Q;
        final String methodName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? METHOD_NAME_R : METHOD_NAME_P_Q;

        // 安卓9-10
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P
                || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            try {
                findAndHookMethod(className, classLoader, methodName, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        loadConfig(classLoader);
                        String address = hostAddress(TETHERING_WIFI);
                        return address != null ? address : DEFAULT_WIFI_IP;
                    }
                });
                log("hooked " + methodName + " in " + processName);
            } catch (Throwable t) {
                log("hook " + methodName + " failed: " + t);
            }
            return;
        }

        // 安卓11+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("unsupported SDK " + Build.VERSION.SDK_INT);
            return;
        }

        try {
            final Constructor<?> linkAddressCtor = LinkAddress.class.getDeclaredConstructor(String.class);
            final Constructor<?> ipPrefixCtor = IpPrefix.class.getDeclaredConstructor(String.class);

            final Class<?> klass = classLoader.loadClass(className);
            java.util.List<Method> methods = new java.util.ArrayList<>();
            for (Method candidate : klass.getDeclaredMethods()) {
                if (methodName.equals(candidate.getName())
                        && candidate.getReturnType() == LinkAddress.class) {
                    methods.add(candidate);
                }
            }
            if (methods.isEmpty()) {
                log("[" + methodName + "] not found in " + klass.getName());
                return;
            }

            XC_MethodHook fixedIpHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        loadConfig(classLoader);

                        Integer typeValue = readTetheringType(klass, param.thisObject);
                        if (typeValue == null) {
                            log("interface type field not found in " + klass.getName() + ", skip");
                            return;
                        }
                        int tetheringType = typeValue;
                        String cidr = sAddressMap.get(tetheringType);
                        if (cidr == null) {
                            return;
                        }
                        if (!StackUtils.isCallingFrom(className, CALLER_METHOD)) {
                            return;
                        }

                        ensureConflictHook(klass, param.thisObject);

                        if (isConflictBackoff(tetheringType)) {
                            log("skip forcing " + cidr + " for type " + tetheringType
                                    + " (prefix conflict recently reported)");
                            return;
                        }

                        LinkAddress linkAddress = (LinkAddress) linkAddressCtor.newInstance(cidr);
                        IpPrefix prefix = (IpPrefix) ipPrefixCtor.newInstance(cidr);

                        Field coordinatorField = ReflectUtils.findField(klass, "mPrivateAddressCoordinator");
                        if (coordinatorField != null) {
                            Object coordinator = coordinatorField.get(param.thisObject);
                            if (coordinator != null && isConflictPrefix(coordinator, prefix)) {
                                log(cidr + " isConflictPrefix, do not replace");
                                return;
                            }
                        }
                        // Android 16+ 没有 mPrivateAddressCoordinator 字段，只能跳过同步冲突检测，
                        // 由 ensureConflictHook 的异步回调做兜底。
                        param.setResult(linkAddress);
                        log("set " + cidr + " for type " + tetheringType);
                    } catch (Throwable t) {
                        log("fixed ip hook error: " + Log.getStackTraceString(t));
                    }
                }
            };
            for (Method method : methods) {
                XposedBridge.hookMethod(method, fixedIpHook);
            }
            log("hooked " + methodName + " x" + methods.size() + " in " + processName);
        } catch (Throwable t) {
            log("hook fixed ip failed: " + Log.getStackTraceString(t));
        }
    }

}
