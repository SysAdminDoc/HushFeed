/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Has TikTok draw feed video inside its own window when See-through bottom bar asks for it (#232).
 *
 * <p>TikTok picks the player's view by one App AB int, {@link #SURFACE_VIEW_GATE}: 1 puts the video
 * on a SurfaceView, a layer of its own under the window, and 0 draws it through a TextureView into
 * the window. With the bar made clear, the video slides under it on every swipe, and an Honor X9c
 * takes that layer off the display's overlay plane for a frame each time ("apply to client compose"
 * on the navigation bar or on TTPlayerVideoLayer in its SurfaceFlinger log), which blinks the
 * screen. Drawn into the window there is no such layer to move. On Login2, 1 came from the server,
 * and with 0 answered here the feed ran with no SurfaceView layer at all and played on.
 *
 * <p>Five places read the key (the player config, the feed fragment and three more), all through
 * the App AB int getter, which is why the hook sits there rather than on any one of them. TikTok
 * reads it as the player starts, so the switch asks for a restart.
 */
public final class SeeThroughVideoLayer {
    /** TikTok's App AB key for a SurfaceView player: 1 a SurfaceView, 0 a TextureView. */
    static final String SURFACE_VIEW_GATE = "player_enable_surface_surfaceview";

    private static volatile boolean logged;

    private SeeThroughVideoLayer() {}

    /**
     * Every App AB int TikTok reads passes here. Only the video view gate is answered, and only
     * with both switches on; paused, both read off.
     */
    public static int videoSurfaceGate(String key, int value) {
        if (value == 0 || !SURFACE_VIEW_GATE.equals(key)) return value;
        if (!Settings.SEE_THROUGH_BOTTOM_BAR.get() || !Settings.SEE_THROUGH_VIDEO_IN_WINDOW.get()) {
            return value;
        }
        if (!logged) {
            logged = true;
            Logger.printInfo(() -> "Feed video drawn in the app's window: " + key + " came back " + value);
        }
        return 0;
    }

    /** Forgets the one log line, so a test can watch it again. */
    static void resetForTests() {
        logged = false;
    }
}
