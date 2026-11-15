package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * The Watch page: a hero (title and details of the focused card) above rows of cards. It takes focus as a
 * single view and moves its own highlight with the D-pad, so navigation is deterministic: Left/Right within a
 * row, Up/Down between rows (each row remembers its column), Up from the first row leaves to the top bar.
 *
 * Motion follows Motion.java: the focused card grows with the snappy spring, and lifts on a soft shadow (tvOS)
 * with the bouncy one (as on iOS 26), and rows and the hero move with the smooth one. Nothing loops.
 */
public class WatchView extends FrameLayout {

    interface Listener {
        void onOpen(WatchItem item);

        /** Up from the first row: the activity moves focus to the top bar. */
        void onExitUp();

        /** The page's tabs changed (names, or the current one): the activity shows them in the top bar. */
        void onTabs(List<String> names, int current);

        /** The focused card's backdrop, or null. Debounced. */
        void onBackdrop(String url, boolean animate);
    }

    // Sizes in dp. The page is about 960 x 420 dp on a 1080p Fire TV.
    private static final int CHIP_W = 176, CHIP_H = 48;
    private static final int LAND_W = 204, LAND_H = 115;
    private static final int POSTER_W = 104, POSTER_H = 156;
    private static final int GAP = 14;
    private static final int ROW_HEADER = 30;
    private static final int ROW_SPACING = 20;
    private static final int ROWS_TOP = 172;
    /** Where a focused row below the first settles: under the hero, with room for the focus scale. */
    private static final int FOCUSED_ROW_TOP = 196;
    private static final float FOCUS_SCALE = 1.08f;
    private static final float CHIP_FOCUS_SCALE = 1.05f;
    private static final float PRESS_SCALE = 0.97f;

    private final float dp;
    private final Typeface font;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private final LinearLayout hero;
    private final View heroDot;
    private final TextView heroKicker, heroTitle, heroMeta, heroDesc;
    private final LinearLayout rowsView;

    private final List<WatchItem.Row> rows = new ArrayList<>();
    /** All rows; {@link #rows} holds only the current tab's. Tabs: Stremio, then one per YouTube account. */
    private final List<WatchItem.Row> allRows = new ArrayList<>();
    private final List<String> tabNames = new ArrayList<>();
    private int tab;
    /** Each tab's row views, kept while the rows don't change. */
    private final java.util.Map<String, List<RowViews>> built = new java.util.HashMap<>();
    private final List<RowViews> rowViews = new ArrayList<>();
    private int row, lastRow = -1;
    private int[] cols = new int[0];
    private WatchItem heroItem;
    private String pendingBackdrop;
    private boolean backdropAnimate;
    private boolean switching;
    private boolean pressed;

    private static final class RowViews {
        View root;
        LinearLayout track;
        final List<CardViews> cards = new ArrayList<>();
        int top, bottom;
        float targetTx = Float.NaN;
        float alpha = Float.NaN;
    }

    private static final class CardViews {
        FrameLayout card;
        WatchItem item;
        /** Chips only: the white fill and the views that turn dark on it (tvOS focused button). */
        View fill;
        TextView kicker, title;
        final Runnable dropLayer = () -> { if (card != null) card.setLayerType(View.LAYER_TYPE_NONE, null); };
        ImageView icon;
        /** Picture cards: loaded only near the focus (see loadNearby), dropped when far. */
        ImageView img;
        boolean loaded;
        /** Last focus targets, so a key press only animates the cards that change. NaN until first set. */
        float s = Float.NaN, z = Float.NaN;
    }

