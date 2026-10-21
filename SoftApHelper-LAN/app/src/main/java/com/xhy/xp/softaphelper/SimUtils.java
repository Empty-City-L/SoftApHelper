package com.xhy.xp.softaphelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimUtils {

    public static final int PORT = 8080;

    // 从 dumpsys isub 的输出里抓：id=1 ... simSlotIndex=0 ... carrierName=中国电信
    private static final Pattern SUB_PATTERN = Pattern.compile(
            "id=(\\d+).*?simSlotIndex=(-?\\d+).*?carrierName=([^\\s\\]]+)");

    private SimUtils() {
    }

    public static final class Sim {
        public final int slot;
        public final int subId;
        public final String name;

        Sim(int slot, int subId, String name) {
            this.slot = slot;
            this.subId = subId;
            this.name = name;
        }
    }

    /** 以 root 执行命令，返回 stdout+stderr。 */
    public static String execRoot(String command) {
        StringBuilder out = new StringBuilder();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            BufferedReader error = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = error.readLine()) != null) {
                out.append(line).append('\n');
            }
            process.waitFor();
        } catch (Throwable t) {
            out.append("ERR: ").append(t);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return out.toString().trim();
    }

    public static List<Sim> listSims() {
        String dump = execRoot("dumpsys isub");
        Map<Integer, Sim> map = new LinkedHashMap<>();
        for (String line : dump.split("\n")) {
            Matcher matcher = SUB_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int subId = Integer.parseInt(matcher.group(1));
            int slot = Integer.parseInt(matcher.group(2));
            if (slot < 0) {
                continue;
            }
            if (!map.containsKey(subId)) {
                map.put(subId, new Sim(slot, subId, matcher.group(3)));
            }
        }
        List<Sim> sims = new ArrayList<>(map.values());
        Collections.sort(sims, (a, b) -> Integer.compare(a.slot, b.slot));
        return sims;
    }

    public static int getDefaultDataSub() {
        String value = execRoot("settings get global multi_sim_data_call").trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 切默认数据卡：ISub.setDefaultDataSubId 的 transaction code 是 31（Android 17 / 本机型实测）。
     * 调用后轮询读回校验，避免 transaction code 不匹配时误报成功。
     */
    public static boolean switchDefaultData(int subId) {
        execRoot("service call isub 31 i32 " + subId);
        for (int i = 0; i < 4; i++) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            if (getDefaultDataSub() == subId) {
                return true;
            }
        }
        return false;
    }

    public static String getLocalIp() {
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

    public static String describe() {
        int def = getDefaultDataSub();
        StringBuilder sb = new StringBuilder();
        List<Sim> sims = listSims();
        if (sims.isEmpty()) {
            sb.append("没读到 SIM 卡（root 授权了吗？）\n");
        }
        for (Sim sim : sims) {
            sb.append("卡槽").append(sim.slot + 1).append("  ")
                    .append(sim.name).append("  subId=").append(sim.subId);
            if (sim.subId == def) {
                sb.append("  ← 当前数据卡");
            }
            sb.append('\n');
        }
        sb.append("默认数据卡 subId=").append(def).append('\n');
        sb.append("控制地址（任选一个，另一台设备能访问即可）:\n");
        sb.append(getAddressList());
        return sb.toString();
    }

    /** 列出所有已启用网卡的 IPv4，热点/USB 共享的网关地址也会在这里。 */
    public static String getAddressList() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        if (ip == null || ip.startsWith("127.")) {
                            continue;
                        }
                        sb.append("  ").append(networkInterface.getName())
                                .append("  http://").append(ip).append(':').append(PORT);
                        if ("wlan0".equals(networkInterface.getName())) {
                            sb.append("  (Wi-Fi)");
                        }
                        sb.append('\n');
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (sb.length() == 0) {
            sb.append("  （没有可用地址）\n");
        }
        return sb.toString();
    }
}
