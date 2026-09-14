# 小车遥控

基于低功耗蓝牙（BLE）的原生 Android 遥控应用。可在同一部手机上同时连接小车的前轮与后轮驱动单元，并分别或同步下发控制指令。

## 功能特性

- 同时维护两条独立的 GATT 连接，分别对应前轮与后轮，互不干扰
- 扫描时按 `FFF0` 服务 UUID 过滤 BLE 广播，仅列出声明该服务的设备；连接后再次校验服务与可写特征，不合规设备不会进入控制流程
- 写入特征按 `FFF2`（首选）→ `FFF1`（备用）→ 该服务下任一可写特征的顺序选择
- 每条连接使用独立串行写队列，写间隔约 24 ms
- 支持前轮、后轮单独控制与前后同步控制
- 控制指令：左转、右转、加速、倒车、保持、推行、全部急停
- 转向与倒车为长按连发，松开后自动补发停止指令
- 应用切至后台时自动下发安全停止

## 环境要求

| 项目 | 要求 |
|---|---|
| 运行平台 | Android 6.0（API 23）及以上 |
| 编译 / 目标 SDK | 34 |
| 构建工具 | JDK 17、Android SDK（含 platform 34） |
| 应用 ID | `com.embedded.dualcarcontroller` |
| 依赖 | AndroidX、Material Components 1.12.0 |

设备需支持蓝牙 4.0 及以上（BLE），应用在清单中将 BLE 声明为必需特性。

## 构建

### 准备

需要 JDK 17 与 Android SDK（含 platform 34）。SDK 位置通过以下任一方式指定：

- **Android Studio**：打开项目目录时会自动生成 `local.properties`，无需手动配置。
- **命令行**：设置环境变量 `ANDROID_HOME`，或在项目根目录创建 `local.properties`：

  ```properties
  sdk.dir=/path/to/Android/Sdk
  ```

  `local.properties` 记录的是本机路径，已在 `.gitignore` 中排除，请勿提交。

### 构建命令

```bash
# 调试版
./gradlew assembleDebug

# 发布版
./gradlew assembleRelease
```

Windows 环境请使用 `gradlew.bat`，在 cmd 或 PowerShell 中执行（Git Bash 下直接运行 `./gradlew` 会因路径转换问题报 `ClassNotFoundException: GradleWrapperMain`）。

产物路径：

| 版本 | 路径 |
|---|---|
| 调试版 | `app/build/outputs/apk/debug/app-debug.apk` |
| 发布版 | `app/build/outputs/apk/release/app-release.apk` |

说明：调试版与发布版为两个独立产物，重新构建其中一个不会更新另一个。发布版使用调试签名配置，可直接安装，但不适用于应用商店上架。

## 通信协议

BLE 服务与特征：

| 用途 | UUID |
|---|---|
| 服务 | `0000fff0-0000-1000-8000-00805f9b34fb` |
| 写入特征（首选） | `0000fff2-0000-1000-8000-00805f9b34fb` |
| 写入特征（备用） | `0000fff1-0000-1000-8000-00805f9b34fb` |

控制指令为 12 字节定长报文：

| 指令 | 报文（十六进制） |
|---|---|
| 左转 | `55 0A FD FB FF FF 00 00 00 00 99 FF` |
| 右转 | `55 0A 01 FA 00 02 00 00 00 00 FF F5` |
| 加速 | `55 0A 00 52 01 FD 00 00 00 00 02 36` |
| 倒车 | `55 0A FF FF FF FF 02 00 00 00 5C 5E` |
| 保持 / 停止 | `55 0A FF FF FF FF 00 00 00 00 5D E6` |
| 推行 | `55 0A FF FF FF FF FE 10 00 00 00 64 E6` |

## 项目结构

```
app/src/main/
├── java/com/embedded/dualcarcontroller/
│   ├── MainActivity.java      界面与交互逻辑
│   ├── BleCarManager.java     BLE 扫描、连接与读写队列
│   └── CarProtocol.java       协议常量与报文编解码
├── res/                       布局、样式、图标等资源
└── AndroidManifest.xml
```

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