    public WatchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        Typeface f;
        try {
            f = getResources().getFont(R.font.open_sans);
        } catch (Exception e) {
            f = Typeface.DEFAULT;
        }
        font = f;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClipChildren(false);
        setClipToPadding(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        LinearLayout kickerRow = new LinearLayout(context);
        kickerRow.setGravity(Gravity.CENTER_VERTICAL);
        heroDot = new View(context);
        heroDot.setBackground(circle(ContentRepository.STREMIO_COLOR));
        kickerRow.addView(heroDot, new LinearLayout.LayoutParams(px(8), px(8)));
        heroKicker = text(12, 500, 0xB3FFFFFF);
        heroKicker.setAllCaps(true);
        heroKicker.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.leftMargin = px(8);
        kickerRow.addView(heroKicker, klp);
        hero.addView(kickerRow);
        heroTitle = text(34, 700, Color.WHITE);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setLineSpacing(0, 0.92f);
        heroTitle.setPadding(0, px(6), 0, px(4));
        hero.addView(heroTitle);
        heroMeta = text(14, 500, 0xCCFFFFFF);
        heroMeta.setSingleLine(true);
        hero.addView(heroMeta);
        heroDesc = text(15, 400, 0xE6FFFFFF);
        heroDesc.setMaxLines(2);
        heroDesc.setEllipsize(TextUtils.TruncateAt.END);
        heroDesc.setLineSpacing(0, 1.15f);
        heroDesc.setPadding(0, px(8), 0, 0);
        hero.addView(heroDesc);
        LayoutParams hlp = new LayoutParams(px(540), -2);
        hlp.topMargin = px(4);
        addView(hero, hlp);

        rowsView = new LinearLayout(context) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;   // faded on a tab change: no full-page offscreen layer
            }
        };
        rowsView.setOrientation(LinearLayout.VERTICAL);
        rowsView.setClipChildren(false);
        rowsView.setClipToPadding(false);
        LayoutParams rlp = new LayoutParams(-1, -2);
        rlp.topMargin = px(ROWS_TOP);
        addView(rowsView, rlp);

    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Replaces the rows, keeping the focused row and columns where they still exist. */
    void setRows(List<WatchItem.Row> newRows) {
        String current = tab < tabNames.size() ? tabNames.get(tab) : null;
        allRows.clear();
        tabNames.clear();
        for (WatchItem.Row r : newRows) {
            if (r.items.isEmpty()) continue;
            allRows.add(r);
            if (!tabNames.contains(tabName(r))) tabNames.add(tabName(r));
        }
        tab = Math.max(0, tabNames.indexOf(current));
        built.clear();
        showTab();
        schedulePrebuild();
    }

    private static String tabName(WatchItem.Row r) {
        return r.group != null ? r.group : "Stremio";
    }

    /** Rewind / fast-forward: the previous or next tab, from its first card. */
    boolean switchTab(int delta) {
        int to = tab + delta;
        if (to < 0 || to >= tabNames.size()) return false;
        tab = to;
        row = 0;
        cols = new int[0];
        // Same move as the Watch / Apps page change: 24 dp towards the button's side, fade, 250 ms ease-out, on a
        // GPU layer so the fade is one composite (fading 60 cards one by one cost this GPU 16 ms a frame).
        switching = true;
        showTab();
        switching = false;
        animate().cancel();
        setAlpha(0f);
        setTranslationX(delta * 24 * dp);
        animate().alpha(1f).translationX(0f).setDuration(250).setInterpolator(Motion.EASE_OUT).withLayer().start();
        return true;
    }

    private void showTab() {
        rows.clear();
        String name = tabNames.isEmpty() ? null : tabNames.get(tab);
        for (WatchItem.Row r : allRows) if (tabName(r).equals(name)) rows.add(r);
        updateTabs();
        int[] old = cols;
        cols = new int[rows.size()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = i < old.length ? Math.min(old[i], rows.get(i).items.size() - 1) : 0;
        }
        row = Math.max(0, Math.min(row, rows.size() - 1));
        build();
        lastRow = -1;
        applyFocus(false);
    }

    /** The focused item, for tests and for the activity. */
    WatchItem focusedItem() {
        if (rows.isEmpty()) return null;
        return rows.get(row).items.get(cols[row]);
    }

    private void updateTabs() {
        if (listener != null) listener.onTabs(tabNames, tab);
    }

    int currentTab() {
        return tab;
    }

    /** A tab of the top bar got the focus: show its rows (tvOS: focusing a tab shows its page). */
    void selectTab(int i) {
        if (i != tab) switchTab(i - tab);
    }

    // ---- building ----------------------------------------------------------------------------------

    /** Shows a tab's rows, built once: building them takes the stick 300-650 ms, a visible freeze. */
    private void build() {
        String name = tabNames.isEmpty() ? "" : tabNames.get(tab);
        List<RowViews> list = built.get(name);
        if (list == null) {
            list = buildViews(rows);
            built.put(name, list);
        }
        rowsView.removeAllViews();
        rowViews.clear();
        rowViews.addAll(list);
        for (RowViews rv : list) rowsView.addView(rv.root);
        // Full height: as wrap_content the rows would be squeezed into what's left under the hero.
        rowsView.getLayoutParams().height = px(list.isEmpty() ? 0 : list.get(list.size() - 1).bottom);
        rowsView.requestLayout();
    }

    /** The other tabs are built while the remote rests, one at a time, so a tab change never waits for them. */
    private final Runnable prebuild = () -> {
        for (String name : tabNames) {
            if (built.containsKey(name)) continue;
            List<WatchItem.Row> rs = new ArrayList<>();
            for (WatchItem.Row r : allRows) if (tabName(r).equals(name)) rs.add(r);
            built.put(name, buildViews(rs));
            schedulePrebuild();
            return;
        }
    };

    private void schedulePrebuild() {
        handler.removeCallbacks(prebuild);
        if (built.size() < tabNames.size()) handler.postDelayed(prebuild, 1500);
    }

    private List<RowViews> buildViews(List<WatchItem.Row> rs) {
        List<RowViews> out = new ArrayList<>();
        int top = 0;
        for (WatchItem.Row r : rs) {
            RowViews rv = new RowViews();
            // Cards don't overlap, so fading the row needs no offscreen layer (a full-width layer costs a frame).
            LinearLayout box = new LinearLayout(getContext()) {
                @Override
                public boolean hasOverlappingRendering() {
                    return false;
                }
            };
            box.setOrientation(LinearLayout.VERTICAL);
            box.setClipChildren(false);
            box.setClipToPadding(false);

            LinearLayout header = new LinearLayout(getContext());
            header.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(getContext());
            dot.setBackground(circle(r.dotColor));
            header.addView(dot, new LinearLayout.LayoutParams(px(8), px(8)));
            TextView t = text(15, 600, 0xF2FFFFFF);
            t.setText(r.title);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = px(8);
            header.addView(t, tlp);
            box.addView(header, new LinearLayout.LayoutParams(-1, px(ROW_HEADER)));

            LinearLayout track = new LinearLayout(getContext());
            track.setClipChildren(false);
            track.setClipToPadding(false);
            int h = 0;
            for (int i = 0; i < r.items.size(); i++) {
                CardViews cv = card(r.items.get(i));
                int w = widthOf(cv.item.type);
                h = heightOf(cv.item.type);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), px(h));
                if (i > 0) lp.leftMargin = px(GAP);
                track.addView(cv.card, lp);
                rv.cards.add(cv);
            }
            box.addView(track, new LinearLayout.LayoutParams(-2, px(h)));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.bottomMargin = px(ROW_SPACING);
            box.setLayoutParams(blp);
            rv.root = box;
            rv.track = track;
            rv.top = top;
            top += ROW_HEADER + h + ROW_SPACING;
            rv.bottom = top;
            out.add(rv);
        }
        return out;
    }

    private CardViews card(WatchItem item) {
        CardViews cv = new CardViews();
        cv.item = item;
        FrameLayout card = new FrameLayout(getContext());
        cv.card = card;
        if (item.type == WatchItem.CHIP) {
            // tvOS button: translucent at rest, solid white when focused.
            card.setBackground(rounded(0x33FFFFFF, px(CHIP_H / 2f)));
            View fill = new View(getContext());
            fill.setBackground(rounded(Color.WHITE, px(CHIP_H / 2f)));
            fill.setAlpha(0f);
            card.addView(fill, new LayoutParams(-1, -1));
            cv.fill = fill;
            LinearLayout content = new LinearLayout(getContext());
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(px(16), 0, px(14), 0);
            ImageView icon = new ImageView(getContext());
            icon.setImageResource(item.iconRes);
            icon.setColorFilter(Color.WHITE);
            content.addView(icon, new LinearLayout.LayoutParams(px(20), px(20)));
            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView k = text(9, 700, 0xA6FFFFFF);
            k.setAllCaps(true);
            k.setLetterSpacing(0.16f);
            k.setText(WatchItem.YOUTUBE.equals(item.app) ? "YouTube" : "Stremio");
            TextView t = text(13, 600, Color.WHITE);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setText(item.title);
            texts.addView(k);
            texts.addView(t);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = px(12);
            content.addView(texts, tl);
            card.addView(content, new LayoutParams(-1, -1));
            cv.icon = icon;
            cv.kicker = k;
            cv.title = t;
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(10));
            bg.setColor(0xFF1C1C22);
            card.setBackground(bg);
            // Title underneath the picture, visible while it loads or if it never does.
            TextView fallback = text(item.type == WatchItem.POSTER ? 14 : 13, 700, 0xCCFFFFFF);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(px(10), 0, px(10), 0);
            fallback.setText(item.title);
            card.addView(fallback, new LayoutParams(-1, -1));
            ImageView img = new ImageView(getContext());
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(img, new LayoutParams(-1, -1));
            // Shaped at the focused size, so the card stays sharp when it grows.
            cv.img = img;   // loaded by loadNearby() once the focus comes close

            if (item.type == WatchItem.LANDSCAPE) {
                LinearLayout texts = new LinearLayout(getContext());
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(px(10), 0, px(10), px(item.progress >= 0 ? 12 : 9));
                TextView t = text(13, 700, Color.WHITE);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setText(item.title);
                TextView s = text(11, 500, 0xCCFFFFFF);
                s.setSingleLine(true);
                s.setEllipsize(TextUtils.TruncateAt.END);
                s.setText(item.subtitle);
                texts.addView(t);
                texts.addView(s);
                card.addView(texts, new LayoutParams(-1, -2, Gravity.BOTTOM));
                if (item.progress >= 0) {
                    FrameLayout bar = new FrameLayout(getContext());
                    GradientDrawable tb = new GradientDrawable();
                    tb.setColor(0x4DFFFFFF);
                    tb.setCornerRadius(px(2));
                    bar.setBackground(tb);
                    View fill = new View(getContext());
                    GradientDrawable fb = new GradientDrawable();
                    fb.setColor(Color.WHITE);
                    fb.setCornerRadius(px(2));
                    fill.setBackground(fb);
                    int barW = widthOf(item.type) - 20;
                    bar.addView(fill, new LayoutParams(Math.max(px(3), (int) (px(barW) * item.progress)), -1));
                    LayoutParams blp = new LayoutParams(px(barW), px(3), Gravity.BOTTOM | Gravity.START);
                    blp.leftMargin = px(10);
                    blp.bottomMargin = px(7);
                    card.addView(bar, blp);
                }
            }
        }
        return cv;
    }

    // ---- focus -------------------------------------------------------------------------------------

    @Override
    protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        applyFocus(true);
    }

    private void applyFocus(boolean animate) {
        if (rowViews.isEmpty()) return;
        boolean focused = hasFocus();
        for (int r = 0; r < rowViews.size(); r++) {
            RowViews rv = rowViews.get(r);
            for (int c = 0; c < rv.cards.size(); c++) {
                CardViews cv = rv.cards.get(c);
                boolean on = focused && r == row && c == cols[r];
                float s = on ? (cv.item.type == WatchItem.CHIP ? CHIP_FOCUS_SCALE : FOCUS_SCALE) : 1f;
                if (on && pressed) s *= PRESS_SCALE;
                // tvOS: the focused card lifts towards you (scale and a deep soft shadow); nothing is drawn on it.
                float z = on ? (cv.item.type == WatchItem.CHIP ? 8 : 20) * dp : 0f;
                if (s == cv.s && z == cv.z) continue;   // unchanged: no spring to start (there can be 200 cards)
                cv.s = s;
                cv.z = z;
                if (animate) {
                    // Scaled text is re-rasterised at every frame of the spring; a layer scales one texture instead.
                    View card = cv.card;
                    if (card.getLayerType() != LAYER_TYPE_HARDWARE) card.setLayerType(LAYER_TYPE_HARDWARE, null);
                    handler.removeCallbacks(cv.dropLayer);
                    handler.postDelayed(cv.dropLayer, 500);
                    Motion.scale(cv.card, s, Motion.SNAPPY);
                    Motion.to(cv.card, DynamicAnimation.TRANSLATION_Z, z, Motion.SNAPPY);
                } else {
                    Motion.set(cv.card, DynamicAnimation.SCALE_X, s);
                    Motion.set(cv.card, DynamicAnimation.SCALE_Y, s);
                    Motion.set(cv.card, DynamicAnimation.TRANSLATION_Z, z);
                }
                if (cv.fill != null) {
                    // The fill follows the spring; the text flips colour at once so it never reads grey on grey.
                    if (animate) Motion.to(cv.fill, DynamicAnimation.ALPHA, on ? 1f : 0f, Motion.SNAPPY);
                    else Motion.set(cv.fill, DynamicAnimation.ALPHA, on ? 1f : 0f);
                    int fg = on ? 0xFF111111 : Color.WHITE;
                    cv.title.setTextColor(fg);
                    cv.kicker.setTextColor(on ? 0x99111111 : 0xA6FFFFFF);
                    cv.icon.setColorFilter(fg);
                }
            }
            scrollRow(rv, cols[r], animate);
        }

        // Rows: the focused row slides up under the hero, which always describes the focused card; rows above fade.
        float ty = row == 0 ? 0f : (FOCUSED_ROW_TOP - ROWS_TOP - rowViews.get(row).top) * dp;
        if (animate) Motion.to(rowsView, DynamicAnimation.TRANSLATION_Y, ty, Motion.SMOOTH);
        else Motion.set(rowsView, DynamicAnimation.TRANSLATION_Y, ty);
        for (int r = 0; r < rowViews.size(); r++) {
            float a = r < row ? 0f : 1f;
            if (a == rowViews.get(r).alpha) continue;
            rowViews.get(r).alpha = a;
            if (animate) Motion.to(rowViews.get(r).root, DynamicAnimation.ALPHA, a, Motion.SMOOTH);
            else Motion.set(rowViews.get(r).root, DynamicAnimation.ALPHA, a);
        }
        loadNearby();
        updateHero(animate);
        lastRow = row;
    }

    /**
     * Pictures for the rows around the focus only: decoding and uploading every card of every row at once (200+
     * with the YouTube accounts) starved the GPU. Far rows give their pictures back; the cache keeps them warm.
     */
    private void loadNearby() {
        float k = FOCUS_SCALE;
        for (int r = 0; r < rowViews.size(); r++) {
            boolean nearRow = r >= row - 1 && r <= row + 2;
            boolean farRow = r < row - 3 || r > row + 4;
            // Off-screen rows and cards are hidden, not just out of sight: the render thread walks every visible
            // view on every frame, and 250 cards (with the YouTube accounts) cost it ~10 ms per frame.
            boolean drawRow = r >= row - 1 && r <= row + 3;
            View rowRoot = rowViews.get(r).root;
            int rowVis = drawRow ? VISIBLE : INVISIBLE;
            if (rowRoot.getVisibility() != rowVis) rowRoot.setVisibility(rowVis);
            List<CardViews> cards = rowViews.get(r).cards;
            for (int c = 0; c < cards.size(); c++) {
                CardViews cv = cards.get(c);
                int cardVis = drawRow && c >= cols[r] - 5 && c <= cols[r] + 6 ? VISIBLE : INVISIBLE;
                if (cv.card.getVisibility() != cardVis) cv.card.setVisibility(cardVis);
                if (cv.img == null) continue;
                if (!cv.loaded && nearRow && c >= cols[r] - 2 && c <= cols[r] + 6) {
                    cv.loaded = true;
                    WatchItem it = cv.item;
                    ImageLoader.intoCard(cv.img, it.imageUrl, px(widthOf(it.type) * k), px(heightOf(it.type) * k),
                            px(10) * k, it.type == WatchItem.LANDSCAPE);
                } else if (cv.loaded && farRow) {
                    cv.loaded = false;
                    cv.img.setTag(R.id.image_url, null);   // a load still in flight must not land here
                    cv.img.setImageDrawable(null);
                }
            }
        }
    }

    /** Keeps the focused card inside the page, with room for its focus scale. */
    private void scrollRow(RowViews rv, int col, boolean animate) {
        if (rv.cards.isEmpty()) return;
        int w = widthOf(rv.cards.get(0).item.type);
        float visible = getWidth() > 0 ? getWidth() / dp : 848;
        float left = col * (w + GAP);
        float right = left + w;
        float margin = 28;
        float tx = right + margin > visible ? -(right + margin - visible) : 0f;
        if (tx * dp == rv.targetTx) return;
        rv.targetTx = tx * dp;
        if (animate) Motion.to(rv.track, DynamicAnimation.TRANSLATION_X, tx * dp, Motion.SMOOTH);
        else Motion.set(rv.track, DynamicAnimation.TRANSLATION_X, tx * dp);
    }

    private void updateHero(boolean animate) {
        WatchItem it = focusedItem();
        if (it == null) return;
        WatchItem shown = it.type == WatchItem.CHIP && it.action == null && heroFor(it) != null ? heroFor(it) : it;
        if (shown == heroItem) return;
        heroItem = shown;
        heroDot.setBackground(circle(WatchItem.YOUTUBE.equals(it.app)
                ? ContentRepository.YOUTUBE_COLOR : ContentRepository.STREMIO_COLOR));
        heroKicker.setText(it.kicker);
        heroTitle.setText(shown.title);
        heroMeta.setText(shown.meta);
        heroMeta.setVisibility(TextUtils.isEmpty(shown.meta) ? View.GONE : View.VISIBLE);
        heroDesc.setText(shown.description);
        heroDesc.setVisibility(TextUtils.isEmpty(shown.description) ? View.GONE : View.VISIBLE);
        if (animate) {
            // New title rises in; the spring picks up from wherever the last one was.
            // Seen at every move: a short fade, no movement (frequent actions get the least motion).
            heroTitle.setAlpha(0.4f);
            heroTitle.animate().alpha(1f).setDuration(120).setInterpolator(Motion.EASE_OUT).start();
        }
        // Backdrop: at once when it is already loaded (the usual case, see prefetch), otherwise after a short
        // pause so scrolling fast doesn't download every picture.
        pendingBackdrop = shown.backdropUrl;
        backdropAnimate = animate || switching;
        handler.removeCallbacks(backdrop);
        if (pendingBackdrop == null || ImageLoader.has(pendingBackdrop, ImageLoader.BACKDROP_W)) backdrop.run();
        else handler.postDelayed(backdrop, 150);
        // Neighbours once the focus has settled: decoding while the cards move steals the CPU the animation needs.
        handler.removeCallbacks(prefetchLater);
        handler.postDelayed(prefetchLater, 300);
    }

    private final Runnable prefetchLater = this::prefetch;

    /** Loads the backdrops of the cards one move away, so the next focus change shows its picture at once. */
    private void prefetch() {
        for (int r = Math.max(0, row - 1); r <= Math.min(rows.size() - 1, row + 1); r++) {
            List<WatchItem> items = rows.get(r).items;
            int reach = r == row ? 2 : 1;
            for (int c = Math.max(0, cols[r] - reach); c <= Math.min(items.size() - 1, cols[r] + reach); c++) {
                WatchItem it = items.get(c);
                WatchItem h = it.type == WatchItem.CHIP && it.action == null ? heroFor(it) : null;
                String url = (h != null ? h : it).backdropUrl;
                if (url != null) ImageLoader.prefetch(url, ImageLoader.BACKDROP_W);
            }
        }
    }

    private final Runnable backdrop = () -> {
        if (listener != null) listener.onBackdrop(pendingBackdrop, backdropAnimate);
    };

    /** A quick action that stands for a card ("Resume") shows that card in the hero. */
    private WatchItem heroFor(WatchItem chip) {
        if (chip.intent == null) return null;
        for (WatchItem.Row r : rows) {
            for (WatchItem i : r.items) {
                if (i != chip && i.intent != null && i.intent.filterEquals(chip.intent)) return i;
            }
        }
        return null;
    }

    // ---- keys --------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        schedulePrebuild();   // not while the remote is in use
        if (rows.isEmpty()) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (cols[row] > 0) {
                    cols[row]--;
                    applyFocus(true);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (cols[row] < rows.get(row).items.size() - 1) {
                    cols[row]++;
                    applyFocus(true);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) {
                    // Coming back down from the tabs starts at the first card, not where the row was left.
                    cols[0] = 0;
                    applyFocus(true);
                    if (listener != null) listener.onExitUp();
                    return true;
                }
                moveRow(row - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < rows.size() - 1) moveRow(row + 1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                // Past the last tab: not handled, the activity goes on to Apps like the top bar does.
                return switchTab(1) || super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return switchTab(-1) || super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    // Feedback on press, not on release.
                    pressed = true;
                    applyFocus(true);
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Same kind of row: the column carries over. Another kind (chips, posters, wide cards): its first card. */
    private void moveRow(int to) {
        int from = row;
        row = to;
        cols[to] = rows.get(to).items.get(0).type == rows.get(from).items.get(0).type
                ? Math.min(cols[from], rows.get(to).items.size() - 1) : 0;
        applyFocus(true);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!pressed) return true;
            pressed = false;
            applyFocus(true);
            WatchItem it = focusedItem();
            if (it != null && listener != null) listener.onOpen(it);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Back on the Watch page returns to the first card, like tvOS. */
    boolean resetToStart() {
        if (rows.isEmpty() || (row == 0 && cols[0] == 0)) return false;
        row = 0;
        for (int i = 0; i < cols.length; i++) cols[i] = 0;
        applyFocus(true);
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) applyFocus(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static int widthOf(int type) {
        return type == WatchItem.CHIP ? CHIP_W : type == WatchItem.POSTER ? POSTER_W : LAND_W;
    }

    private static int heightOf(int type) {
        return type == WatchItem.CHIP ? CHIP_H : type == WatchItem.POSTER ? POSTER_H : LAND_H;
    }

    private TextView text(int sp, int weight, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
