package com.xhy.simswitch.ctl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class Controller {

    public static final String PREFS = "ctl";
    public static final String DEFAULT_URL = "http://192.168.43.1:8080";

    private Controller() {
    }

    public static String httpGet(String url) throws Exception {
        return httpGet(url, 4000, 6000);
    }

    /**
     * 局域网请求默认给较短超时：目标机不可达时尽快失败，别让磁贴一直转圈。
     */
    public static String httpGet(String url, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        try {
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
            }
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }
            return sb.toString();
        } finally {
            connection.disconnect();
        }
    }

    public static String statusUrl(String baseUrl, String pin) {
        return trimSlash(baseUrl) + "/api/status?pin=" + pin;
    }

    public static String switchUrl(String baseUrl, String pin, int slot) {
        return trimSlash(baseUrl) + "/api/switch?pin=" + pin + "&slot=" + slot;
    }

    /** 一次请求切到另一张卡（新版本 system_server 服务支持）。 */
    public static String toggleUrl(String baseUrl, String pin) {
        return trimSlash(baseUrl) + "/api/toggle?pin=" + pin;
    }

    private static String trimSlash(String url) {
        String value = url == null ? DEFAULT_URL : url.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
