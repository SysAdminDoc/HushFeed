/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Follow list reads for tests outside this package: canned replies, or a result that read nobody. */
public final class FollowListsAccess {
    private FollowListsAccess() {
    }

    /** Reads the signed-in "100"'s followers from {@code replies}, stopping after the first page when asked. */
    public static FollowLists.Result read(List<String> replies, AtomicBoolean stopped, boolean stopAfterFirst) {
        int[] next = {0};
        return FollowLists.read(FollowLists.Kind.FOLLOWERS, "100", null, (path, query) -> replies.get(next[0]++),
                0, stopped, stopAfterFirst ? count -> stopped.set(true) : null);
    }

    public static FollowLists.Result ended(FollowLists.End end) {
        return new FollowLists.Result(new ArrayList<>(), end);
    }
}
