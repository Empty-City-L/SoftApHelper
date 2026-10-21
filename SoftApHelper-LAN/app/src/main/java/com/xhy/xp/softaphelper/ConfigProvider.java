package com.xhy.xp.softaphelper;

import android.content.ContentProvider;
import android.content.Context;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.ParcelFileDescriptor;

import java.util.Map;

public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.xhy.xp.softaphelper.config";
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_GET_ALL = "getAll";

    private SharedPreferences mPrefs;

    @Override
    public boolean onCreate() {
        mPrefs = Config.getPrefs(getContext());
        return true;
    }

    // 只允许自己、system_server、root、以及 networkstack/tethering 进程读取
    // （钩子要在 networkstack 进程里读配置，那些进程 uid 不是 1000）。
    private boolean isTrustedCaller(int uid) {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) {
            return true;
        }
        try {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String name : packages) {
                    if (name != null && (name.contains("networkstack") || name.contains("tethering"))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (!isTrustedCaller(uid)) {
            return new Bundle();
        }
        if (METHOD_GET_ALL.equals(method)) {
            Bundle out = new Bundle();
            // 不能按 key 直接 getString：布尔值（开关）存在同一个 SharedPreferences 里，
            // 对布尔 key 调 getString 会抛 ClassCastException，导致整个配置读取失败。
            Map<String, ?> all = mPrefs.getAll();
            for (String key : Config.ALL_KEYS) {
                Object value = all.get(key);
                out.putString(key, value == null ? "" : String.valueOf(value));
            }
            // MQTT/PIN 存在 CE 存储，锁屏时读会抛异常；不能让它影响固定 IP 配置读取。
            try {
                SharedPreferences mqtt = Config.getMqttPrefs(getContext());
                out.putString("mqtt_host", mqtt.getString("host", ""));
                out.putString("mqtt_user", mqtt.getString("user", ""));
                out.putString("mqtt_pass", mqtt.getString("pass", ""));
                out.putString("mqtt_device", mqtt.getString("device", "xiaomi14"));
                SharedPreferences pin = Config.getPinPrefs(getContext());
                out.putString("mqtt_pin", pin.getString("pin", ""));
            } catch (Throwable ignored) {
                out.putString("mqtt_host", "");
                out.putString("mqtt_user", "");
                out.putString("mqtt_pass", "");
                out.putString("mqtt_device", "xiaomi14");
                out.putString("mqtt_pin", "");
            }
            return out;
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        return null;
    }
}
