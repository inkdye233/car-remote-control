# 双车遥控（原生 Android）

不依赖 HBuilderX、DCloud 云打包或第三方 BLE 库的原生 Android 应用。

## 环境

- Android Studio: `E:\Program Files\Android\Android Studio`
- Android SDK: `E:\AndroidSDK`
- Gradle 用户目录: `E:\Program Files\Android\GradleUserHome`
- 包名: `com.embedded.dualcarcontroller`
- minSdk 23 / targetSdk 34 / compileSdk 34

## 功能

- 同时维护 A、B 两条独立 GATT 连接
- 扫描 BLE 设备并验证 FFF0 服务
- 优先写入 FFF2，备用 FFF1
- 每条连接使用独立串行写队列
- 停止类指令走独立抢占通道，保证不被积压队列延迟
- A、B、A+B 同步控制
- 左右转长按连发、倒车、加速、停止、推行、全部急停
- 切后台自动发送安全停止

## 倒车

倒车指令已接入，`reverse` 按键绑定 `CarProtocol.REVERSE`，按下时档位置为 `-1`。长按期间由 `reverseRunnable` 每 200ms 重发一次倒车指令，机制与左右转的连发同类。

实际报文序列：

1. **按下瞬间**：发送 1 条 `REVERSE` —— `55 0A FF FF FF FF 02 00 00 00 5C 5E`。
2. **按住期间**：`reverseRunnable` 每 200ms 重发同一条 `REVERSE`，日志标签「倒车」。
3. **抬起或滑动取消**：取消倒车循环后补发 1 条 `HOLD_AND_STOP` —— `55 0A FF FF FF FF 00 00 00 00 5D E6`，日志标签「倒车停止」。

倒车期间**不会**下发 `HOLD_AND_STOP`：`holdRunnable`（前进档的 200ms 心跳）会显式跳过正在倒车的车，避免「保持/停止」报文与倒车互相打架。任何停止路径（停止、推行、全部急停、切后台）都会先取消倒车循环，防止急停后仍继续下发 `REVERSE`。

目标为 A+B 时，上述每条报文都会分别投递到两条连接，各自走独立的串行写队列（写间隔 24ms）。

## 停止类指令通道（急停 / 停止 / 倒车停止 / 安全停止 / 断开前停止）

**为什么需要单独通道**：写队列是单线程无界队列。若链路进入「僵尸」状态（`connected` 仍为 `true`，但实际已不回 ACK，系统尚未报断），`ready()` 只查标志位、察觉不到链路已死，`send()` 会持续入队；而 `WRITE_TYPE_DEFAULT` 的写入要等回执，每条最多阻塞 2 秒。此时按住前进/转向/倒车会让队列持续积压（心跳 200ms + 转向 150ms + 倒车 200ms 并存时约 16 条/秒），排在队尾的急停会被延迟数十秒。

**实现**（`BleCarManager`）：

- `writeQueue` 用直接 `new ThreadPoolExecutor` 构造（而非 `Executors.newSingleThreadExecutor()`），以获得队列引用；写线程命名 `ble-write-<slot>`。
- `sendUrgent()` 相对 `send()` 多三步：`getQueue().clear()` 丢弃积压的普通指令 → 取出 `writeLatch` 并 `countDown()` **软打断**正在等待回执的那条写（不中断线程，避免影响 GATT 栈；被中断的写会以 `GATT_FAILURE` 提前返回并记一条错误日志）→ 再入队。
- `WRITE_TYPE_DEFAULT` 的等待超时按紧急度区分：**急停类 250 ms**、普通 2000 ms。
- 急停类写入若 `launchWrite` 失败会重试 3 次（间隔 60 ms），避免因上一条写刚被软打断、GATT 短暂繁忙而静默丢弃停止包。

**接入点**：`MainActivity.stopSlots()`（覆盖「停止」「全部急停」「倒车停止」）、`BleCarManager.safeStopAll()`（切后台）、断开前的停止包。

净效果：急停从「可能排在数十秒积压之后」变为**约 250 ms 内必然下发**。

## 连发定时器一览

| 循环 | 周期 | 报文 | 取消方式 |
|---|---|---|---|
| `turnRunnable` | 150 ms | 当前转向包 | `stopTurn()` → `removeCallbacks` |
| `holdRunnable` | 200 ms | `HOLD_AND_STOP`（前进档心跳），显式跳过正在倒车的车 | `resetMotion()` / `onStop` |
| `reverseRunnable` | 200 ms | `REVERSE` | `stopReverse()` |

三个循环都由各自命名的 `Runnable` 字段持有并被显式取消。**注意不要再往按键监听器里写匿名自调度 `Runnable`**：早前转向连发就是匿名实现的，`stopTurn()` 无法取消它，于是「快速松开再按下」时旧循环会复活并与新循环并存，转向指令速率翻倍（已修）。

## 构建

用 Android Studio 打开本目录，或在 PowerShell 执行：

```powershell
$env:JAVA_HOME='E:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME='E:\Program Files\Android\GradleUserHome'
.\gradlew.bat assembleDebug
```

也可以直接双击或运行 `build-local.bat`，它会固定使用 E 盘的 JDK、SDK 和 Gradle 缓存。

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`

构建耗时参考：冷编译约 40 s ~ 1'40"，增量约 12 s。

几点注意：

- **debug 与 release 是两个独立产物**，重编一个不会更新另一个。用户装的通常是 release，改完代码别只编 debug。
- 若通过自动化/非交互 shell 调用 wrapper 却**看不到任何输出、也拿不到退出码**（看起来像"秒退成功"，实际根本没构建），改用 Git Bash 并先 `cd` 到项目目录再执行。
- APK 是 **multidex**（`classes.dex` / `classes2.dex` / `classes3.dex`，`MainActivity` 在 **classes3.dex**）。若要做 dex 层面的改动校验，必须**合并全部 dex 再搜**，只查 `classes.dex` 会误判为"改动没打进包"：
  ```bash
  unzip -o -q app-release.apk "*.dex" -d /tmp/chk && cat /tmp/chk/*.dex > /tmp/chk/all.bin
  grep -ac "新增的字段或方法名" /tmp/chk/all.bin    # ≥1 即已打入
  ```
