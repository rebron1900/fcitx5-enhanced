# Fcitx5 增强插件 (fcitx5-enhanced)

LSPosed 模块，为 fcitx5-android 提供键盘美化（毛玻璃、按键描边）和 RIME WebDAV 同步。

## 目录映射

```
/opt/data/workspace/03-dev/project/fcitx5-enhanced/
├── app/src/main/java/com/rebron1900/fcitx5enhanced/
│   ├── MainHook.java              — LSPosed 入口，Hook setInputView/onWindowShown
│   ├── SettingsActivity.java       — 设置 UI（主题美化 + WebDAV 同步双 Tab）
│   ├── ConfigStorage.java          — 配置持久化（SP + JSON 文件双写）
│   ├── ConfigProvider.java         — 跨进程 ContentProvider
│   ├── FrostedGlassHelper.java     — 毛玻璃 + 键盘圆角
│   ├── GlassBorderDrawable.java    — 按键玻璃描边 Drawable
│   ├── KeyEffectsHelper.java       — 按键描边效果
│   ├── ExtraButtonsHelper.java     — 底部工具栏（IME/剪贴板/语音按钮）
│   ├── PreeditHelper.java          — 预编辑文本
│   ├── VoiceInputClient.java       — 语音输入 AIDL 客户端
│   ├── SvgIcons.java               — SVG 图标
│   ├── WaveformLineView.java       — 语音波形动画
│   └── sync/
│       └── WebDavSyncHelper.java   — WebDAV 双向同步引擎
```

## 构建前准备

### 环境变量

```bash
export JAVA_HOME=/opt/data/tools/jdk-21.0.6+7
export ANDROID_HOME=/opt/data/android-sdk
export ANDROID_SDK_ROOT=/opt/data/android-sdk
```

`local.properties` 已指向 `sdk.dir=/opt/data/android-sdk`，无需单独设置。

### 密钥

| 用途 | 路径 |
|------|------|
| 发布签名 | `/opt/data/workspace/03-dev/project/fcitx5-frosted-glass-module/release_fcitx5.keystore` |
| 调试签名 | `~/.android/debug.keystore`（别名 `androiddebugkey`，密码 `android`） |

## APK 打包流程

### 1. 编译

```bash
cd /opt/data/workspace/03-dev/project/fcitx5-enhanced
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release-unsigned.apk
```

### 2. 签名（发布版）

```bash
KEYSTORE=/opt/data/workspace/03-dev/project/fcitx5-frosted-glass-module/release_fcitx5.keystore
APKSIGNER=$ANDROID_HOME/build-tools/36.1.0/lib/apksigner.jar

$JAVA_HOME/bin/java -jar $APKSIGNER sign \
  --ks $KEYSTORE \
  --out app/build/outputs/apk/release/fcitx5-enhanced-v1.8.0.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

密钥别名和密码见 `.env` 或询问用户。

### 3. 签名（调试版 — 临时测试）

```bash
APKSIGNER=$ANDROID_HOME/build-tools/36.1.0/lib/apksigner.jar

$JAVA_HOME/bin/java -jar $APKSIGNER sign \
  --ks ~/.android/debug.keystore \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out app/build/outputs/apk/release/fcitx5-enhanced-test.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

### 4. 交付

在 Telegram 响应中包含 `MEDIA:/absolute/path/to.apk` 发送 APK 文件。

## 架构

### 同步系统（WebDAV RIME Sync）

**触发方式：** MainHook 内 Handler 定时循环（每分钟 tick），检查上次同步时间 → 够间隔则执行。

**执行位置：** fcitx5 进程内（MainHook 在 `org.fcitx.fcitx5.android.fx` 进程运行），File API 直接读写 sync 目录，无需 SAF。

**同步引擎：** `WebDavSyncHelper`，单线程顺序上传/下载，Last-write-wins + .bak 冲突备份，HTTP 423 自动重试（指数退避 3 次）。

**互斥：** `AtomicBoolean` 防止并发触发。

### 关键流程

```
键盘弹出 (onWindowShown hook)
  └─ reread config + reapply UI effects

Handler 定时循环 (60s tick)
  └─ WebDavSyncHelper.sync()
      ├─ PROPFIND 递归列举远端 (XmlPullParser)
      ├─ File.listFiles() 扫描本地
      ├─ 对比 → 下载/上传
      └─ .bak 冲突备份

SettingsActivity "立即同步" 按钮
  └─ sendBroadcast(SYNC_TRIGGER) → MainHook 收到 → runSyncOnce()
```

| 解决方案 | 版本号 + WeakHashMap 避免 InputView 重建后效果丢失 |

## 配置系统（2026-07-08 重构）

```
ConfigManager (静态类)
  ├─ AtomicInteger sVersion          — 版本计数器
  ├─ Map<View,Integer> sLastApplied  — 每个 InputView 上次应用的版本（WeakHashMap 自动清理）
  ├─ Config 内部类                    — 不可变配置快照（final 字段）
  ├─ read(Context) → Config           — 从 SP "fcitx5_enhanced_config" 读
  ├─ write(Context, ...)              — 写 SP + bumpVersion
  ├─ shouldApply(View) → boolean     — O(1) 版本号对比
  ├─ resetForView(View)              — 新 InputView 时清除记录
  └─ bumpVersion()                   — 配置变更时递增
```

**数据流：**
```
SettingsActivity
  └─ saveAndApply()
       ├─ ConfigManager.write() → bumpVersion
       ├─ ConfigStorage.writeConfigToFile() (NPatch 备选)
       └─ sendBroadcast(UI_UPDATE) → MainHook.BroadcastReceiver
                                        └─ ConfigManager.write(this) → bumpVersion

MainHook.ContentObserver
  └─ bumpVersion() (只看增量通知，不读数据)

setInputView hook
  └─ detect InputView change → ConfigManager.resetForView(newView)

onWindowShown hook
  └─ ConfigManager.shouldApply() ? applyAllEffects() : skip

ConfigProvider (跨进程 IPC)
  └─ update() → ConfigManager.write() → bumpVersion + notifyChange
  └─ query()  → ConfigManager.read()
```

**核心规则：**
- 所有写入通道最终汇聚到 ConfigManager.write() → 同一个 SP + 版本号递增
- applyAllEffects 之前用 shouldApply() 决定是否执行（版本号 O(1)）
- InputView 重建时 resetForView() 确保下次 apply 必执行
- `MainHook.Config` 已移除，全部使用 `ConfigManager.Config`
- `ConfigStorage` 标记 @Deprecated，仅保留 JSON 文件 IO 作为 NPatch 备选通道
- Bitmap 缓存（FrostedGlassHelper）、按键描边缓存（KeyEffectsHelper）均加版本守卫，配置变化时自动失效

- Android 12+ (API 31+)
- LSPosed 已激活
- 目标包名 `org.fcitx.fcitx5.android.fx`（靓企鹅版）或 `org.fcitx.fcitx5.android`（官方版）
- WebDAV 同步跑在 fcitx5 进程内，定时循环基于 Handler (main Looper)
