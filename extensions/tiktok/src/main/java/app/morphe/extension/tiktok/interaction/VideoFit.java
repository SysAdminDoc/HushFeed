/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.interaction;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Puts the whole of a video on screen instead of cropping it to the window.
 *
 * <p>TikTok works out how big the video view should be and keeps the answer in a real named
 * {@code VideoAdaptionResult}, which the feed cell then writes into the view's layout
 * parameters. On a 9:16 phone the answer fills the window exactly. On anything squarer, a Fold
 * opened up, a Titan, a Clicks, split view, the answer is larger than the window in one
 * direction and the sides or the ends are cut off. Same shape, same arithmetic, but taking the
 * smaller scale rather than the larger one, so the video sits inside the window whole.
 */
public final class VideoFit {
    private VideoFit() {}

    /**
     * What the five switches ask for: the whole video inside the window, the window covered by
     * cropping, the window covered by stretching the whole video to it (#406), the window covered
     * by a little of each (#421), the window covered with the middle of the video at its own
     * shape (#421), or nothing.
     */
    enum Mode { LEAVE_ALONE, FIT, FILL, STRETCH, BLEND, KEEP_MIDDLE }

    /**
     * Fit wins when more than one switch is on, then fill. The page keeps them apart, and a
     * restored backup that carries two should show the whole video rather than crop it. Keep the
     * middle needs Android 13 for its shader, and below that crops and stretches like blend.
     */
    static Mode mode() {
        if (Settings.FIT_VIDEO_TO_SCREEN.get()) {
            return Settings.FIT_FILLS_TALL_SCREEN.get() && tallWindow() ? Mode.FILL : Mode.FIT;
        }
        if (Settings.FILL_VIDEO_TO_SCREEN.get()) return Mode.FILL;
        if (Settings.STRETCH_VIDEO_TO_SCREEN.get()) return Mode.STRETCH;
        if (Settings.BLEND_VIDEO_TO_SCREEN.get()) return Mode.BLEND;
        if (Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.get()) {
            return KeepMiddle.supported() ? Mode.KEEP_MIDDLE : Mode.BLEND;
        }
        return Mode.LEAVE_ALONE;
    }

    /**
     * Whether TikTok's window is phone shaped, narrower than {@link PhotoSlideFit#SQUARISH} wide
     * over tall: a folded phone's front screen, where Fill a tall screen instead crops to cover,
     * and not the opened inner screen or a phone turned sideways, which keep the whole video
     * (#345). The app's own configuration follows a fold, an unfold and split view as they happen.
     */
    static boolean tallWindow() {
        Context context = Utils.getContext();
        if (context == null) return false;
        android.content.res.Configuration configuration = context.getResources().getConfiguration();
        return tallWindow(configuration.screenWidthDp, configuration.screenHeightDp);
    }

    static boolean tallWindow(int widthDp, int heightDp) {
        return widthDp > 0 && heightDp > 0 && !PhotoSlideFit.squarish(widthDp, heightDp);
    }

    /** Whether the video is shown whole, Fit's way, so a picture it shrank stays whole. */
    public static boolean fitsWhole() {
        return SettingsStatus.videoFitEnabled && mode() == Mode.FIT;
    }

    /**
     * The mode {@code view} is sized by. Keep the middle crops and stretches like blend wherever
     * its shader can't reach the picture: TikTok still playing on a SurfaceView, until a restart
     * lets {@link #videoSurfaceGate} answer. The video and its cover both go by the same answer,
     * so the cover never takes a size the video doesn't.
     */
    static Mode modeFor(View view) {
        Mode mode = mode();
        return mode == Mode.KEEP_MIDDLE && !KeepMiddle.canWarp(view) ? Mode.BLEND : mode;
    }

    /**
     * Whether the video is asked to cover the screen and the patch that does it is in. An opened
     * video keeps a strip under its comment bar that the feed doesn't have, so the overlay pass
     * gives that strip to the video too while this holds (#406).
     */
    public static boolean fillsScreen() {
        if (!SettingsStatus.videoFitEnabled) return false;
        Mode mode = mode();
        return mode == Mode.FILL || mode == Mode.STRETCH || mode == Mode.BLEND || mode == Mode.KEEP_MIDDLE;
    }

    /** Whether the mode changes the video's shape, which the cover then has to follow. */
    static boolean reshapes(Mode mode) {
        return mode == Mode.STRETCH || mode == Mode.BLEND || mode == Mode.KEEP_MIDDLE;
    }

