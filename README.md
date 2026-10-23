# 智能开关数据读取器

一个很小的 Android 工具，就干一件事：盯住一台指定的 BLE 设备，把它的广播原始数据（RAW）实时显示出来，方便复制走。

写它的原因很简单：nRF Connect 那类工具什么都能干，但每次只想瞄一眼某台设备的广播，都得在扫描列表里从十几台设备里翻找、点进去再切 RAW 视图，太折腾。这个 App 打开就扫、只认配置里那一台、主界面就只有信号强度、原始数据和一个复制按钮。

Kotlin 写的，没用 AndroidX，也没引入任何第三方库，只用系统 API，所以 APK 不大（debug 约 840 KB）。要 Android 8.0（API 26）以上。不联网、不申请 INTERNET 权限，MAC 和记录都只存在本机。

## 用之前先改 MAC（重要）

出厂默认 MAC 是占位值 `00:00:00:00:00:00`，不改的话永远扫不到东西，界面会一直停在「等待数据」——不是坏了，是还没设置。

设置入口藏得有点深，故意的，免得平时误触：3 秒内连续点左上角标题 10 次，进「高级设置」，填目标设备的 MAC 保存。支持 `AA:BB:CC:DD:EE:FF`、`AA-BB-...` 和 `AABBCCDDEEFF` 三种写法。保存后自动按新地址重新扫描。

## 怎么工作

- 被动监听，不连接、不读 GATT，所以对目标设备没任何影响。看到的 RAW 就是 `ScanResult.getScanRecord().getBytes()`，包含广播数据和扫描响应，不是解析后的字段，看不懂的字节得自己拿去别的工具里解。
- 历史记录只在数据变化时才写一条。广播一秒能来好几包相同内容，全记下来没意义，所以现在记录列表相当于一条变更时间线。上限 1000 条，超了删最旧的。每页 200 条，点一行就是复制那行，清空要二次确认。
- 复制有三种格式：连续无空格（默认，粘到解析工具里方便）、空格分隔（肉眼逐字节对）、`0x` 数组（直接贴代码里）。

高级设置里还能恢复默认 MAC、切换复制格式、查看/清空记录。

## 构建

要 JDK 17、Kotlin 1.9.24、AGP 8.1.4、Gradle 8.x，compileSdk/targetSdk 33，minSdk 26，applicationId 是 `com.ble.rawreader`。

先在根目录建 `local.properties` 写上本机 SDK 路径（这个文件不入库）：

```properties
sdk.dir=/path/to/Android/Sdk
```

然后：

```bash
gradle :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

几个坑提前说：项目关了 AndroidX 和 Jetifier；仓库路径带中文等非 ASCII 字符时 Gradle 会报错，`gradle.properties` 里留了 `android.overridePathCheck=true`；构建脚本里配的是国内镜像源，在国外编觉得慢就自己调 `repositories` 顺序。

## 权限

- Android 12+ 要 `BLUETOOTH_SCAN`，不给就收不到广播。
- Android 11 及以下用老的 `BLUETOOTH` / `BLUETOOTH_ADMIN`。
- 还声明了定位权限，因为 Android 6~11 把 BLE 扫描算作能推断位置，部分厂商 ROM 在 12+ 也还这么干。

权限被拒只会提示要授权，不会崩，授权后重新打开就能扫。

## 已知限制

这些是被动监听本身的边界，不是 bug：

- 只能看广播，看不到要连接后才能读的 GATT 服务、电量、传感器值。
- 广播格式各家厂商都不一样，本工具只给原始字节，不负责解读。
- 回调频率看系统脸色，不同手机、不同 ROM 上节奏可能不一样，密集广播还可能被系统合并或限流。
- 设备如果用随机地址、会定期换 MAC，写死 MAC 的方式就盯不住。
- 只在前台扫，退后台就停。没做多语言和横屏，界面是竖屏中文。

仅供学习和调试自己或已授权的设备用，后果自负。

许可：公开出来只是供学习参考，没随仓库授予使用许可。
