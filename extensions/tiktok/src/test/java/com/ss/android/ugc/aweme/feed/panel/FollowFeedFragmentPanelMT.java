package com.ss.android.ugc.aweme.feed.panel;

/** Stands in for TikTok's Following panel: counts the load-more checks it is asked for. */
public class FollowFeedFragmentPanelMT {
    public int checks;
    public boolean viewValid = true;

    public boolean isViewValid() {
        return viewValid;
    }

    public void checkLoadMore() {
        checks++;
    }
}
