/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.bytedance.retrofit2.mime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** Stands in for TikTok's in-memory body, with the constructor 47.9.2 has. */
public class TypedByteArray implements TypedInput {
    public final String mimeType;
    public final byte[] bytes;

    public TypedByteArray(String mimeType, byte[] bytes, String... fileName) {
        if (bytes == null) throw new NullPointerException("bytes null");
        this.mimeType = mimeType == null ? "application/unknown" : mimeType;
        this.bytes = bytes;
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public long length() {
        return bytes.length;
    }

    @Override
    public InputStream in() {
        return new ByteArrayInputStream(bytes);
    }
}
