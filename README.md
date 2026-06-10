# Fcitx5 增强

<div align="center">

一个 LSPosed 模块，为 [fcitx5-android](https://github.com/fxliang/fcitx5-android)（fx 版）提供**键盘背景美化**与**快捷工具**。

![Platform](https://img.shields.io/badge/platform-Android_12%2B-brightgreen)
![LSPosed](https://img.shields.io/badge/LSPosed-required-blue)
![License](https://img.shields.io/badge/license-GPL--3.0-red)
![Version](https://img.shields.io/github/v/release/rebron1900/fcitx5-enhanced)

</div>

> 截图待补充

## 功能

**🎨 键盘美化**
- 键盘背景**磨砂玻璃**效果（真实 `BlurDrawable`，非伪毛玻璃）
- 自定义**圆角**（全局统一，覆盖键盘本体与弹窗）
- 描边颜色**自动推导自主题背景色**
- **暗色主题自适应**（浅色/深色主题自动切换风格）

**🎛️ 可配置参数**（设置界面实时调节）
- 模糊半径 (0–100)
- 背景透明度 (0–255)
- 圆角半径 (0–48)

**⌨️ 快捷工具栏**
- **左下角**：IME 切换按钮 → 弹出系统输入法选择列表
- **右下角**：剪贴板按钮 → 弹出最近 10 条剪贴板记录
- **底部中间**：频谱波形线（空闲水平渐隐线 / 录音时实时 RMS 振幅动画）

**🎤 语音输入**
- 长按波形线区域录音，松开提交
- 集成 bibi keyboard 语音识别服务
- 实时振幅波形反馈

## 环境要求

| 项目 | 要求 |
|---|---|
| 设备 | Android 12+ (API 31+) |
| Root | [LSPosed](https://github.com/LSPosed/LSPosed) 已激活 |
| 输入法 | [fcitx5-android](https://github.com/fxliang/fcitx5-android) fx 版 |
| 语音（可选） | [BiBi-Keyboard（说点啥）](https://github.com/BryceWG/BiBi-Keyboard) 或兼容 AIDL 服务 |

## 安装

1. 下载最新 [Release APK](https://github.com/rebron1900/fcitx5-enhanced/releases)
2. 在 LSPosed 中激活模块，作用域勾选 `org.fcitx.fcitx5.android.fx`
3. 重启 fcitx5 进程（或重启设备）
4. 在桌面应用列表中找到 **Fcitx5 增强配置**，调节参数

## 从源码编译

```bash
git clone https://github.com/rebron1900/fcitx5-enhanced.git
cd fcitx5-enhanced
./gradlew assembleRelease
# 产物在 app/build/outputs/apk/release/
```

> 生成的 APK 需要签名后才能安装。Release 版本由 CI 自动签名。

## 致谢

- [WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced) — UI 增强灵感
- [WaveLineView](https://github.com/Jay-Goo/WaveLineView) — 录音波形动画（振幅响应管线参考）
- [fcitx5-android](https://github.com/fxliang/fcitx5-android) — 优秀的 Android 输入法
- [BiBi-Keyboard（说点啥）](https://github.com/BryceWG/BiBi-Keyboard) — 语音识别服务

## 许可证

[GNU General Public License v3.0](LICENSE)
