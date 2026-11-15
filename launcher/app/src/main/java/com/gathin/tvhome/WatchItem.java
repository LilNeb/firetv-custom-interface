package com.gathin.tvhome;

import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/** One card on the Watch page: a quick action chip, a 16:9 card (episode, video) or a 2:3 poster. */
final class WatchItem {

    static final int CHIP = 0;
    static final int LANDSCAPE = 1;
    static final int POSTER = 2;

    static final String STREMIO = "stremio";
    static final String YOUTUBE = "youtube";

    final int type;
    /** STREMIO, YOUTUBE or null; picks the dot colour and the fallback app. */
    final String app;
    String title = "";
    String subtitle = "";
    /** Shown above the hero title, e.g. "Stremio · Continue watching". */
    String kicker = "";
    String meta = "";
    String description = "";
    String imageUrl;
    /** Full-screen picture behind the hero when the card is focused. */
    String backdropUrl;
    /** 0..1, or negative for none. */
    float progress = -1f;
    int iconRes;
    /** What OK does. When the intent can't be resolved, the activity opens {@link #app} instead. */
    Intent intent;
    /** Instead of an intent, e.g. "Connect Stremio". */
    Runnable action;

    WatchItem(int type, String app) {
        this.type = type;
        this.app = app;
    }

    /** A titled row of cards. */
    static final class Row {
        final String title;
        final int dotColor;
        final List<WatchItem> items = new ArrayList<>();
        /** The Watch page tab this row belongs to (a YouTube account's name); null for Stremio. */
        String group;

        Row(String title, int dotColor) {
            this.title = title;
            this.dotColor = dotColor;
        }
    }
}
