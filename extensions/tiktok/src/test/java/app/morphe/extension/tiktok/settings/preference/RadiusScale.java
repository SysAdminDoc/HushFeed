package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertTrue;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Walks a built view tree and fails on any backdrop drawn as an oval or rounded off the bundle's
 * scale: 0, 4, 6, 8, 10 or 12 dp. Glyphs (compound drawables, image content) aren't backdrops and
 * aren't read; a status dot is allowed to be round.
 */
public final class RadiusScale {
    private static final int[] SCALE = {0, 4, 6, 8, 10, 12};

    private RadiusScale() {
    }

    public static void assertOnScale(View root, String where) {
        List<String> off = new ArrayList<>();
        collect(root, where, off);
        assertTrue("backdrops off the 0/4/6/8/10/12 dp scale on " + where + ": " + off, off.isEmpty());
    }

    private static void collect(View view, String where, List<String> off) {
        // A hidden view draws nothing: the framework list fragment keeps a GONE wizard bar of
        // stock buttons under every preference page.
        if (view.getVisibility() != View.VISIBLE) return;
        float density = view.getResources().getDisplayMetrics().density;
        String name = describe(view);
        check(view.getBackground(), name, density, off, 0);
        check(view.getForeground(), name + " foreground", density, off, 0);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), where, off);
        }
    }

    private static void check(Drawable drawable, String where, float density, List<String> off, int depth) {
        if (drawable == null || depth > 8) return;
        if (drawable instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) drawable;
            if (shape.getShape() == GradientDrawable.OVAL) {
                off.add(where + " is an oval");
                return;
            }
            if (shape.getShape() != GradientDrawable.RECTANGLE) return;
            float[] radii = shape.getCornerRadii();
            if (radii == null) radii = new float[]{shape.getCornerRadius()};
            for (float radius : radii) {
                if (!onScale(radius / density)) {
                    off.add(String.format(Locale.ROOT, "%s at %.1f dp", where, radius / density));
                    return;
                }
            }
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                check(layers.getDrawable(i), where, density, off, depth + 1);
            }
        } else if (drawable instanceof InsetDrawable) {
            check(((InsetDrawable) drawable).getDrawable(), where, density, off, depth + 1);
        } else if (drawable instanceof StateListDrawable) {
            StateListDrawable states = (StateListDrawable) drawable;
            for (int i = 0; i < states.getStateCount(); i++) {
                check(states.getStateDrawable(i), where, density, off, depth + 1);
            }
        } else if (drawable instanceof DrawableContainer) {
            check(drawable.getCurrent(), where, density, off, depth + 1);
        }
    }

    private static boolean onScale(float dp) {
        for (int step : SCALE) {
            if (Math.abs(dp - step) < 0.05f) return true;
        }
        return false;
    }

    private static String describe(View view) {
        Object tag = view.getTag();
        CharSequence text = view instanceof android.widget.TextView
                ? ((android.widget.TextView) view).getText() : view.getContentDescription();
        return view.getClass().getSimpleName()
                + (tag instanceof String ? "#" + tag : "")
                + (text != null && text.length() > 0 ? "\"" + text + "\"" : "");
    }
}