    /**
     * Every App AB int TikTok reads passes here (#421). Only {@link KeepMiddle#SURFACE_VIEW_GATE}
     * and search's {@link KeepMiddle#SEARCH_SURFACE_VIEW_GATE} are answered while Keep the middle
     * is on, so TikTok plays into a TextureView the window draws and the shader reaches. One
     * SurfaceView anywhere leaves every video cropped and stretched until a restart (#468), so
     * search gets its low bit taken off too. Whatever answer the feed's key ends up with is noted,
     * the see-through bottom bar's own 0 included, since that hook may run after this one.
     */
    public static int videoSurfaceGate(String key, int value) {
        if (KeepMiddle.SEARCH_SURFACE_VIEW_GATE.equals(key)) {
            if ((value & 1) == 0 || !KeepMiddle.wanted()) return value;
            Logger.printInfo(() -> "Search video drawn in the app's window to keep its middle unstretched: "
                    + key + " came back " + value);
            return value & ~1;
        }
        if (!KeepMiddle.SURFACE_VIEW_GATE.equals(key)) return value;
        int answer = value;
        if (answer != 0 && KeepMiddle.wanted()) {
            answer = 0;
            Logger.printInfo(() -> "Feed video drawn in the app's window to keep its middle unstretched: "
                    + key + " came back " + value);
        }
        if (answer == 0 || (Settings.SEE_THROUGH_BOTTOM_BAR.get() && Settings.SEE_THROUGH_VIDEO_IN_WINDOW.get())) {
            KeepMiddle.videoInWindow = true;
        }
        return answer;
    }

    /** Draws a video kept in the middle with the shader, and gives any other one its own drawing back. */
    private static void warpFor(Mode mode, View view, int videoWidth, int videoHeight, int width, int height) {
        if (mode == Mode.KEEP_MIDDLE) {
            KeepMiddle.warp(view, videoWidth, videoHeight, width, height);
        } else {
            KeepMiddle.clear(view);
        }
    }

    /** What the last decision on this thread settled: which result, the height for it and where it sits. */
    private static final class Fitted {
        final Object result;
        final int height;
        /** The offsets that centre it, in the order the host asks: across, then down. */
        final float[] offsets;
        int offsetsRemaining = 2;

        Fitted(Object result, int height, float[] offsets) {
            this.result = result;
            this.height = height;
            this.offsets = offsets;
        }
    }

    /**
     * The height that goes with the width last handed back, and the result it was for.
     *
     * <p>One slot per thread rather than a map keyed by the result. The result is a data class
     * whose {@code hashCode} is built from its width and height, and the width is written before
     * the height is asked for, so a map would look under the old hash and find nothing. The
     * result is told apart by identity, and the three reads happen back to back on the thread
     * that made the decision.
     */
    private static final ThreadLocal<Fitted> LAST = new ThreadLocal<>();

    /**
     * The result the feed cell should apply: a copy sized to sit inside the window when the
     * switch is on and the video overflows it, and otherwise the result TikTok chose.
     *
     * <p>The feed cell does not size its video through {@code saveResultInner}; it hands the
     * result and the view to a pair of static helpers, one that writes the size and offsets into
     * the view and one that says whether the view already matches. Both get the result through
     * this, so the size they apply and the size they compare against are the same one. A copy is
     * handed back rather than the result changed in place, because the result is not this code's
     * to write and the copy is what the class offers for exactly that.
     *
     * <p>Anything that cannot be read or does not add up hands TikTok's own result back.
     */
    public static Object fitted(View view, Object result) {
        if (view == null || result == null) return result;
        try {
            // The switches before the reads: this runs twice for every video the feed binds, off
            // is the default, and the reflection below is only worth paying for with one on.
            Mode mode = modeFor(view);
            if (mode == Mode.LEAVE_ALONE) return untouched(view, result);
            int videoWidth = size(result, "getWidth");
            int videoHeight = size(result, "getHeight");
            if (videoWidth <= 0 || videoHeight <= 0) return untouched(view, result);
            int containerWidth = containerWidth(view);
            int containerHeight = containerHeight(view);
            if (containerWidth <= 0 || containerHeight <= 0) return untouched(view, result);
            if (!wants(mode, videoWidth, videoHeight, containerWidth, containerHeight)) {
                note("feed", mode, view, videoWidth, videoHeight, containerWidth, containerHeight, LEAVE, LEAVE);
                return untouched(view, result);
            }
            int width = widthFor(mode, videoWidth, videoHeight, containerWidth, containerHeight);
            int height = heightFor(mode, videoWidth, videoHeight, containerWidth, containerHeight);
            // Centred by the layout when the container can do it, and by the offsets otherwise.
            boolean centred = centre(view);
            float[] offsets = centred
                    ? new float[]{0f, 0f} : offsets(width, height, containerWidth, containerHeight);
            Object copy = copyOf(result, width, height, offsets);
            if (copy == null) return untouched(view, result);
            note("feed", mode, view, videoWidth, videoHeight, containerWidth, containerHeight, width, height);
            if (centred) watch(view, videoWidth, videoHeight, width, height);
            shapeCover(view, reshapes(mode));
            warpFor(mode, view, videoWidth, videoHeight, width, height);
            return copy;
        } catch (Exception exception) {
            Logger.printException(() -> "Could not fit the video to the window", exception);
            return untouched(view, result);
        }
    }

