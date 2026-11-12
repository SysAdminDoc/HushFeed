/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.os.Build;
import android.view.Surface;
import android.view.SurfaceView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Where TikTok's player draws each video, so the Long press frame save and the glass blur can read
 * it back.
 *
 * <p>TikTok's player can draw into a layer of its own instead of into the feed cell's SurfaceView:
 * TTPlayerSurface builds a SurfaceControl named TTPlayerVideoLayer-n with a Surface on it, and the
 * player hangs that layer under the SurfaceView through a small static helper (one reparent, one
 * for Android 13 and later that waits for the commit) and takes it down through a third ("recycle").
 * The SurfaceView then never holds a frame and neither does the window, so PixelCopy on either
 * answers with no data. That's 47.5.3 on the S22 (2026-10-23, SurfaceFlinger showing a 576x1024
 * TTPlayerVideoLayer-0 as the only buffer under the cell). TTPlayerSurface's own nativeReparent
 * calls the first helper too, but the player goes straight to the helpers, so those are what the
 * patch hands here, and the layer's own Surface is what PixelCopy reads.
 *
 * <p>The LIVE player does the same without TTPlayerSurface: on 47.7.3 it builds a Live-n layer on
 * SurfaceControl itself and hangs it under the LIVE cell's SurfaceView (the S22, 2026-11-11, #259).
 * Its hook hands over that layer's Surface, which stands in for the player here.
 *
 * <p>Both calls arrive on the player's threads and the read on the main thread, so the list is
 * guarded. Players and parents are held weakly: a player TikTok lets go of drops out on its own.
 */
public final class PlayerLayers {
    /** Players kept at once. TikTok keeps a few cells' players warm, never close to this many. */
    static final int MAX_PLACED = 32;

    /** A player's layer: the Surface it draws into and the size of that Surface's buffer. */
    public static final class Layer {
        public final Surface surface;
        public final int width;
        public final int height;

        Layer(Surface surface, int width, int height) {
            this.surface = surface;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Placed {
        final WeakReference<Object> player;
        final WeakReference<Object> parent;
        final String parentName;
        public final int width;
        public final int height;
        final boolean visible;

        Placed(Object player, Object parent, int width, int height, boolean visible) {
            this.player = new WeakReference<>(player);
            this.parent = new WeakReference<>(parent);
            this.parentName = layerName(parent);
            this.width = width;
            this.height = height;
            this.visible = visible;
        }
    }

    /** Oldest first, so the newest placement under a parent is found first from the end. */
    private static final List<Placed> placed = new ArrayList<>();

    private PlayerLayers() {
    }

    /**
     * Hooked first thing in the player's reparent helper: the player's layer moves under
     * {@code parent} with a buffer of {@code width} by {@code height}. A null parent leaves the
     * layer where it hangs and only resizes, shows or hides it, as the helper does.
     */
    public static void reparented(Object player, Object parent, int width, int height, boolean visible) {
        try {
            Object under;
            synchronized (placed) {
                under = parent != null ? parent : parentOf(player);
                forget(player);
                if (player == null || under == null) return;
                placed.add(new Placed(player, under, width, height, visible));
                while (placed.size() > MAX_PLACED) placed.remove(0);
            }
            Logger.printDebug(() -> "Player layer " + width + "x" + height + (visible ? "" : ", hidden,")
                    + " under " + layerName(under));
        } catch (RuntimeException unexpected) {
            // The player's own thread called in; whatever happens here mustn't reach it.
            Logger.printInfo(() -> "Could not note where the player draws: " + unexpected);
        }
    }

    /** Hooked first thing in the Android 13 reparent helper, which always shows the layer. */
    public static void shown(Object player, Object parent, int width, int height) {
        reparented(player, parent, width, height, true);
    }

    /** Hooked first thing in the player's recycle helper: the layer is taken down. */
    public static void detached(Object player) {
        try {
            synchronized (placed) {
                forget(player);
            }
        } catch (RuntimeException unexpected) {
            Logger.printInfo(() -> "Could not note the player's layer going: " + unexpected);
        }
    }

    /**
     * Hooked just before the LIVE player points its stream at {@code surface}, the Surface of the
     * layer it hangs under {@code parent} at the parent's size. A null parent takes the layer down.
     */
    public static void live(Object parent, Surface surface, int width, int height) {
        if (parent == null) {
            detached(surface);
        } else {
            reparented(surface, parent, width, height, true);
        }
    }

    /** The player layer drawing under {@code view}, or null when the player draws into the view. */
    public static Layer under(SurfaceView view) {
        // SurfaceView hands out its SurfaceControl from Android 10, the first TikTok builds one on.
        if (Build.VERSION.SDK_INT < 29 || view == null) return null;
        Object own;
        try {
            own = view.getSurfaceControl();
        } catch (RuntimeException gone) {
            return null;
        }
        Layer layer = placedUnder(own);
        if (layer == null) {
            int count;
            synchronized (placed) {
                count = placed.size();
            }
            Logger.printDebug(() -> "No player layer under " + layerName(own) + " among " + count + " placed");
        }
        return layer;
    }

    /**
     * The layer placed under {@code parent}: the newest one showing, else the newest at all, and
     * only while its Surface is still valid. The player hands over the same SurfaceControl the view
     * holds, so the match is by identity, with the layer's name as the fallback for a second Java
     * object on the same layer.
     */
    static Layer placedUnder(Object parent) {
        if (parent == null) return null;
        String name = layerName(parent);
        Layer hidden = null;
        synchronized (placed) {
            for (int i = placed.size() - 1; i >= 0; i--) {
                Placed entry = placed.get(i);
                Object player = entry.player.get();
                if (player == null) {
                    placed.remove(i);
                    continue;
                }
                Object at = entry.parent.get();
                if (at != parent && (name == null || !name.equals(entry.parentName))) continue;
                Surface surface = surfaceOf(player);
                if (surface == null || !surface.isValid()) continue;
                Layer layer = new Layer(surface, entry.width, entry.height);
                if (entry.visible) return layer;
                if (hidden == null) hidden = layer;
            }
        }
        return hidden;
    }

    private static Object parentOf(Object player) {
        if (player == null) return null;
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placed entry = placed.get(i);
            if (entry.player.get() == player) return entry.parent.get();
        }
        return null;
    }

    private static void forget(Object player) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Object held = placed.get(i).player.get();
            if (held == null || held == player) placed.remove(i);
        }
    }

    private static Surface surfaceOf(Object player) {
        if (player instanceof Surface) return (Surface) player;
        Object surface = Reflect.property(player, "getSurface", "mSurface");
        return surface instanceof Surface ? (Surface) surface : null;
    }

    /**
     * The layer's name out of SurfaceControl's {@code Surface(name=...)/@0x...}, or null for any
     * other shape. The view's layer names carry the view's own hash, so two views never share one.
     */
    static String layerName(Object layer) {
        String text = String.valueOf(layer);
        int start = text.indexOf("name=");
        int end = text.lastIndexOf(")/@");
        if (!text.startsWith("Surface(") || start < 0 || end <= start + 5) return null;
        return text.substring(start + 5, end);
    }
}
