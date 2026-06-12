package com.rebron1900.fcitx5enhanced;

/** fxliang 的 fcitx5-android fork，完整支持语音。 */
public class FxVariant implements Variant {

    public static final String PACKAGE = "org.fcitx.fcitx5.android.fx";

    @Override
    public String packageName() {
        return PACKAGE;
    }

    @Override
    public boolean hasVoice() {
        return true;
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