    /**
     * The result again at the given size, with no offsets: the offsets are what centre a video
     * that is larger than the window, and one that fits has nothing hanging over an edge. The
     * operator travels across unchanged. Null when the class has no {@code copy} of the shape a
     * data class generates, which is what says the result is not one this knows how to remake.
     */
    private static Object copyOf(Object result, int width, int height, float[] offsets) {
        Method copy = copyMethodOf(result.getClass());
        if (copy == null) return null;
        Object operator = Reflect.invoke(result, "getResultOperator");
        try {
            return copy.invoke(result, width, height,
                    Float.valueOf(offsets[0]), Float.valueOf(offsets[1]), operator);
        } catch (Exception exception) {
            Logger.printException(() -> "Could not copy the adaption result", exception);
            return null;
        }
    }

    /*
     * Each result class's copy method, or NO_COPY, found once: a bound video asks twice with Fit
     * on, and every ask used to copy out all the class's declared methods.
     */
    private static final Object NO_COPY = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object> COPY_METHODS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Method copyMethodOf(Class<?> type) {
        Object found = COPY_METHODS.get(type);
        if (found == null) {
            found = NO_COPY;
            // What the class declares, made accessible, rather than what it makes public: the copy
            // is the one a data class generates whatever access R8 leaves it or its class with.
            for (Method candidate : type.getDeclaredMethods()) {
                if (!candidate.getName().equals("copy")) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length != 5) continue;
                if (parameters[0] != int.class || parameters[1] != int.class) continue;
                if (parameters[2] != Float.class || parameters[3] != Float.class) continue;
                try {
                    candidate.setAccessible(true);
                    found = candidate;
                } catch (RuntimeException refused) {
                    Logger.printException(() -> "Could not open the adaption result's copy", refused);
                }
                break;
            }
            COPY_METHODS.put(type, found);
        }
        return found == NO_COPY ? null : (Method) found;
    }

    /**
     * A video large enough to fill was pinned wherever the container puts a child that does
     * not fit. A smaller one has to say where it goes, or the bars all end up on one side, and
     * a larger one made here has to say it too, or the crop shows a corner. The host sets the
     * size, not the gravity, so this stays here.
     *
     * @return whether the container centres its children, which a frame does once told to; a
     *         container of another kind is centred through the offsets instead.
     */
    private static boolean centre(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof FrameLayout.LayoutParams)) return false;
        FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) params;
        if (frame.gravity == Gravity.CENTER) return true;
        GRAVITY_BEFORE.put(view, frame.gravity);
        frame.gravity = Gravity.CENTER;
        view.setLayoutParams(frame);
        return true;
    }

    /**
     * The gravity each view had before {@link #centre} replaced it. TikTok reuses a cell's video
     * view, and its layout parameters, for the next video, and lays its own result out with
     * offsets that assume its own gravity: a result left alone on a view centred here for an
     * earlier video was centred twice, and a video TikTok had cropped showed an edge instead of
     * the middle. Weak, because a view that is gone has nothing to give back.
     */
    private static final Map<View, Integer> GRAVITY_BEFORE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Gives a view centred here its own gravity back, for a result left as TikTok made it. Only
     * while the view still carries the centring: a gravity TikTok has set since, or layout
     * parameters it has replaced, are TikTok's own and stay as they are. On the S22, 45 videos with
     * fill on never showed TikTok keeping the centring, so this is for a build or a screen that
     * does.
     */
    private static void uncentre(View view) {
        if (GRAVITY_BEFORE.isEmpty()) return;
        Integer before = GRAVITY_BEFORE.remove(view);
        if (before == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) params;
        if (frame.gravity != Gravity.CENTER) return;
        frame.gravity = before;
        view.setLayoutParams(frame);
    }

    /** TikTok's own result for the feed cell, on a view with its own gravity. */
    private static Object untouched(View view, Object result) {
        unwatch(view);
        uncentre(view);
        shapeCover(view, false);
        KeepMiddle.clear(view);
        return result;
    }

    /** {@link #LEAVE} for the story cell, on a view with its own gravity. */
    private static int leave(View view) {
        unwatch(view);
        uncentre(view);
        shapeCover(view, false);
        KeepMiddle.clear(view);
        return LEAVE;
    }

    // ---------------------------------------------------------------------------------------
    // The cover. The feed hands the cover image the same result as the video, so it gets the
    // same size, but the image inside keeps its shape and crops to that size where the video
    // stretches to it. Stretch on, the cover showed a cropped picture that jumped wider or
    // narrower the moment the stretched video replaced it (#421). A cover the video is stretched
    // over is drawn stretched to its size too, and gets TikTok's crop back once it isn't.

    /** The covers drawn stretched here. Weak, because a cover that is gone has nothing to give back. */
    private static final Map<View, Boolean> STRETCHED_COVERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The scale type TikTok's feed cover is drawn with, which Fresco also starts every image at. */
    static final String COVER_CROP = "CENTER_CROP";
    /** Drawn to the view's own size, whatever the picture's shape. */
    static final String COVER_STRETCH = "FIT_XY";

    /**
     * Draws a cover stretched to its view while the video over it is, and with TikTok's crop
     * again once it isn't. Anything but TikTok's image view is left alone: the video view has no
     * scale type of its own and is stretched by its size alone.
     */
    static void shapeCover(View view, boolean stretched) {
        if (!(view instanceof ImageView)) return;
        try {
            if (stretched) {
                // Set again each time: TikTok's next load into a recycled cover may set its own.
                if (scaleCover(view, COVER_STRETCH)) STRETCHED_COVERS.put(view, Boolean.TRUE);
            } else if (!STRETCHED_COVERS.isEmpty() && STRETCHED_COVERS.remove(view) != null) {
                scaleCover(view, COVER_CROP);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not shape the video cover", failure);
        }
    }

    /** Each image class's scale setter, or NO_SETTER, found once. */
    private static final Object NO_SETTER = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object> SCALE_SETTERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sets the image's scale type by its name, through the setter TikTok's image view has for
     * one: {@code setActualImageScaleType}, taking an enum whose constants kept Fresco's names.
     * False for a view without one, or an enum without the name.
     */
    static boolean scaleCover(View view, String scale) throws ReflectiveOperationException {
        Method setter = scaleSetterOf(view.getClass());
        if (setter == null) return false;
        for (Object constant : setter.getParameterTypes()[0].getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(scale)) {
                setter.invoke(view, constant);
                return true;
            }
        }
        return false;
    }

    private static Method scaleSetterOf(Class<?> type) {
        Object found = SCALE_SETTERS.get(type);
        if (found == null) {
            found = NO_SETTER;
            for (Method candidate : type.getMethods()) {
                if (!candidate.getName().equals("setActualImageScaleType")) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 1 && parameters[0].isEnum()) {
                    found = candidate;
                    break;
                }
            }
            SCALE_SETTERS.put(type, found);
        }
        return found == NO_SETTER ? null : (Method) found;
    }

    // ---------------------------------------------------------------------------------------
    // A fit worked out against a space that then changed size. TikTok works its result out for
    // the space it means to give the video and can hand it over before that space has been laid
    // out at the new size, so the size read here is the old one. #29's Pixel 9 showed a video
    // fitted to 1500 pixels of height and centred in about 2213. TikTok doesn't hand the result
    // over again once the layout catches up, because its own result hasn't changed, so the space
    // around a fitted video is watched, and a new size works the fit out again from TikTok's size.

    /** What a fitted video was given, and the space and TikTok size it was worked out from. */
    static final class Watch implements View.OnLayoutChangeListener {
        // Both weak: the watch is kept in a map keyed weakly by the video, and the space holds
        // the video, so a strong reference to either would keep the entry alive for good.
        final WeakReference<View> video;
        final WeakReference<View> space;
        final int videoWidth;
        final int videoHeight;
        int width;
        int height;
        int spaceWidth;
        int spaceHeight;

        Watch(View video, View space, int videoWidth, int videoHeight, int width, int height) {
            this.video = new WeakReference<>(video);
            this.space = new WeakReference<>(space);
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.width = width;
            this.height = height;
            this.spaceWidth = space.getWidth();
            this.spaceHeight = space.getHeight();
        }

        @Override
        public void onLayoutChange(View space, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            // A watch whose video left this space goes first, whatever size the pass reported:
            // a recycled page that never changes size again would keep it forever otherwise.
            View view = video.get();
            if (view == null || view.getParent() != space) {
                space.removeOnLayoutChangeListener(this);
                return;
            }
            int width = right - left;
            int height = bottom - top;
            if (width <= 0 || height <= 0 || (width == spaceWidth && height == spaceHeight)) return;
            // After the layout pass that reported the size, not inside it.
            Utils.runOnMainThread(() -> refit(view, this));
        }
    }

    private static final Map<View, Watch> WATCHES = Collections.synchronizedMap(new WeakHashMap<>());

    /** Watches the space a centred video was just fitted in, in place of any earlier watch on it. */
    private static void watch(View view, int videoWidth, int videoHeight, int width, int height) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof View)) return;
        unwatch(view);
        View space = (View) parent;
        Watch watch = new Watch(view, space, videoWidth, videoHeight, width, height);
        space.addOnLayoutChangeListener(watch);
        WATCHES.put(view, watch);
    }

    private static void unwatch(View view) {
        if (WATCHES.isEmpty()) return;
        Watch watch = WATCHES.remove(view);
        if (watch == null) return;
        View space = watch.space.get();
        if (space != null) space.removeOnLayoutChangeListener(watch);
    }

    /**
     * Works the fit out again for the space's new size and gives the video that size. Only while
     * a switch is still on and the video still has exactly the size this gave it: a size anyone
     * else has given it since is theirs, and TikTok's next pass over the video decides again.
     */
    static void refit(View view, Watch watch) {
        try {
            if (WATCHES.get(view) != watch) return;
            Mode mode = modeFor(view);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (mode == Mode.LEAVE_ALONE || !(params instanceof FrameLayout.LayoutParams)
                    || params.width != watch.width || params.height != watch.height
                    || ((FrameLayout.LayoutParams) params).gravity != Gravity.CENTER) {
                unwatch(view);
                return;
            }
            int spaceWidth = containerWidth(view);
            int spaceHeight = containerHeight(view);
            if (spaceWidth <= 0 || spaceHeight <= 0) return;
            watch.spaceWidth = spaceWidth;
            watch.spaceHeight = spaceHeight;
            boolean change = wants(mode, watch.videoWidth, watch.videoHeight, spaceWidth, spaceHeight);
            int width = change ? widthFor(mode, watch.videoWidth, watch.videoHeight, spaceWidth, spaceHeight) : watch.videoWidth;
            int height = change ? heightFor(mode, watch.videoWidth, watch.videoHeight, spaceWidth, spaceHeight) : watch.videoHeight;
            if (width == params.width && height == params.height) return;
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
            watch.width = width;
            watch.height = height;
            warpFor(change ? mode : Mode.LEAVE_ALONE, view, watch.videoWidth, watch.videoHeight, width, height);
            note("after the space changed", mode, view, watch.videoWidth, watch.videoHeight,
                    spaceWidth, spaceHeight, width, height);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not fit the video to its new space", failure);
        }
    }

    /** The offsets that centre a video of this size in the container: half the difference, each way. */
    static float[] offsets(int width, int height, int containerWidth, int containerHeight) {
        return new float[]{(containerWidth - width) / 2f, (containerHeight - height) / 2f};
    }

    /**
     * The width the video should be, for the one path that sizes a view from inside the result:
     * {@code saveResultInner}, which the story cell uses. That method reads its own fields, so
     * there is no result to swap for a copy and the size is changed in place instead. The fields
     * are final, so nothing out here can write them; the patch does, from inside the class that
     * declares them, and asks here for what to write, or {@link #LEAVE} to write nothing.
     *
     * <p>The matching height is worked out here too and kept for {@link #fittedHeightFor}, because
     * asking twice would mean the second answer was worked out from a width already changed.
     */
    public static int fitWidthFor(Object result, View view) {
        LAST.remove();
        if (view == null || result == null) return LEAVE;
        try {
            Mode mode = modeFor(view);
            if (mode == Mode.LEAVE_ALONE) return leave(view);
            int videoWidth = size(result, "getWidth");
            int videoHeight = size(result, "getHeight");
            if (videoWidth <= 0 || videoHeight <= 0) return leave(view);
            int containerWidth = containerWidth(view);
            int containerHeight = containerHeight(view);
            if (containerWidth <= 0 || containerHeight <= 0) return leave(view);
            // Already inside the window, or already covering it: nothing to bring back or add.
            if (!wants(mode, videoWidth, videoHeight, containerWidth, containerHeight)) {
                note("story", mode, view, videoWidth, videoHeight, containerWidth, containerHeight, LEAVE, LEAVE);
                return leave(view);
            }

            int width = widthFor(mode, videoWidth, videoHeight, containerWidth, containerHeight);
            int height = heightFor(mode, videoWidth, videoHeight, containerWidth, containerHeight);
            boolean centred = centre(view);
            float[] offsets = centred
                    ? new float[]{0f, 0f} : offsets(width, height, containerWidth, containerHeight);
            LAST.set(new Fitted(result, height, offsets));
            note("story", mode, view, videoWidth, videoHeight, containerWidth, containerHeight, width, height);
            if (centred) watch(view, videoWidth, videoHeight, width, height);
            shapeCover(view, reshapes(mode));
            warpFor(mode, view, videoWidth, videoHeight, width, height);
            return width;
        } catch (Exception exception) {
            Logger.printException(() -> "Could not fit the video to the window", exception);
            LAST.remove();
            return leave(view);
        }
    }

    /**
     * What {@link #fitWidthFor} answers when the result is to be left exactly as TikTok wrote
     * it: the switch off, a video that already fits, or anything it could not read. The patch
     * branches past every write on it, so none of the host's fields is touched on that path.
     * It used to answer the width it had read, which was 0 for a result it could not read, and
     * the patch wrote that back: a build that renamed the getters would have laid every story
     * out at nothing by nothing.
     */
    public static final int LEAVE = -1;

    /**
     * The height that goes with the width already handed back, or the one TikTok chose.
     *
     * <p>Left in place rather than taken, because the two offsets are asked about after this and
     * the entry is what says the video was fitted. The second offset read clears it.
     */
    public static int fittedHeightFor(Object result) {
        Fitted fitted = fittedFor(result);
        return fitted != null ? fitted.height : size(result, "getHeight");
    }

    /**
     * The offset that centres the video this code sized, and whatever the host had otherwise.
     *
     * <p>TikTok's offsets are what centre a video it cropped. One that fits has nothing hanging
     * over an edge, so those would push it off the other side, and one cropped here needs its
     * own. The host asks twice, across and then down, and the second answer ends the decision.
     */
    public static Float fittedTranslation(Object result, Float translation) {
        Fitted fitted = fittedFor(result);
        if (fitted == null) return translation;
        float offset = fitted.offsets[2 - fitted.offsetsRemaining];
        if (--fitted.offsetsRemaining == 0) LAST.remove();
        return Float.valueOf(offset);
    }

    /** The decision made for exactly this result on this thread, if the last one was for it. */
    private static Fitted fittedFor(Object result) {
        Fitted fitted = LAST.get();
        return fitted != null && result != null && fitted.result == result ? fitted : null;
    }

    private static int size(Object result, String getter) {
        if (result == null) return 0;
        Object value = Reflect.invoke(result, getter);
        return value instanceof Integer ? (Integer) value : 0;
    }

    /**
     * Whether the video at this size needs changing: it overflows for fit, or leaves a gap for
     * fill. Fill only takes a video standing the same way as the window. A wide video on a tall
     * window covered it by losing three quarters of its width (#394), and a square one more than
     * half; the switch is for the strip under a tall video, and the rest stay whole.
     */
    static boolean wants(Mode mode, int width, int height, int containerWidth, int containerHeight) {
        if (mode == Mode.FIT) return width > containerWidth || height > containerHeight;
        if (mode == Mode.FILL) {
            return sameWay(width, height, containerWidth, containerHeight)
                    && (width < containerWidth || height < containerHeight);
        }
        if (mode == Mode.STRETCH) {
            return sameWay(width, height, containerWidth, containerHeight)
                    && closeInShape(width, height, containerWidth, containerHeight, MAX_STRETCH)
                    && (width != containerWidth || height != containerHeight);
        }
        if (mode == Mode.BLEND) {
            // Only half the gap is stretched, so the shapes may be twice as far apart, as a
            // ratio, before that half is as much as stretch takes on its own.
            return sameWay(width, height, containerWidth, containerHeight)
                    && closeInShape(width, height, containerWidth, containerHeight, MAX_STRETCH * MAX_STRETCH)
                    && (blendWidth(width, height, containerWidth, containerHeight) != width
                    || blendHeight(width, height, containerWidth, containerHeight) != height);
        }
        if (mode == Mode.KEEP_MIDDLE) {
            // The shader makes up a gap in height only, the strip under a tall video, and no
            // more of it than stretching the edge twice as tall covers.
            return sameWay(width, height, containerWidth, containerHeight)
                    && KeepMiddle.plan(width, height, containerWidth, containerHeight) != null
                    && (width != containerWidth || height != containerHeight);
        }
        return false;
    }

    /**
     * How far stretch pulls a video out of shape before it leaves the video alone. A 9:16 video
     * on a 20:9 phone is a quarter off, and past a third faces look plainly squashed.
     */
    static final double MAX_STRETCH = 4.0 / 3.0;

    /** Whether the two shapes are within {@code limit} of each other, either way. */
    static boolean closeInShape(int width, int height, int containerWidth, int containerHeight, double limit) {
        double video = width / (double) height;
        double space = containerWidth / (double) containerHeight;
        double apart = video > space ? video / space : space / video;
        return apart <= limit + 1e-9;
    }

    /** Both taller than wide, or both wider than tall. A square either way stands neither. */
    static boolean sameWay(int width, int height, int containerWidth, int containerHeight) {
        return (height > width && containerHeight > containerWidth)
                || (width > height && containerWidth > containerHeight);
    }

    /** Stretch takes the window's own size, each side scaled on its own. */
    static int widthFor(Mode mode, int width, int height, int containerWidth, int containerHeight) {
        // Keep the middle is laid out at the window's size too, and its shader draws the shape back.
        if (mode == Mode.STRETCH || mode == Mode.KEEP_MIDDLE) return containerWidth;
        if (mode == Mode.BLEND) return blendWidth(width, height, containerWidth, containerHeight);
        return mode == Mode.FILL
                ? fillWidth(width, height, containerWidth, containerHeight)
                : fitWidth(width, height, containerWidth, containerHeight);
    }

    static int heightFor(Mode mode, int width, int height, int containerWidth, int containerHeight) {
        if (mode == Mode.STRETCH || mode == Mode.KEEP_MIDDLE) return containerHeight;
        if (mode == Mode.BLEND) return blendHeight(width, height, containerWidth, containerHeight);
        return mode == Mode.FILL
                ? fillHeight(width, height, containerWidth, containerHeight)
                : fitHeight(width, height, containerWidth, containerHeight);
    }

    /** The width the video needs to cover the container without changing shape: the larger scale. */
    static int fillWidth(int width, int height, int containerWidth, int containerHeight) {
        if (wider(width, height, containerWidth, containerHeight)) {
            return atLeastOne(Math.round(containerHeight * (double) width / height));
        }
        return containerWidth;
    }

    /** The matching height. */
    static int fillHeight(int width, int height, int containerWidth, int containerHeight) {
        if (wider(width, height, containerWidth, containerHeight)) return containerHeight;
        return atLeastOne(Math.round(containerWidth * (double) height / width));
    }

    /**
     * The shape a blend gives the video: halfway between the video's own and the window's, as a
     * ratio, so the stretch from the video's shape to it and the crop from it to the window are
     * the same amount. A 9:16 video on a 20:9 phone is a quarter off; blended it is cropped by
     * about a ninth and stretched by about a ninth, where fill crops a quarter and stretch
     * stretches a quarter.
     */
    static double blendShape(int width, int height, int containerWidth, int containerHeight) {
        return Math.sqrt((width / (double) height) * (containerWidth / (double) containerHeight));
    }

    /** The width of the box in the blended shape that covers the container. */
    static int blendWidth(int width, int height, int containerWidth, int containerHeight) {
        double shape = blendShape(width, height, containerWidth, containerHeight);
        if (shape * containerHeight > containerWidth) {
            return atLeastOne(Math.round(containerHeight * shape));
        }
        return containerWidth;
    }

    /** The matching height. */
    static int blendHeight(int width, int height, int containerWidth, int containerHeight) {
        double shape = blendShape(width, height, containerWidth, containerHeight);
        if (shape * containerHeight > containerWidth) return containerHeight;
        return atLeastOne(Math.round(containerWidth / shape));
    }

    /** The width the video needs to sit inside the container without changing shape. */
    static int fitWidth(int width, int height, int containerWidth, int containerHeight) {
        if (wider(width, height, containerWidth, containerHeight)) return containerWidth;
        return atLeastOne(Math.round(containerHeight * (double) width / height));
    }

    /** The matching height. */
    static int fitHeight(int width, int height, int containerWidth, int containerHeight) {
        if (wider(width, height, containerWidth, containerHeight)) {
            return atLeastOne(Math.round(containerWidth * (double) height / width));
        }
        return containerHeight;
    }

    /**
     * Whether the video is the wider shape of the two, which is what decides the side that
     * touches. Cross multiplied so the comparison stays exact.
     */
    private static boolean wider(int width, int height, int containerWidth, int containerHeight) {
        return (long) width * containerHeight > (long) height * containerWidth;
    }

    private static int atLeastOne(long value) {
        return value < 1 ? 1 : (int) value;
    }

    /**
     * How many levels up to look for something that has been measured. The video sits a
     * couple of frames inside the cell, and past that the answer stops being the cell.
     */
    private static final int LEVELS = 4;

    /**
     * The window the video has to fit in. The nearest laid out ancestor is the cell, which
     * fills the window; on a first bind nothing above it has a size yet, and the fallback is
     * the view's own resources rather than the application's, because in split view only the
     * activity's configuration reports the half of the screen TikTok actually has.
     */
    private static int containerWidth(View view) {
        int width = measuredAncestor(view, true);
        if (width > 0) return width;
        return metrics(view, true);
    }

    private static int containerHeight(View view) {
        int height = measuredAncestor(view, false);
        if (height > 0) return height;
        return metrics(view, false);
    }

    private static int measuredAncestor(View view, boolean horizontal) {
        ViewParent parent = view.getParent();
        for (int level = 0; level < LEVELS && parent instanceof View; level++) {
            View candidate = (View) parent;
            int size = horizontal ? candidate.getWidth() : candidate.getHeight();
            if (size > 0) return size;
            parent = candidate.getParent();
        }
        return 0;
    }

    private static int metrics(View view, boolean horizontal) {
        Context context = view.getContext();
        if (context == null) context = Utils.getContext();
        if (context == null) return 0;
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        return horizontal ? display.widthPixels : display.heightPixels;
    }

    // ---------------------------------------------------------------------------------------
    // The diagnostic report. A screenshot shows that a fit went wrong but not what it was
    // measured against: #29's Pixel 9 showed a 9:16 video fitted to 1500 pixels of height and
    // centred in about 2213, so something above the video was 1500 tall when the fit was worked
    // out. The last few decisions are kept with the size of every view above the video.

    /** How many decisions the report keeps. */
    static final int KEPT = 8;
    /** How far up the report walks, which reaches past the levels the fit itself looks at. */
    private static final int CHAIN_LEVELS = 8;

    /** One decision line and how many times in a row it came out the same. */
    private static final class Decision {
        final String line;
        int times = 1;

        Decision(String line) {
            this.line = line;
        }
    }

    private static final ArrayDeque<Decision> DECISIONS = new ArrayDeque<>();
    private static final AtomicLong RESIZED = new AtomicLong();
    private static final AtomicLong LEFT = new AtomicLong();
    private static volatile boolean reporting;

    /**
     * Keeps one decision made with a switch on. {@code width} is {@link #LEAVE} for a video left
     * at TikTok's size. The feed asks twice for every video it binds, once to apply and once to
     * compare, so a decision the same as the one before it adds to that one's count.
     */
    private static void note(String cell, Mode mode, View view, int videoWidth, int videoHeight,
                             int spaceWidth, int spaceHeight, int width, int height) {
        try {
            if (!reporting) {
                reporting = true;
                LogBufferManager.registerReportSection(Report.INSTANCE);
            }
            String label = mode == Mode.FIT ? "Fit" : mode == Mode.FILL ? "Fill"
                    : mode == Mode.BLEND ? "Crop and stretch"
                    : mode == Mode.KEEP_MIDDLE ? "Keep the middle" : "Stretch";
            String line = label + ", " + cell + ": TikTok's size "
                    + videoWidth + "x" + videoHeight + ", space " + spaceWidth + "x" + spaceHeight
                    + (width == LEAVE ? ", left as it was" : ", made " + width + "x" + height)
                    + ". Above the video: " + chain(view);
            (width == LEAVE ? LEFT : RESIZED).incrementAndGet();
            synchronized (DECISIONS) {
                Decision last = DECISIONS.peekLast();
                if (last != null && last.line.equals(line)) {
                    last.times++;
                    return;
                }
                if (DECISIONS.size() == KEPT) DECISIONS.removeFirst();
                DECISIONS.addLast(new Decision(line));
            }
        } catch (Throwable ignored) {
            // A decision missing from the report beats a layout pass that throws.
        }
    }

    /** Each view above the video with its size, up to the page the feed scrolls, then the window. */
    static String chain(View view) {
        StringBuilder out = new StringBuilder();
        ViewParent parent = view.getParent();
        for (int level = 0; level < CHAIN_LEVELS && parent instanceof View; level++) {
            View above = (View) parent;
            if (out.length() > 0) out.append(", ");
            out.append(nameOf(above.getClass())).append(' ')
                    .append(above.getWidth()).append('x').append(above.getHeight());
            parent = above.getParent();
            if (scrollsPages(parent)) {
                out.append(" (the page)");
                break;
            }
        }
        View root = view.getRootView();
        if (root != null && root != view) {
            out.append(out.length() > 0 ? "; " : "").append("window ")
                    .append(root.getWidth()).append('x').append(root.getHeight());
        }
        return out.length() > 0 ? out.toString() : "nothing";
    }

    private static String nameOf(Class<?> type) {
        String name = type.getSimpleName();
        return name.isEmpty() ? type.getName() : name;
    }

    /** Whether this is the list or pager the feed's pages sit in, by the class it extends. */
    private static boolean scrollsPages(ViewParent parent) {
        for (Class<?> type = parent == null ? null : parent.getClass();
             type != null && type != Object.class; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.endsWith("ViewPager") || name.endsWith("ViewPager2") || name.endsWith("RecyclerView")) {
                return true;
            }
        }
        return false;
    }

    /** The VIDEO FIT section of the diagnostic report. */
    static final class Report implements LogBufferManager.ReportSection {
        static final Report INSTANCE = new Report();

        @Override public String title() {
            return "VIDEO FIT";
        }

        @Override public List<String> lines() {
            List<String> lines = new ArrayList<>();
            // The saved choices: Pause reads them all as off, and says so on a line of its own.
            boolean fit = Settings.FIT_VIDEO_TO_SCREEN.savedValue();
            boolean fill = Settings.FILL_VIDEO_TO_SCREEN.savedValue();
            boolean stretch = Settings.STRETCH_VIDEO_TO_SCREEN.savedValue();
            boolean blend = Settings.BLEND_VIDEO_TO_SCREEN.savedValue();
            boolean middle = Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.savedValue();
            lines.add("Fit the video to the screen: " + (fit ? "on" : "off"));
            lines.add("Fill the screen with the video: " + (fill ? "on" : "off"));
            lines.add("Stretch the video to the screen: " + (stretch ? "on" : "off"));
            lines.add("Crop and stretch to fill the screen: " + (blend ? "on" : "off"));
            lines.add("Keep the middle unstretched: " + (middle ? "on" : "off"));
            if (Settings.FIT_FILLS_TALL_SCREEN.savedValue()) {
                lines.add("Fill a tall screen instead: on"
                        + (tallWindow() ? ", this window is tall" : ", this window is squarish"));
            }
            if ((fit || fill || stretch || blend || middle) && mode() == Mode.LEAVE_ALONE) {
                lines.add("Hushfeed is paused, so videos keep TikTok's size");
            } else if (middle && mode() == Mode.BLEND && !blend) {
                lines.add("Keep the middle needs Android 13, so videos are cropped and stretched instead");
            } else if (middle && mode() == Mode.KEEP_MIDDLE && !KeepMiddle.videoInWindow) {
                lines.add("TikTok still plays videos on a layer of their own, so they're cropped and "
                        + "stretched until TikTok restarts");
            } else if (middle && mode() == Mode.KEEP_MIDDLE && KeepMiddle.ownLayerSeen) {
                lines.add("TikTok played a video on a layer of its own after the restart, so videos "
                        + "and their covers are cropped and stretched");
            }
            // Counted per check, and the feed checks twice for each video it shows.
            lines.add("Fit checks since TikTok started: " + RESIZED.get() + " resized the video, "
                    + LEFT.get() + " left it at TikTok's size");
            synchronized (DECISIONS) {
                if (!DECISIONS.isEmpty()) lines.add("The last " + DECISIONS.size() + ", oldest first:");
                for (Decision decision : DECISIONS) {
                    lines.add(decision.times == 1 ? decision.line
                            : decision.line + " (" + decision.times + " in a row)");
                }
            }
            return lines;
        }
    }

    static void resetReportForTests() {
        synchronized (DECISIONS) {
            DECISIONS.clear();
        }
        RESIZED.set(0);
        LEFT.set(0);
    }
}
