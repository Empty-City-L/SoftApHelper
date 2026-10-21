# SoftApHelper (Xposed)

固定热点（SoftAP）网关 IPv4 地址的 Xposed 模块。

## 功能

- 固定 WiFi / USB / 蓝牙 / WiFi P2P / 以太网 热点的网关地址
- 界面里可以改，保存后重新开关热点即可生效

> 5G 信道 / 带宽锁定功能已经移除：在部分 ROM（如小米）上强改 `SoftApConfiguration` 会导致 hostapd 启动失败、热点打不开。

## 使用

1. 安装后在 LSPosed 里启用，作用域勾选**系统框架** + Tethering 相关包（模块已内置推荐作用域）。
2. 打开 SoftApHelper，按需修改各类型的网关 IP，点「保存」。
3. 重新开关一次热点（或重启共享）。
4. 客户端连上后执行 `ip a` / `ifconfig`，网关应该就是你设置的地址。

### 默认值

| 类型 | 默认 IP |
|---|---|
| WiFi | 192.168.43.1 |
| USB | 192.168.42.1 |
| 蓝牙 | 192.168.44.1 |
| WiFi P2P | 192.168.49.1 |
| 以太网 | 192.168.45.1 |

如果 192.168.43.1 连不上（和上游网段冲突），直接在界面里改成 `192.168.1.1` 之类的地址再保存即可。

## 兼容性

- Android 11+：WiFi / USB / 蓝牙 / P2P / 以太网
- Android 9-10：仅 WiFi
- Android 16/17：已适配（`IpServer.mPrivateAddressCoordinator` 被移除，改用异步冲突回调兜底）

## 注意

- 修改后要重新开关热点才会生效（配置有 3 秒缓存）。
- 如果热点网段和上游网络冲突，模块会跳过替换（日志里能看到 `isConflictPrefix`）；Android 16/17 上冲突检测是异步的，收到冲突后会暂停强制 60 秒。
- 修改 IP 前建议先确认手机本身能正常开热点。

## 远程切卡（可选）

`system_server` 里还会起一个局域网 HTTP 服务（端口 8080，PIN 在 App 里设置），以及可选的 MQTT 客户端：

| 接口 | 说明 |
|---|---|
| `GET /api/status?pin=...` | 当前默认卡、卡槽、控制地址 |
| `GET /api/switch?pin=...&slot=1` | 切到指定卡槽（1 起） |
| `GET /api/toggle?pin=...` | 切到另一张卡，一次请求完成 |

控制端磁贴优先走 `/api/toggle`，省掉一次状态查询；HTTP 服务用线程池处理请求，
切卡期间不会再堵住状态查询。

## 原理

| 版本 | Hook 点 |
|---|---|
| Android 9 | `TetherInterfaceStateMachine#getRandomWifiIPv4Address` |
| Android 10 | `android.net.ip.IpServer#getRandomWifiIPv4Address` |
| Android 11+ | `android.net.ip.IpServer#requestIpv4Address`（在 `configureIPv4` 调用时按 `mInterfaceType` 替换返回值） |

配置通过 `ContentProvider`（`com.xhy.xp.softaphelper.config`）从 App 进程传给被 hook 的 system_server / tethering 进程。

## 构建

```bat
gradlew.bat assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`（签名配置见 `keystore.properties`，不入库）。

## 感谢

[@mmfmkuang](https://github.com/mmfmkuang) [@dsfgdadg](https://github.com/dsfgdadg)
