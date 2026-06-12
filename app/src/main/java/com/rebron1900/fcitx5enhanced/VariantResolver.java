package com.rebron1900.fcitx5enhanced;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 运行时根据包名解析 Variant。
 * 新增衍生版本只需在这里加一行注册。
 */
public class VariantResolver {

    private static final String TAG = "Fcitx5Enh";
    private static final Map<String, Variant> REGISTRY = new HashMap<>();

    static {
        register(new FxVariant());
        register(new OfficialVariant());
        // 未来新增衍生版本在这里 register(new XxxVariant());
    }

    private static void register(Variant v) {
        REGISTRY.put(v.packageName(), v);
    }

    /**
     * 根据包名返回对应 Variant，不认识的包返回 null。
     */
    public static Variant resolve(String packageName) {
        Variant v = REGISTRY.get(packageName);
        if (v != null) {
            Log.i(TAG, "variant resolved: " + packageName + " → " + v.getClass().getSimpleName());
        }
        return v;
    }
}
