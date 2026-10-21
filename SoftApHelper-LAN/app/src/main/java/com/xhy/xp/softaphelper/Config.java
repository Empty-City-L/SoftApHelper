package com.xhy.xp.softaphelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Config {

    public static final String PREF_NAME = "softap_helper_config";
    public static final String PREF_MQTT = "mqtt";
    public static final String PREF_PIN = "simswitch";

    // 各接口固定 IP（不带 /24 前缀，仅地址）
    public static final String KEY_WIFI_IP = "wifi_ip";
    public static final String KEY_USB_IP = "usb_ip";
    public static final String KEY_BT_IP = "bt_ip";
    public static final String KEY_P2P_IP = "p2p_ip";
    public static final String KEY_ETHERNET_IP = "ethernet_ip";

    // 以太网共享常驻保活
    public static final String KEY_ETH_AUTO = "eth_auto";
    public static final String KEY_USB_AUTO = "usb_auto";

    public static final String[] ALL_KEYS = {
            KEY_WIFI_IP, KEY_USB_IP, KEY_BT_IP, KEY_P2P_IP, KEY_ETHERNET_IP, KEY_ETH_AUTO, KEY_USB_AUTO
    };

    private static final Pattern IPV4 = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /**
     * 配置放在 device-protected storage 里，首次解锁前也能读（Android 7.0+）。
     * Android 6 没有 DE 存储，退回普通存储。
     */
    public static SharedPreferences getPrefs(Context context) {
        return getDevicePrefs(context, PREF_NAME);
    }

    public static SharedPreferences getMqttPrefs(Context context) {
        return getDevicePrefs(context, PREF_MQTT);
    }

    public static SharedPreferences getPinPrefs(Context context) {
        return getDevicePrefs(context, PREF_PIN);
    }

    /** 统一的 device-protected 存储，首次解锁后自动从 CE 迁移。 */
    public static SharedPreferences getDevicePrefs(Context context, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return context.getSharedPreferences(name, Context.MODE_PRIVATE);
        }
        Context storage = context.createDeviceProtectedStorageContext();
        SharedPreferences de = storage.getSharedPreferences(name, Context.MODE_PRIVATE);
        if (de.getAll().isEmpty()) {
            migrateFromCredentialStorage(context, de, name);
        }
        return de;
    }

    /** 首次解锁后把旧的 CE 配置拷到 DE，之后锁屏状态也能读到。 */
    private static void migrateFromCredentialStorage(Context context, SharedPreferences de, String name) {
        try {
            UserManager userManager = context.getSystemService(UserManager.class);
            if (userManager == null || !userManager.isUserUnlocked()) {
                return;
            }
            SharedPreferences ce = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Map<String, ?> all = ce.getAll();
            if (all.isEmpty()) {
                return;
            }
            SharedPreferences.Editor editor = de.edit();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
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
        } catch (Throwable ignored) {
        }
    }

    private Config() {}

    /**
     * 只接受点分四段的 IPv4，且每段 0-255，不允许带 "/xx"。
     * 非法值直接丢弃可以避免 LinkAddress 抛 IllegalArgumentException 把被 hook 的进程搞崩。
     */
    public static boolean isValidIpv4(String value) {
        if (value == null) {
            return false;
        }
        Matcher m = IPV4.matcher(value.trim());
        if (!m.matches()) {
            return false;
        }
        for (int i = 1; i <= 4; i++) {
            if (Integer.parseInt(m.group(i)) > 255) {
                return false;
            }
        }
        return true;
    }
}
