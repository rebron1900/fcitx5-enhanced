package com.rebron1900.fcitx5enhanced;

/** fcitx5-android 官方版本，无语音权限。 */
public class OfficialVariant implements Variant {

    public static final String PACKAGE = "org.fcitx.fcitx5.android";

    @Override
    public String packageName() {
        return PACKAGE;
    }

    @Override
    public boolean hasVoice() {
        return false;
    }

    @Override
    public boolean hasKawaiiBar() {
        return true;
    }

    @Override
    public boolean hasThemeIsDark() {
        return true;
    }
}
