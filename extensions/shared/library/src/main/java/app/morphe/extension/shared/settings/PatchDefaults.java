/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.settings;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.json.JSONObject;

/** Defaults supplied by native Morphe patch options; never writes user preferences. */
final class PatchDefaults {
    private static final String ASSET = "hushfeed-defaults-v1.json";
    private static JSONObject defaults;

    private PatchDefaults() {}

    private static synchronized JSONObject defaults() {
        if (defaults != null) return defaults;
        JSONObject loaded = new JSONObject();
        try (InputStream input = Utils.getContext().getAssets().open(ASSET)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (buffer.size() + count > 8 * 1024 * 1024) {
                    throw new java.io.IOException("Patch defaults exceed the asset limit");
                }
                buffer.write(bytes, 0, count);
            }
            loaded = SettingsJson.parseObject(buffer.toByteArray(),
                    new SettingsJson.Limits(2, 4096, 512 * 1024, 1, 8 * 1024 * 1024));
        } catch (FileNotFoundException absent) {
            // No customized defaults: the normal bundle does not add an asset.
        } catch (Exception failure) {
            Logger.printException(() -> "Could not read patch defaults", failure);
        }
        defaults = loaded;
        return loaded;
    }

    static <T> T resolve(String key, T stock) {
        return resolve(defaults(), key, stock);
    }

    @SuppressWarnings("unchecked")
    static <T> T resolve(JSONObject values, String key, T stock) {
        if (!values.has(key)) return stock;
        try {
            Object raw = values.get(key);
            Object result;
            if (stock instanceof Boolean && raw instanceof Boolean
                    || stock instanceof String && raw instanceof String) {
                result = raw;
            } else if (raw instanceof Number && stock instanceof Number) {
                BigDecimal number = new BigDecimal(raw.toString());
                if (stock instanceof Integer) result = number.intValueExact();
                else if (stock instanceof Long) result = number.longValueExact();
                else if (stock instanceof Float) {
                    float value = number.floatValue();
                    if (Float.isInfinite(value) || Float.isNaN(value)) throw new IllegalArgumentException(key);
                    result = value;
                } else throw new IllegalArgumentException(key);
            } else if (stock instanceof Enum && raw instanceof String) {
                Class enumClass = ((Enum<?>) stock).getDeclaringClass();
                result = Enum.valueOf(enumClass, (String) raw);
            } else throw new IllegalArgumentException("Wrong default type for " + key);
            return (T) result;
        } catch (Exception invalid) {
            Logger.printException(() -> "Invalid patch default: " + key, invalid);
            return stock;
        }
    }
}
