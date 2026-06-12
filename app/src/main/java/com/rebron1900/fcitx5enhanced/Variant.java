package com.rebron1900.fcitx5enhanced;

/**
 * 不同 fcitx5-android 衍生版本的适配接口。
 * 每个 Variant 定义包名、特性开关、字段映射。
 */
public interface Variant {

    /** 目标应用包名 */
    String packageName();

    /** 是否支持语音输入（需要 AIDL + RECORD_AUDIO） */
    boolean hasVoice();

    /** 是否有 kawaiiBar 字段（工具栏） */
    boolean hasKawaiiBar();

    /** 是否有 theme.isDark() 方法 */
    boolean hasThemeIsDark();
}
