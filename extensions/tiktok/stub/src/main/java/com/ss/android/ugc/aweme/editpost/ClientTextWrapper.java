/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.ss.android.ugc.aweme.editpost;

/**
 * A cached caption translation's rich form: how it's encoded (0 plain text, 1 the old markup,
 * 2 a {@link ClientText} with its own spans) and the rich text itself.
 */
@SuppressWarnings("unused")
public final class ClientTextWrapper {
    public final int translationProtocol;
    public final ClientText clientText;

    public ClientTextWrapper(int translationProtocol, ClientText clientText) {
        this.translationProtocol = translationProtocol;
        this.clientText = clientText;
    }
}
