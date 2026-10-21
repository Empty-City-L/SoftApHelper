# SimSwitch 控制端

配合 [SoftApHelper-LAN](../SoftApHelper-LAN/)（Xposed 模块，跑在目标机 `system_server` 里）远程切换目标机的默认数据卡。
支持两种通道，按配置自动选择：

- **局域网 HTTP**：目标机和本机在同一 Wi-Fi / 热点下，走 `http://目标IP:8080`
- **MQTT（EMQX）**：目标机不在同一局域网时走公网 broker

安装包下载见 [Releases](https://github.com/Empty-City-L/SoftApHelper/releases)。

## 使用

1. 打开 App，填写目标机地址（如 `http://192.168.43.1:8080`）和 PIN，点「保存」。
2. 点「切到卡1 / 切到卡2」即可；也可以把「切卡」磁贴加到快捷设置，点一下在两张卡之间切换。
3. 需要远程时再填 MQTT 配置；MQTT 连接不上的那次操作会在约 1 秒内自动回落到局域网。

局域网 API：

| 路径 | 说明 |
|---|---|
| `GET /api/status?pin=...` | 当前默认卡和卡槽信息 |
| `GET /api/switch?pin=...&slot=1` | 切到指定卡槽（1 起） |
| `GET /api/toggle?pin=...` | 切到另一张卡（一次请求完成） |

旧版目标机没有 `/api/toggle` 时，磁贴会自动退回 `status + switch` 两次请求。

## 构建

```bat
gradlew.bat assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`。
