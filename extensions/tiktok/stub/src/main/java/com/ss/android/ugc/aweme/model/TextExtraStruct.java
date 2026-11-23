/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.ss.android.ugc.aweme.model;

/**
 * One hashtag or mention span in a caption: its range in the text and, in a translation's
 * markup, the {@code tagId} its markup tag points at.
 */
@SuppressWarnings("unused")
public class TextExtraStruct implements Cloneable {
    public String tagId;
    public String hashTagName;
    public int start;
    public int end;

    @Override
    public TextExtraStruct clone() {
        try {
            return (TextExtraStruct) super.clone();
        } catch (CloneNotSupportedException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
