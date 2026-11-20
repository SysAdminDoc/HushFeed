/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.bytedance.retrofit2.mime;

import java.io.IOException;
import java.io.InputStream;

/** Stands in for TikTok's response body type, with its three members. */
public interface TypedInput {
    String mimeType();

    long length();

    InputStream in() throws IOException;
}
