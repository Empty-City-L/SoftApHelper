# SoftApHelper

两个配套的 Android 工程：

| 目录 | 说明 |
|---|---|
| [SoftApHelper-LAN](SoftApHelper-LAN/) | Xposed 模块：固定 WiFi / USB / 蓝牙 / P2P / 以太网热点网关 IPv4，并在 `system_server` 内提供局域网 HTTP 与 MQTT 远程切卡接口 |
| [SimSwitchTile](SimSwitchTile/) | 控制端 App：快捷设置「切卡」磁贴，通过局域网 HTTP 或 MQTT 切换目标机的默认数据卡 |

## 下载

已签名的安装包见 [Releases](https://github.com/Empty-City-L/SoftApHelper/releases)。

## 构建

两个工程相互独立，分别用 Android Studio 打开，或在各自目录下执行：

```bat
gradlew.bat assembleRelease
```

产物位于各自的 `app\build\outputs\apk\release\app-release.apk`。

签名配置从各工程根目录的 `keystore.properties` 读取，该文件不入库；没有该文件时构建出的是未签名包。

## 文档

- [SoftApHelper-LAN/README.md](SoftApHelper-LAN/README.md)：模块功能、远程切卡 / MQTT 配置、Hook 原理、局域网 API
- [SimSwitchTile/README.md](SimSwitchTile/README.md)：控制端使用说明
