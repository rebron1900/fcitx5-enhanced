package com.rebron1900.fcitx5enhanced;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.caverock.androidsvg.SVG;

/** Lucide SVG icons rendered to BitmapDrawable at runtime. */
public class SvgIcons {
    private static final String TAG = "Fcitx5Enh";

    /** Lucide keyboard.svg — IME 切换图标 */
    private static final String IME_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<rect x=\"2\" y=\"4\" width=\"20\" height=\"16\" rx=\"2\"/>" +
        "<path d=\"M6 8h.01\"/>" +
        "<path d=\"M10 8h.01\"/>" +
        "<path d=\"M14 8h.01\"/>" +
        "<path d=\"M18 8h.01\"/>" +
        "<path d=\"M8 12h.01\"/>" +
        "<path d=\"M12 12h.01\"/>" +
        "<path d=\"M16 12h.01\"/>" +
        "<path d=\"M7 16h10\"/>" +
        "</svg>";

    /** Lucide audio-lines.svg — 语音输入图标 */
    private static final String MIC_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<path d=\"M2 20v-8\"/>" +
        "<path d=\"M6 20v-4\"/>" +
        "<path d=\"M10 20V4\"/>" +
        "<path d=\"M14 20V8\"/>" +
        "<path d=\"M18 20v-6\"/>" +
        "<path d=\"M22 20v-2\"/>" +
        "</svg>";

    /** Lucide clipboard-list.svg — 剪贴板图标 */
    private static final String CLIPBOARD_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<rect x=\"8\" y=\"2\" width=\"8\" height=\"4\" rx=\"1\"/>" +
        "<path d=\"M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2\"/>" +
        "<path d=\"M12 11h4\"/>" +
        "<path d=\"M12 16h4\"/>" +
        "<path d=\"M8 11h.01\"/>" +
        "<path d=\"M8 16h.01\"/>" +
        "</svg>";

    public static Drawable ime(float density, int color, int sizeDp) {
        return render(IME_SVG, density, color, sizeDp);
    }

    public static Drawable mic(float density, int color, int sizeDp) {
        return render(MIC_SVG, density, color, sizeDp);
    }

    public static Drawable clipboard(float density, int color, int sizeDp) {
        return render(CLIPBOARD_SVG, density, color, sizeDp);
    }

    private static Drawable render(String svgTemplate, float density, int color, int sizeDp) {
        try {
            int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            float a = Color.alpha(color) / 255.0f;
            String strokeColor = String.format("rgba(%d,%d,%d,%.2f)", r, g, b, a);
            String svgXml = String.format(svgTemplate, strokeColor);

            SVG svg = SVG.getFromString(svgXml);
            int px = Math.max(1, (int) (sizeDp * density + 0.5f));

            Picture picture = svg.renderToPicture();
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawPicture(picture, new RectF(0, 0, px, px));
            return new BitmapDrawable(null, bmp);
        } catch (Throwable t) {
            Log.w(TAG, "SVG error: " + t);
            return null;
        }
    }
}
