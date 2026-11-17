package com.ss.android.ugc.aweme.backgroundplay;

/** Test stand-in for TikTok's background queue scene: its kind and the key it was opened with. */
public final class ScopedBgPlayScene {
    public final String sceneKey;
    public final String scopeId;

    public ScopedBgPlayScene(String sceneKey, String scopeId) {
        this.sceneKey = sceneKey;
        this.scopeId = scopeId;
    }
}
