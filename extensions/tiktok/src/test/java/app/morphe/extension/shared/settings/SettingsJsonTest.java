/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsJsonTest {
    private static final SettingsJson.Limits LIMITS = new SettingsJson.Limits(4, 64, 1024, 8, 4096);

    /** Notepad and some file apps save UTF-8 with a byte order mark; JsonReader skips one. */
    @Test public void aFileSavedWithAByteOrderMarkStillReads() throws Exception {
        byte[] body = "{\"hide_ads\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] marked = new byte[body.length + 3];
        marked[0] = (byte) 0xEF;
        marked[1] = (byte) 0xBB;
        marked[2] = (byte) 0xBF;
        System.arraycopy(body, 0, marked, 3, body.length);

        JSONObject read = SettingsJson.parseObject(marked, LIMITS);
        assertEquals(true, read.getBoolean("hide_ads"));
        assertEquals(1, read.length());

        String text = new String(marked, StandardCharsets.UTF_8);
        assertEquals(true, SettingsJson.parseObject(text).getBoolean("hide_ads"));
    }

    @Test public void onlyOneLeadingMarkIsLetThrough() {
        String twice = String.valueOf((char) 0xFEFF) + (char) 0xFEFF + "{}";
        assertThrows(IOException.class, () -> SettingsJson.parseObject(twice));
        String inside = "{" + (char) 0xFEFF + "}";
        assertThrows(IOException.class, () -> SettingsJson.parseObject(inside));
    }
}
