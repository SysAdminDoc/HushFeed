package com.ss.android.ugc.aweme;

/**
 * Stands in for TikTok's Activity center experiment: a built-in model with no address, and a
 * lazy, not public, that answers the model TikTok's servers sent.
 */
public final class ActivityCenterEntranceExperiment {
    public static final ActivityCenterSettingModel LIZ = new ActivityCenterSettingModel(false, null);
    public static final Object LIZIZ = new Served();

    /** What the servers sent; a test sets its address. */
    public static final ActivityCenterSettingModel SERVED = new ActivityCenterSettingModel(true, null);

    private ActivityCenterEntranceExperiment() {
    }

    public static final class ActivityCenterSettingModel {
        public final boolean enable;
        public String url;

        public ActivityCenterSettingModel(boolean enable, String url) {
            this.enable = enable;
            this.url = url;
        }
    }

    static final class Served {
        public Object getValue() {
            return SERVED;
        }
    }
}
