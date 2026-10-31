/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.app.Activity;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.CoverSaver;
import app.morphe.extension.tiktok.settings.L10n;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Create on the comment panel's saved stickers page (#156): pick a photo and it becomes one of your
 * saved stickers, the way TikTok's own Create sticker does. Make sticker in a video's share sheet
 * (#88) does the same with the video's cover.
 *
 * <p>The app ships everything this needs except the button. The DM canvas saves a drawing as a
 * sticker through {@code im/sticker/save} and then adds it to Saved, and the comment sticker
 * uploader asks {@code im/upload_config} for an ImageX token (upload scene 2). The patch copies
 * those calls into the bridges at the bottom of this class. This class adds the button, reads the
 * photo, shrinks it to sticker size and runs the upload, the save and the add on a worker thread.
 */
@SuppressWarnings("unused")
public final class StickerCreator {
    /** A photo sticker's type on {@code im/sticker/save}. The canvas saves 13, a comment photo 15. */
    static final int PHOTO_STICKER = 9;
    /** The longest side a sticker keeps. */
    static final int MAX_SIDE = 512;
    static final String PILL_TAG = "hushfeed_sticker_create";
    static final String PICKER_TAG = "hushfeed_sticker_picker";
    static final String UPLOAD_CONFIG = "com.ss.android.ugc.aweme.subscription.pgc.jsbridge.upload.StickerImageAuthConfig";
    private static final String UPLOADER = "com.ss.bduploader.BDImageXUploader";
    private static final String UPLOADER_LISTENER = "com.ss.bduploader.BDImageXUploaderListener";
    /** BDImageXUploader's notices: the file is up, or the upload gave up. */
    private static final int UPLOAD_DONE = 6;
    private static final int UPLOAD_FAILED = 7;
    private static final long WAIT_SECONDS = 90;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "hushfeed-sticker");
        thread.setDaemon(true);
        return thread;
    });

    private StickerCreator() {
    }

    /** Called with the saved stickers page as PineDrama builds it. */
    public static void onFavouritesPage(View page) {
        try {
            if (HushfeedPause.isPaused() || !(page instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) page;
            if (root.findViewWithTag(PILL_TAG) != null) return;
            ViewGroup grid = findGrid(root);
            if (grid == null) return;
            TextView pill = createPill(root.getContext());
            pill.setOnClickListener(StickerCreator::pick);
            // The page is a ConstraintLayout, which puts a child with no constraints at its top
            // left; place moves it level with the grid's Saved heading from there.
            root.addView(pill, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Runnable place = () -> place(grid, pill);
            ViewTreeObserver.OnScrollChangedListener scrolled = place::run;
            root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> place.run());
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    view.getViewTreeObserver().addOnScrollChangedListener(scrolled);
                    place.run();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    view.getViewTreeObserver().removeOnScrollChangedListener(scrolled);
                }
            });
            if (root.isAttachedToWindow()) root.getViewTreeObserver().addOnScrollChangedListener(scrolled);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not add Create to the saved stickers", error);
        }
    }

    /**
     * Keeps Create at the right end of the Saved heading's row, and hides it once the grid scrolls
     * the top away. Recently used puts its rows above that heading, so Create skips them. An empty
     * grid has no heading, so Create sits at its top.
     */
    static void place(ViewGroup grid, TextView pill) {
        try {
            boolean atTop = !grid.canScrollVertically(-1);
            int above = StickerRecents.rowsAbove();
            View heading = grid.getChildCount() > above ? grid.getChildAt(above) : null;
            float y = heading != null
                    ? grid.getTop() + heading.getTop() + (heading.getHeight() - pill.getHeight()) / 2f
                    : grid.getTop() + dp(grid.getContext(), 8);
            pill.setTranslationX(grid.getRight() - pill.getWidth());
            pill.setTranslationY(Math.max(0f, y));
            // Until the grid has drawn past Recently used, the top would put Create on that heading.
            int visibility = atTop && (heading != null || above == 0) ? View.VISIBLE : View.INVISIBLE;
            if (pill.getVisibility() != visibility) pill.setVisibility(visibility);
        } catch (Throwable error) {
            pill.setVisibility(View.INVISIBLE);
            Logger.printException(() -> "Could not place Create", error);
        }
    }

    /** The page's sticker grid: the first RecyclerView under it. */
    static ViewGroup findGrid(ViewGroup root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view != root && isRecyclerView(view.getClass())) return (ViewGroup) view;
            if (!(view instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) queue.add(group.getChildAt(index));
        }
        return null;
    }

    private static boolean isRecyclerView(Class<?> type) {
        for (Class<?> current = type; current != null && current != View.class; current = current.getSuperclass()) {
            if ("RecyclerView".equals(current.getSimpleName())) return true;
        }
        return false;
    }

    private static TextView createPill(Context context) {
        TextView pill = new TextView(context);
        pill.setTag(PILL_TAG);
        pill.setText(L10n.t(context, "Create"));
        pill.setContentDescription(L10n.t(context, "Make a sticker from a photo"));
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setTextColor(0xFFFFFFFF);
        pill.setSingleLine(true);
        int across = dp(context, 12);
        int down = dp(context, 4);
        pill.setPadding(across, down, across, down);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFE2C55);
        background.setCornerRadius(dp(context, 14));
        pill.setBackground(background);
        return pill;
    }

    private static void pick(View view) {
        Activity activity = activityOf(view.getContext());
        if (activity == null) return;
        if (BUSY.get()) {
            Utils.showToastShort(L10n.t("Making your sticker"));
            return;
        }
        if (activity.getFragmentManager().findFragmentByTag(PICKER_TAG) != null) return;
        activity.getFragmentManager().beginTransaction().add(new Picker(), PICKER_TAG).commitAllowingStateLoss();
    }

    private static Activity activityOf(Context context) {
        for (Context current = context; current instanceof ContextWrapper; current = ((ContextWrapper) current).getBaseContext()) {
            if (current instanceof Activity) return (Activity) current;
        }
        return null;
    }

    /** Android's photo picker on 13 and later, any app that hands out images before that. */
    static Intent pickIntent() {
        if (Build.VERSION.SDK_INT >= 33) {
            Intent intent = new Intent("android.provider.action.PICK_IMAGES");
            intent.setType("image/*");
            return intent;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        return intent;
    }

    /** Opens the picker and hands the photo to {@link #create}, then removes itself. */
    public static final class Picker extends Fragment {
        static final int REQUEST = 0x15C;

        public Picker() {
        }

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            if (state != null) return;
            try {
                startActivityForResult(pickIntent(), REQUEST);
            } catch (RuntimeException error) {
                leave();
                Logger.printException(() -> "Could not open the photo picker", error);
                Utils.showToastShort(L10n.t("Couldn't open your photos. Try again."));
            }
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request != REQUEST) return;
            Activity activity = getActivity();
            leave();
            Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (uri != null && activity != null) create(activity.getApplicationContext(), uri);
        }

        private void leave() {
            if (getFragmentManager() != null) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    /** Makes a sticker from the photo at {@code uri} on the worker thread, one at a time. */
    static void create(Context context, Uri uri) {
        start(() -> make(context, uri));
    }

    /**
     * Make sticker in a video's share sheet (#88): the video's cover becomes one of your saved
     * stickers, since TikTok's own Create sticker needs the sticker module a patched app can't get.
     */
    public static void fromCover(Context context, Object aweme) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        start(() -> {
            File cover = new File(app.getCacheDir(), "hushfeed-sticker-cover-" + System.nanoTime() + ".img");
            try {
                if (!coverFetcher.fetch(app, aweme, cover)) return L10n.t("Couldn't make the sticker. Try again.");
                return make(app, Uri.fromFile(cover));
            } finally {
                if (cover.exists() && !cover.delete()) cover.deleteOnExit();
            }
        });
    }

    interface CoverFetcher {
        boolean fetch(Context context, Object aweme, File target) throws IOException;
    }

    /** CoverSaver's fetch, which a test swaps for a picture on disk. */
    static volatile CoverFetcher coverFetcher = CoverSaver::fetchStill;

    interface Job {
        String run() throws Exception;
    }

    private static void start(Job job) {
        if (!BUSY.compareAndSet(false, true)) {
            Utils.showToastShort(L10n.t("Making your sticker"));
            return;
        }
        Utils.showToastShort(L10n.t("Making your sticker"));
        WORKER.execute(() -> {
            String message;
            try {
                message = job.run();
            } catch (Throwable error) {
                Logger.printException(() -> "Could not make the sticker", error);
                message = L10n.t("Couldn't make the sticker. Try again.");
            } finally {
                BUSY.set(false);
            }
            Utils.showToastShort(message);
        });
    }

    private static String make(Context context, Uri uri) throws Exception {
        Prepared photo = prepare(context, uri);
        if (photo == null) return L10n.t("Couldn't read that photo. Try another one.");
        try {
            String key = upload(photo.file);
            if (key == null) return L10n.t("Couldn't make the sticker. Try again.");
            Object saved = unbox(await(continuation -> saveSticker(PHOTO_STICKER, key, photo.width, photo.height,
                    Collections.emptyList(), null, null, continuation)), value -> hasMethod(value, "getSticker"));
            if (saved == null) return L10n.t("Couldn't make the sticker. Try again.");
            if (Boolean.TRUE.equals(Reflect.invoke(saved, "isCreatorBanned"))) return L10n.t("This account can't make stickers");
            Object sticker = Reflect.invoke(saved, "getSticker");
            if (sticker == null) {
                Logger.printInfo(() -> "Sticker save answered without a sticker: " + saved.getClass().getName());
                return L10n.t("Couldn't make the sticker. Try again.");
            }
            Utils.runOnMainThread(() -> {
                try {
                    addToSaved(sticker);
                } catch (Throwable error) {
                    Logger.printException(() -> "Could not add the sticker to Saved", error);
                }
            });
            return L10n.t("Sticker added to Saved");
        } finally {
            if (!photo.file.delete()) photo.file.deleteOnExit();
        }
    }

    static final class Prepared {
        final File file;
        final int width;
        final int height;

        Prepared(File file, int width, int height) {
            this.file = file;
            this.width = width;
            this.height = height;
        }
    }

    /** The photo as a PNG no bigger than {@link #MAX_SIDE}, so a transparent photo stays see-through. */
    private static Prepared prepare(Context context, Uri uri) throws IOException {
        Bitmap bitmap = decode(context, uri);
        if (bitmap == null) return null;
        int[] size = fit(bitmap.getWidth(), bitmap.getHeight(), MAX_SIDE);
        if (size[0] <= 0) return null;
        Bitmap scaled = size[0] == bitmap.getWidth() && size[1] == bitmap.getHeight()
                ? bitmap : Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
        File file = new File(context.getCacheDir(), "hushfeed-sticker-" + System.currentTimeMillis() + ".png");
        try (OutputStream out = new FileOutputStream(file)) {
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
        }
        return new Prepared(file, scaled.getWidth(), scaled.getHeight());
    }

    /** The size that keeps the photo's shape with neither side over {@code max}. */
    static int[] fit(int width, int height, int max) {
        if (width <= 0 || height <= 0) return new int[]{0, 0};
        if (width <= max && height <= max) return new int[]{width, height};
        double scale = (double) max / Math.max(width, height);
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    private static Bitmap decode(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder turns the photo upright from its EXIF orientation, which BitmapFactory doesn't.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                int[] size = fit(info.getSize().getWidth(), info.getSize().getHeight(), MAX_SIDE);
                if (size[0] > 0) decoder.setTargetSize(size[0], size[1]);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= MAX_SIDE) options.inSampleSize *= 2;
        try (InputStream in = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    /**
     * Uploads the sticker file through ImageX with the settings PineDrama's comment sticker
     * uploader uses, and returns the file's key. Logs the host and service, never the keys.
     */
    private static String upload(File file) throws Exception {
        Object config = unbox(await(StickerCreator::uploadConfig), value -> UPLOAD_CONFIG.equals(value.getClass().getName()));
        Object token = config == null ? null : Reflect.readField(config, "authToken");
        if (token == null) {
            Logger.printInfo(() -> "No sticker upload token");
            return null;
        }
        String host = (String) Reflect.readField(config, "host");
        String service = (String) Reflect.readField(config, "serviceId");
        Logger.printInfo(() -> "Sticker upload to " + host + " service " + service);
        ClassLoader loader = StickerCreator.class.getClassLoader();
        Object uploader = Class.forName(UPLOADER, true, loader).getConstructor().newInstance();
        try {
            call(uploader, "setOpenBoe", new Class<?>[]{boolean.class}, false);
            call(uploader, "setUploadDomain", new Class<?>[]{String.class}, host);
            call(uploader, "setTopAccessKey", new Class<?>[]{String.class}, Reflect.readField(token, "accessKeyId"));
            call(uploader, "setTopSecretKey", new Class<?>[]{String.class}, Reflect.readField(token, "secretAccessKey"));
            call(uploader, "setTopSessionToken", new Class<?>[]{String.class}, Reflect.readField(token, "sessionToken"));
            call(uploader, "setServiceID", new Class<?>[]{String.class}, service);
            call(uploader, "setNetworkType", new Class<?>[]{int.class, int.class}, 0x193, 1);
            call(uploader, "setNetworkType", new Class<?>[]{int.class, int.class}, 0x194, -1);
            call(uploader, "setSocketNum", new Class<?>[]{int.class}, 2);
            call(uploader, "setFileRetryCount", new Class<?>[]{int.class}, 2);
            call(uploader, "setRWTimeout", new Class<?>[]{int.class}, 10);
            call(uploader, "setMaxFailTime", new Class<?>[]{int.class}, 5);
            call(uploader, "setEnableHttps", new Class<?>[]{int.class}, 1);
            call(uploader, "setProcessActionType", new Class<?>[]{int.class}, 0);
            String parameter = uploadServerParameter();
            if (parameter != null) call(uploader, "setServerParameter", new Class<?>[]{String.class}, parameter);
            call(uploader, "setFilePath", new Class<?>[]{int.class, String[].class}, 1, new String[]{file.getAbsolutePath()});
            return startAndWait(loader, uploader);
        } finally {
            call(uploader, "close", new Class<?>[0]);
        }
    }

    private static String startAndWait(ClassLoader loader, Object uploader) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> key = new AtomicReference<>();
        Class<?> listenerType = Class.forName(UPLOADER_LISTENER, true, loader);
        InvocationHandler listener = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            switch (method.getName()) {
                case "onNotify": {
                    int what = (Integer) args[0];
                    Object info = args[2];
                    if (what == UPLOAD_DONE) {
                        key.set(info == null ? null : (String) info.getClass().getField("mImageTosKey").get(info));
                        done.countDown();
                    } else if (what == UPLOAD_FAILED) {
                        Object code = info == null ? null : info.getClass().getField("mErrorCode").get(info);
                        Logger.printInfo(() -> "Sticker upload failed: " + code);
                        done.countDown();
                    }
                    return null;
                }
                case "imageXUploadCheckNetState":
                    return 1;
                default:
                    return null;
            }
        };
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, listener);
        call(uploader, "setListener", new Class<?>[]{listenerType}, proxy);
        call(uploader, "start", new Class<?>[0]);
        if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) Logger.printInfo(() -> "Sticker upload timed out");
        return key.get();
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    interface Suspend {
        Object call(Object continuation);
    }

    /** A Predicate would need API 24, a floor above the payload's. */
    interface Wanted {
        boolean test(Object value);
    }

    /**
     * Runs one suspend call with a continuation of its own and returns what it finished with. The
     * continuation's context is empty, so the call runs on whatever dispatcher it picks itself.
     * Null when the bridges are missing or the call didn't finish in time.
     */
    static Object await(Suspend suspend) throws InterruptedException {
        Class<?> type = continuationType();
        if (type == null) return null;
        Class<?> contextType = null;
        for (Method method : type.getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType().isInterface()) contextType = method.getReturnType();
        }
        if (contextType == null) return null;
        Class<?> context = contextType;
        ClassLoader loader = type.getClassLoader();
        Object empty = Proxy.newProxyInstance(loader, new Class<?>[]{context}, (proxy, method, args) -> emptyContext(proxy, method, args, context));
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        Object continuation = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (args == null || args.length == 0) return empty;
            result.set(args[0]);
            resumed.countDown();
            return null;
        });
        Object direct = suspend.call(continuation);
        // A suspend call that hasn't finished returns the COROUTINE_SUSPENDED enum constant, and
        // none of the calls here finish with an enum.
        if (!(direct instanceof Enum)) return direct;
        return resumed.await(WAIT_SECONDS, TimeUnit.SECONDS) ? result.get() : null;
    }

    /** An empty CoroutineContext, told apart by shape since R8 may rename its methods. */
    static Object emptyContext(Object proxy, Method method, Object[] args, Class<?> contextType) {
        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
        int count = args == null ? 0 : args.length;
        // fold(initial, operation) has nothing to fold.
        if (count == 2) return args[0];
        // plus(other) is the other context, minusKey(key) is this one.
        if (count == 1 && method.getReturnType() == contextType) {
            return method.getParameterTypes()[0] == contextType ? args[0] : proxy;
        }
        // get(key) finds no element.
        return null;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "HushfeedSticker";
        }
    }

    /**
     * What a suspend call finished with, out of the boxes Kotlin puts it in: a Result holding the
     * value, or a failure holding the exception. Null for a failure or anything {@code wanted}
     * turns down.
     */
    static Object unbox(Object value, Wanted wanted) {
        for (int depth = 0; value != null && depth < 3; depth++) {
            if (value instanceof Throwable) {
                Throwable failure = (Throwable) value;
                Logger.printException(() -> "Sticker call failed", failure);
                return null;
            }
            if (wanted.test(value)) return value;
            value = onlyField(value);
        }
        return null;
    }

    private static Object onlyField(Object value) {
        Object found = null;
        int fields = 0;
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (++fields > 1) return null;
            try {
                field.setAccessible(true);
                found = field.get(value);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return null;
            }
        }
        return found;
    }

    private static boolean hasMethod(Object value, String name) {
        try {
            value.getClass().getMethod(name);
            return true;
        } catch (NoSuchMethodException error) {
            return false;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    // The bridges below are stubs. The patch writes each body from the calls PineDrama makes
    // itself, and an unpatched build answers with nothing.

    /** PineDrama's sticker upload config call: an ImageX token from im/upload_config, scene 2. */
    static Object uploadConfig(Object continuation) {
        return null;
    }

    /** The server parameter PineDrama hands its ImageX uploads (app id, device, region). */
    static String uploadServerParameter() {
        return null;
    }

    /** StickerApiV2's im/sticker/save, through the API object PineDrama's canvas saver uses. */
    static Object saveSticker(int type, String uri, int width, int height, List<?> tags, String source,
                              Integer createSource, Object continuation) {
        return null;
    }

    /** The sticker store's add, which puts a saved sticker into the Saved page. */
    static void addToSaved(Object sticker) {
    }

    /** The Continuation interface the suspend calls take. */
    static Class<?> continuationType() {
        return null;
    }
}
