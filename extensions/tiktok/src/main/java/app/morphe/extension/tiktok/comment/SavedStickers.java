/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

/**
 * How many saved stickers the sticker panel's first read asks for (#214).
 *
 * <p>The panel loads your saved stickers with one request sized by the server's
 * im_sticker_fav_pagesize_config (refreshCount, 100 by default). The comment sticker
 * panel never asks for the next page, so a sticker saved past that first page never shows,
 * patched or not. The patch hands the refresh's count here just before the request goes out,
 * and a count of {@link #FIRST_READ} brings every saved sticker in that one read (113 of 113 on
 * the test account). TikTok pages its panel itself, and its load more still covers anything past
 * this. The recently used list also reads refreshCount, as its length cap, and isn't hooked.
 */
public final class SavedStickers {
    /** The first read's count: past what people save, and a size the server answered in full. */
    static final long FIRST_READ = 500;

    private SavedStickers() {}

    /** The count for the first read of the saved stickers, given the server's {@code original}. */
    public static long refreshCount(long original) {
        return Math.max(original, FIRST_READ);
    }
}
