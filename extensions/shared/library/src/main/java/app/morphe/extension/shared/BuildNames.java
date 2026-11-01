/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared;

import android.content.Context;

/**
 * Resource names written for one build of the host app.
 *
 * <p>TikTok's build shortens most resource names to two or three characters and hands the same
 * short names out again on every build, mostly to other views: {@code g8a} is the like button on
 * 47.4.3 and something else on another build. A name written {@code "47.4.3:g8a"} is 47.4.3's
 * name and resolves on 47.4.3 alone, so a build the bundle doesn't declare never finds the wrong
 * view under it, and a list can carry each supported build's name for one view. A name with no
 * build in front is a real name, the same on every build.
 */
public final class BuildNames {
    private static volatile String runningBuild;

    /** The entry name to look up on the running build, or null when the name is another build's. */
    public static String entryName(String name) {
        int colon = name.indexOf(':');
        if (colon < 0) return name;
        String build = runningBuild();
        return build == null ? null : entryName(name, build);
    }

    /** The entry name to look up on [build], or null when the name is another build's. */
    static String entryName(String name, String build) {
        int colon = name.indexOf(':');
        if (colon < 0) return name;
        return name.regionMatches(0, build, 0, colon) && build.length() == colon ? name.substring(colon + 1) : null;
    }

    /**
     * The host app's version name, read once it can be. Null until the extension has a context,
     * and then not remembered, so an early lookup can't pin "no build" for the life of the process.
     */
    public static String runningBuild() {
        String build = runningBuild;
        if (build != null) return build;
        Context context = Utils.context;
        if (context == null) return null;
        try {
            build = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable error) {
            return null;
        }
        if (build != null) runningBuild = build;
        return build;
    }

    /** Stands in for the host app's version name in a test; null reads it again. */
    public static void setRunningBuildForTests(String build) {
        runningBuild = build;
    }

    private BuildNames() {}
}
