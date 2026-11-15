package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home with two pages. Watch: Stremio and YouTube (quick actions, continue watching, catalogs, new
 * videos). Apps: the pinned dock and the app grid, with one glass highlight that springs between tiles.
 * The tabs at the top switch pages; motion uses Apple's springs (Motion.java).
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, WatchView.Listener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private TextView heroName;
    private View dockDot;
    // macOS dock magnification: focused icon and its neighbours grow from their bottom edge and lift.
    private static final float DOCK_MAGNIFY = 1.30f;
    private static final float DOCK_NEIGHBOUR = 1.10f;
    private static final float DOCK_LIFT_DP = 10f;
    private static final float DOCK_NEIGHBOUR_LIFT_DP = 4f;
    private boolean heroShown, resumed;
    private TextView greeting;
    private ImageView bgBack, bgFront;
    private Bitmap photo, shownBg;
    private int bgRes;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;

    // ---- pages -----------------------------------------------------------------------------------
    private static final int PAGE_WATCH = 0, PAGE_APPS = 1;
    private static final long RELOAD_MS = 10 * MINUTE_MS;
    private int page = PAGE_WATCH;
    private WatchView watch;
    private View pagesView, appsPage, tabs, tabIndicator;
    private TextView tabWatch, tabApps;
    private String heroUrl;
    private Bitmap heroBitmap;
    private ContentRepository repo;
    private long lastLoad;
    private List<WatchItem> continueItems = new ArrayList<>();
    private List<WatchItem.Row> loadedRows = new ArrayList<>();
    private View linkPanel;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int COLOR_WARN = 0xFFFFB340;   // connected, no internet
    private static final int COLOR_ERROR = 0xFFFF5A52;  // no network
    private static final int[] WIFI_ICONS = {R.drawable.ic_wifi_1, R.drawable.ic_wifi_2, R.drawable.ic_wifi};
    // Refresh the Wi-Fi pill as soon as the connection changes, not on the next minute tick.
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onLost(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
        @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties p) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateBackground();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repo = new ContentRepository(this);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        // tvOS: the focused tile lifts on its own; no outline drawn around it.
        glass.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        dockDot = findViewById(R.id.dockDot);
        greeting = findViewById(R.id.greeting);
        bgBack = findViewById(R.id.bgBack);
        bgFront = findViewById(R.id.bgFront);
        // The background picture covers the window: no need to paint the window's own background under it.
        getWindow().setBackgroundDrawable(null);
        // Scrims are painted into the backdrops once, off the main thread (see showBackground).
        ImageLoader.backdropScrims = new Drawable[]{getDrawable(R.drawable.bg_scrim), getDrawable(R.drawable.hero_scrim)};
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        // One span unit per cell of any dock size 1..COLUMNS: the dock row splits evenly, grid rows are COLUMNS wide.
        final int spans = lcmUpTo(COLUMNS);
        GridLayoutManager glm = new GridLayoutManager(this, spans);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.isDocked(position) ? spans / adapter.dockCount() : spans / COLUMNS;
            }
        });
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);

        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.addItemDecoration(new DockDecoration(adapter, COLUMNS, density));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((101 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow + (int) (8 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            // Liquid Glass: Apple's bouncy spring.
            s.setSpring(Motion.SNAPPY.force(0f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showHomeScreenMenu(v);
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                enterPage(page);   // back to where you were on the page, not the nearest tile
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) {
                tabApps.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU && v.getId() == R.id.btnSettings) {
                showHomeScreenMenu(v);
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        setUpPages();

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                hideHero();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        dockDot.setAlpha(0f);
        adapter.reload();
        updateGreeting();
        updateBackground();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);

        if (System.currentTimeMillis() - lastLoad > RELOAD_MS) loadWatch();
        setPage(page, false);
        // Return to where we left from: the Watch page, or the tile on the Apps page.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (page == PAGE_WATCH) {
                if (focused == null || focused == grid || focused == tabWatch || focused == tabApps
                        || grid.findContainingViewHolder(focused) != null) {
                    watch.requestFocus();
                }
                return;
            }
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back closes the Stremio sign-in, then returns to the first card or the Watch page.
        if (linkPanel != null) {
            closeLink();
            return;
        }
        // Back climbs one level at a time, like tvOS: content → start of the page → its tab → the Watch tab.
        View f = getCurrentFocus();
        if (f == tabWatch) return;
        if (f == tabApps) {
            tabWatch.requestFocus();
            return;
        }
        if (page == PAGE_WATCH) {
            if (watch.hasFocus() && watch.resetToStart()) return;
            tabWatch.requestFocus();
            return;
        }
        int dock = adapter.dockCount();
        if (f != null && grid.findContainingViewHolder(f) != null && lastFocused >= dock && dock > 0) {
            focusTile(Math.min((lastFocused - dock) % COLUMNS, dock - 1));
            return;
        }
        tabApps.requestFocus();
    }

    /** Home pressed while the home screen is already in front: back to the start, like the TV button on tvOS. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!resumed || linkPanel != null) return;
        setPage(PAGE_WATCH, true);
        watch.resetToStart();
        watch.requestFocus();
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        if (adapter.isDocked(position)) {
            onDockFocused(position, card);
            return;
        }
        magnifyDock(-1);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(Math.min(card.getWidth(), card.getHeight()) * AppAdapter.CORNER_RATIO * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        hideHero();
        // Scroll just enough so the whole glass (not only the tile) is visible.
        Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
        grid.requestChildRectangleOnScreen(item, want, false);
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void onDockFocused(int position, View card) {
        hideGlass();
        View item = (View) card.getParent();
        int decoratedTop = item.getTop() - (int) (6 * density);
        int dy = decoratedTop - grid.getPaddingTop();
        if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        magnifyDock(position);
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        r.offset(0, -dy);
        showHero(position, r);
    }

    /** Scale dock icons by distance from the focused one; focused < 0 resets them all. */
    private void magnifyDock(int focused) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm == null) return;
        for (int i = 0; i < adapter.dockCount(); i++) {
            View item = lm.findViewByPosition(i);
            View card = item != null ? item.findViewById(R.id.card) : null;
            if (card == null) continue;
            int d = focused < 0 ? Integer.MAX_VALUE : Math.abs(i - focused);
            float s = d == 0 ? DOCK_MAGNIFY : d == 1 ? DOCK_NEIGHBOUR : 1f;
            float lift = d == 0 ? DOCK_LIFT_DP : d == 1 ? DOCK_NEIGHBOUR_LIFT_DP : 0f;
            card.setPivotX(card.getWidth() / 2f);
            card.setPivotY(card.getHeight());
            Motion.scale(card, s, Motion.SNAPPY);
            Motion.to(card, DynamicAnimation.TRANSLATION_Y, -lift * density, Motion.SNAPPY);
            // The shadow grows from the same point (the icon's bottom centre) so it stays under the icon.
            View shadow = item.findViewById(R.id.shadow);
            shadow.setPivotX(shadow.getWidth() / 2f);
            shadow.setPivotY(card.getBottom() - shadow.getTop());
            Motion.scale(shadow, s, Motion.SNAPPY);
            Motion.to(shadow, DynamicAnimation.TRANSLATION_Y, -lift * density, Motion.SNAPPY);
        }
    }

    // Name tooltip above the magnified icon, dot below it, and a soft glow in the app's colour.
    private void showHero(int position, Rect card) {
        heroName.setText(adapter.labelAt(position));
        heroName.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float iconTop = card.bottom - card.height() * DOCK_MAGNIFY - DOCK_LIFT_DP * density;
        heroName.setX(card.exactCenterX() - heroName.getMeasuredWidth() / 2f);
        heroName.setY(iconTop - 8 * density - heroName.getMeasuredHeight());
        dockDot.setX(card.exactCenterX() - dockDot.getWidth() / 2f);
        dockDot.setY(card.bottom + 3 * density);
        heroName.animate().alpha(1f).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
        dockDot.animate().alpha(1f).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
        heroShown = true;
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(120).setInterpolator(Motion.EASE_OUT).start();
        dockDot.animate().alpha(0f).setDuration(120).setInterpolator(Motion.EASE_OUT).start();
        magnifyDock(-1);
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int dock = adapter.dockCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < dock) {                         // dock row: up to this page's tab
                tabApps.requestFocus();
                return true;
            }
            int col = (position - dock) % COLUMNS;
            if (position - dock < COLUMNS) {
                if (dock == 0) {
                    tabApps.requestFocus();
                    return true;
                }
                target = Math.min(col, dock - 1);          // first grid row: up to the dock
            } else {
                target = position - COLUMNS;
            }
        } else {
            if (position < dock) {
                if (count == dock) return true;            // nothing below the dock
                target = Math.min(dock + position, count - 1);
            } else {
                int lastRowStart = dock + ((count - 1 - dock) / COLUMNS) * COLUMNS;
                if (position >= lastRowStart) return true; // already on the last row: stay put
                target = Math.min(position + COLUMNS, count - 1);
            }
        }
        focusTile(target);
        return true;
    }

    private static int lcmUpTo(int n) {
        int l = 1;
        for (int i = 2; i <= n; i++) {
            int a = l, b = i;
            while (b != 0) { int t = a % b; a = b; b = t; }
            l = l / a * i;
        }
        return l;
    }

    // ---- dock editing ----------------------------------------------------------------------------

    @Override
    public void onTileMenu(int position, View card) {
        String pkg = adapter.pkgAt(position);
        boolean docked = adapter.isDocked(position);
        int dock = adapter.dockCount();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (220 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(R.style.PopupFade);   // enter is animated in code; exit fades (120 ms)
        popup.setElevation(0f);

        if (docked) {
            addInputRow(rows, popup, R.drawable.ic_unpin, getString(R.string.unpin_from_dock), -1, () -> {
                adapter.unpin(pkg);
                focusAfterLayout(pkg);
            });
            if (position > 0) addInputRow(rows, popup, R.drawable.ic_left, getString(R.string.move_left), -1, () -> {
                adapter.move(pkg, -1);
                focusAfterLayout(pkg);
            });
            if (position < dock - 1) addInputRow(rows, popup, R.drawable.ic_right, getString(R.string.move_right), -1, () -> {
                adapter.move(pkg, 1);
                focusAfterLayout(pkg);
            });
        } else {
            addInputRow(rows, popup, R.drawable.ic_pin, getString(R.string.pin_to_dock), -1, () -> {
                if (adapter.pin(pkg)) focusAfterLayout(pkg);
                else Toast.makeText(this, R.string.dock_full, Toast.LENGTH_SHORT).show();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_info, getString(R.string.app_info), -1, () ->
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));

        // Float the menu just above the tile, centred on it; the name tooltip steps aside while it's open.
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        float top = docked ? r.bottom - r.height() * DOCK_MAGNIFY - DOCK_LIFT_DP * density : r.top;
        int x = (int) (r.exactCenterX() - width / 2f);
        int y = (int) (top - 12 * density - panel.getMeasuredHeight());
        heroName.animate().alpha(0f).setDuration(120).setInterpolator(Motion.EASE_OUT).start();
        popup.setOnDismissListener(() -> {
            if (adapter.isDocked(lastFocused) && heroShown) heroName.animate().alpha(1f).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
        });
        panel.setAlpha(0f);
        panel.setTranslationY(8 * density);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y);
        panel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(Motion.EASE_OUT).start();
        rows.getChildAt(0).requestFocus();
    }

    // Escape hatch on the TV itself: Menu (or hold OK) on the Settings button switches the Home redirect.
    private void showHomeScreenMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (290 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(R.style.PopupFade);   // enter is animated in code; exit fades (120 ms)
        popup.setElevation(0f);
        boolean on = HomeRedirectService.isEnabled(this);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(on ? R.string.use_amazon_home : R.string.use_this_home), -1, () -> {
            if (!HomeRedirectService.setEnabled(this, !on)) {
                Toast.makeText(this, R.string.redirect_needs_permission, Toast.LENGTH_LONG).show();
            }
        });
        popup.showAsDropDown(anchor, anchor.getWidth() - width, (int) (10 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    /** After the dock changes, focus the app's new tile once the grid has re-laid out. */
    private void focusAfterLayout(String pkg) {
        hideHero();
        ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup().invalidateSpanIndexCache();
        grid.invalidateItemDecorations();
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int pos = adapter.positionOf(pkg);
                RecyclerView.ViewHolder vh = pos >= 0 ? grid.findViewHolderForAdapterPosition(pos) : null;
                if (vh == null) {
                    focusTile(Math.max(pos, 0));
                    return;
                }
                View c = vh.itemView.findViewById(R.id.card);
                c.requestFocus();
                onTileFocused(pos, c);
            }
        });
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    // Morning 5-12, afternoon 12-17, evening 17-22, night 22-5. Indexes GREETINGS and BACKGROUNDS.
    private static final int[] GREETINGS = {
            R.string.good_morning, R.string.good_afternoon, R.string.good_evening, R.string.good_night};
    private static final int[] BACKGROUNDS = {
            R.drawable.bg_morning, R.drawable.bg_afternoon, R.drawable.bg_evening, R.drawable.bg_night};

    private static int dayPart() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return 3;
        if (hour < 12) return 0;
        if (hour < 17) return 1;
        if (hour < 22) return 2;
        return 3;
    }

    private void updateGreeting() {
        String text = getString(GREETINGS[dayPart()]);
        String name = getString(R.string.user_name).trim();
        greeting.setText(name.isEmpty() ? text : text + ", " + name);
    }

    private void updateBackground() {
        int res = BACKGROUNDS[dayPart()];
        if (res == bgRes) return;
        boolean first = bgRes == 0;
        bgRes = res;
        Drawable scrim = getDrawable(R.drawable.bg_scrim);
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;  // 960 x 540 is plenty for a photo under a scrim, and a quarter of the texture
            Bitmap b = ImageLoader.bake(BitmapFactory.decodeResource(getResources(), res, o), scrim);
            handler.post(() -> {
                photo = b;
                if (currentBackground() == photo) showBackground(photo, !first);
            });
        }).start();
    }

    // ---- background ----------------------------------------------------------------------------------
    // The Fire TV Stick's GPU fills about two 1080p layers per frame at 60 fps, so: one picture at rest (scrims
    // baked in) and a second view only while two pictures crossfade.

    private Bitmap currentBackground() {
        return page == PAGE_WATCH && heroBitmap != null ? heroBitmap : photo;
    }

    private void showBackground(Bitmap b, boolean animate) {
        if (b == null || b == shownBg) return;
        shownBg = b;
        bgFront.animate().cancel();
        if (!animate) {
            bgBack.setImageBitmap(b);
            bgFront.setImageDrawable(null);
            bgFront.setAlpha(0f);
            return;
        }
        // Interrupted fade: keep underneath whichever picture was mostly visible.
        if (bgFront.getDrawable() != null && bgFront.getAlpha() > 0.5f) bgBack.setImageDrawable(bgFront.getDrawable());
        bgFront.setImageBitmap(b);
        bgFront.setAlpha(0f);
        bgFront.animate().alpha(1f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE_OUT).withEndAction(() -> {
            bgBack.setImageBitmap(b);
            bgFront.setImageDrawable(null);
            bgFront.setAlpha(0f);
        }).start();
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    // Signal bars only (no network name, so screenshots stay private); text only when something is wrong.
    private void updateWifiLabel() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network net = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
        if (caps == null) {
            setWifiPill(R.drawable.ic_wifi_off, getString(R.string.offline), COLOR_ERROR);
            return;
        }
        boolean online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            setWifiPill(R.drawable.ic_ethernet, online ? getString(R.string.ethernet) : getString(R.string.no_internet),
                    online ? 0 : COLOR_WARN);
            return;
        }
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        int bars = info != null ? WifiManager.calculateSignalLevel(info.getRssi(), WIFI_ICONS.length) : 0;
        if (!online) {
            setWifiPill(WIFI_ICONS[bars], getString(R.string.no_internet), COLOR_WARN);
            return;
        }
        setWifiPill(WIFI_ICONS[bars], getString(R.string.wifi), 0);
    }

    private void setWifiPill(int icon, String label, int alertColor) {
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        btnWifi.setContentDescription(label);
        // Healthy: a round icon button like Inputs and Settings. Alert: a pill with the status text.
        boolean iconOnly = alertColor == 0;
        ViewGroup.LayoutParams lp = btnWifi.getLayoutParams();
        lp.width = iconOnly ? (int) (42 * density) : ViewGroup.LayoutParams.WRAP_CONTENT;
        btnWifi.setLayoutParams(lp);
        btnWifi.setPaddingRelative((int) ((iconOnly ? 10 : 16) * density), 0, (int) ((iconOnly ? 10 : 20) * density), 0);
        btnWifi.setCompoundDrawablePadding(iconOnly ? 0 : (int) (9 * density));
        if (iconOnly) {
            btnWifi.setText("");
            return;
        }
        SpannableString text = new SpannableString("\u25CF  " + label);
        text.setSpan(new ForegroundColorSpan(alertColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        btnWifi.setText(text);
    }

    // ---- pages -----------------------------------------------------------------------------------

    private void setUpPages() {
        watch = findViewById(R.id.watch);
        watch.setListener(this);
        pagesView = findViewById(R.id.pages);
        appsPage = findViewById(R.id.appsPage);
        tabs = findViewById(R.id.tabs);
        tabIndicator = findViewById(R.id.tabIndicator);
        tabWatch = findViewById(R.id.tabWatch);
        tabApps = findViewById(R.id.tabApps);
        // tvOS tab bar: a translucent track; the current tab sits on a grey pill that turns white when focused.
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x24FFFFFF);
        track.setCornerRadius(26 * density);
        tabs.setBackground(track);
        GradientDrawable marker = new GradientDrawable();
        marker.setColor(0x40FFFFFF);
        marker.setCornerRadius(21 * density);
        tabIndicator.setBackground(marker);

        TextView[] tabViews = {tabWatch, tabApps};
        for (int i = 0; i < tabViews.length; i++) {
            final int p = i;
            TextView t = tabViews[i];
            // tvOS: focusing a tab shows its page; OK or Down goes into it.
            t.setOnFocusChangeListener((v, has) -> {
                Motion.scale(v, has ? 1.06f : 1f, Motion.SNAPPY);
                updateTabText();
                if (has && page != p) setPage(p, true);
            });
            t.setOnClickListener(v -> enterPage(p));
            t.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    enterPage(p);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && p == PAGE_APPS) {
                    findViewById(R.id.btnInputs).requestFocus();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_LEFT && p == PAGE_WATCH;
            });
        }
        tabs.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeIndicator(false));
        pagesView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> setPage(page, false));
    }

    private void enterPage(int p) {
        setPage(p, true);
        if (p == PAGE_WATCH) watch.requestFocus();
        else focusTile(Math.max(0, Math.min(lastFocused, adapter.getItemCount() - 1)));
    }

    private void setPage(int p, boolean animate) {
        page = p;
        // Pages swap with a short fade and a small slide in the direction of the tab (24 dp, 250 ms ease-out),
        // each page on a GPU layer for the duration so the fade costs one composite, not a redraw per frame.
        float shift = 24 * density;
        slidePage(watch, p == PAGE_WATCH ? 0f : -shift, p == PAGE_WATCH ? 1f : 0f, animate);
        slidePage(appsPage, p == PAGE_APPS ? 0f : shift, p == PAGE_APPS ? 1f : 0f, animate);
        watch.setFocusable(p == PAGE_WATCH);
        grid.setDescendantFocusability(p == PAGE_APPS
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        int down = p == PAGE_WATCH ? R.id.watch : R.id.grid;
        tabWatch.setNextFocusDownId(down);
        tabApps.setNextFocusDownId(down);
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) findViewById(id).setNextFocusDownId(down);
        if (p == PAGE_WATCH) {
            hideGlass();
            hideHero();
        }
        showBackground(currentBackground(), animate);
        placeIndicator(animate);
        updateTabText();
    }

    private void slidePage(View v, float x, float alpha, boolean animate) {
        v.animate().cancel();
        if (!animate || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            v.setTranslationX(x);
            v.setAlpha(alpha);
            return;
        }
        v.animate().translationX(x).alpha(alpha).setDuration(250).setInterpolator(Motion.EASE_OUT).withLayer().start();
    }

    private void placeIndicator(boolean animate) {
        TextView t = page == PAGE_WATCH ? tabWatch : tabApps;
        if (t.getWidth() == 0) return;
        ViewGroup.LayoutParams lp = tabIndicator.getLayoutParams();
        if (lp.width != t.getWidth()) {
            lp.width = t.getWidth();
            tabIndicator.setLayoutParams(lp);
        }
        float x = t.getLeft();
        if (animate) Motion.to(tabIndicator, DynamicAnimation.TRANSLATION_X, x, Motion.SNAPPY);
        else Motion.set(tabIndicator, DynamicAnimation.TRANSLATION_X, x);
    }

    private void updateTabText() {
        boolean focused = tabWatch.hasFocus() || tabApps.hasFocus();
        ((GradientDrawable) tabIndicator.getBackground()).setColor(focused ? 0xF5FFFFFF : 0x40FFFFFF);
        tabWatch.setTextColor(tabColor(tabWatch, PAGE_WATCH));
        tabApps.setTextColor(tabColor(tabApps, PAGE_APPS));
    }

    private int tabColor(TextView t, int p) {
        if (t.hasFocus()) return 0xFF111111;
        return page == p ? Color.WHITE : 0xB3FFFFFF;
    }

    // ---- Watch page --------------------------------------------------------------------------------

    private void loadWatch() {
        lastLoad = System.currentTimeMillis();
        if (loadedRows.isEmpty() && continueItems.isEmpty()) watch.setRows(buildRows());
        repo.load((cont, rows) -> {
            continueItems = cont;
            loadedRows = rows;
            watch.setRows(buildRows());
        });
    }

    private List<WatchItem.Row> buildRows() {
        List<WatchItem.Row> out = new ArrayList<>();
        WatchItem.Row quick = new WatchItem.Row(getString(R.string.watch_row_quick), Color.WHITE);
        String stremio = getString(R.string.stremio_package), youtube = getString(R.string.youtube_package);

        if (!continueItems.isEmpty()) {
            WatchItem first = continueItems.get(0);
            WatchItem c = chip(WatchItem.STREMIO, getString(R.string.quick_resume, first.title), R.drawable.ic_play);
            c.intent = first.intent;
            c.kicker = getString(R.string.watch_kicker_quick_stremio);
            quick.items.add(c);
        } else if (!repo.signedIn()) {
            WatchItem c = chip(WatchItem.STREMIO, getString(R.string.quick_connect), R.drawable.ic_link);
            c.kicker = getString(R.string.watch_kicker_quick_stremio);
            c.description = getString(R.string.quick_connect_desc);
            c.action = this::showLink;
            quick.items.add(c);
        }
        WatchItem discover = chip(WatchItem.STREMIO, getString(R.string.quick_discover), R.drawable.ic_compass);
        discover.intent = new Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///discover")).setPackage(stremio);
        discover.kicker = getString(R.string.watch_kicker_quick_stremio);
        discover.description = getString(R.string.quick_discover_desc);
        quick.items.add(discover);
        WatchItem openSt = chip(WatchItem.STREMIO, getString(R.string.quick_open, "Stremio"), R.drawable.ic_open);
        openSt.intent = getPackageManager().getLeanbackLaunchIntentForPackage(stremio);
        openSt.kicker = getString(R.string.watch_kicker_quick_stremio);
        quick.items.add(openSt);

        WatchItem.Row yt = null;
        for (WatchItem.Row r : loadedRows) if (r.dotColor == ContentRepository.YOUTUBE_COLOR) yt = r;
        if (yt != null && !yt.items.isEmpty()) {
            WatchItem latest = yt.items.get(0);
            WatchItem c = chip(WatchItem.YOUTUBE, getString(R.string.quick_latest), R.drawable.ic_play);
            c.intent = latest.intent;
            c.kicker = getString(R.string.watch_kicker_quick_youtube);
            quick.items.add(c);
        }
        WatchItem openYt = chip(WatchItem.YOUTUBE, getString(R.string.quick_open, "YouTube"), R.drawable.ic_open);
        openYt.intent = getPackageManager().getLeanbackLaunchIntentForPackage(youtube);
        openYt.kicker = getString(R.string.watch_kicker_quick_youtube);
        openYt.description = getString(R.string.quick_open_youtube_desc);
        quick.items.add(openYt);
        out.add(quick);

        if (!continueItems.isEmpty()) {
            WatchItem.Row cont = new WatchItem.Row(getString(R.string.watch_row_continue), ContentRepository.STREMIO_COLOR);
            cont.items.addAll(continueItems);
            out.add(cont);
        }
        out.addAll(loadedRows);
        return out;
    }

    private WatchItem chip(String app, String title, int icon) {
        WatchItem c = new WatchItem(WatchItem.CHIP, app);
        c.title = title;
        c.iconRes = icon;
        return c;
    }

    @Override
    public void onOpen(WatchItem item) {
        if (item.action != null) {
            item.action.run();
            return;
        }
        String pkg = getString(WatchItem.YOUTUBE.equals(item.app) ? R.string.youtube_package : R.string.stremio_package);
        if (item.intent != null) {
            Intent i = new Intent(item.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // The app doesn't take this link: open the app itself instead.
            }
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) open(launch);
        else Toast.makeText(this, getString(R.string.app_missing, pkg), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onExitUp() {
        tabWatch.requestFocus();
    }

    @Override
    public void onBackdrop(String url) {
        if (url == null) {
            heroUrl = null;
            heroBitmap = null;
            showBackground(currentBackground(), true);
            return;
        }
        if (url.equals(heroUrl)) return;
        heroUrl = url;
        ImageLoader.get(url, ImageLoader.BACKDROP_W, b -> {
            if (!url.equals(heroUrl)) return;
            heroBitmap = b;
            showBackground(currentBackground(), true);
        });
    }

    // ---- Stremio sign-in -----------------------------------------------------------------------------

    /** Stremio's sign-in by code: a panel with the QR code, the code and the link to open on a phone. */
    private void showLink() {
        if (linkPanel != null) return;
        View panel = getLayoutInflater().inflate(R.layout.panel_link, root, false);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF21C1C1E);
        card.setCornerRadius(28 * density);
        panel.findViewById(R.id.linkCard).setBackground(card);
        ImageView qr = panel.findViewById(R.id.linkQr);
        TextView code = panel.findViewById(R.id.linkCode);
        TextView hint = panel.findViewById(R.id.linkHint);
        root.addView(panel);
        linkPanel = panel;
        // Modal: from the centre, never from nothing (scale 0.96 + fade), 220 ms ease-out; it leaves faster.
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.EASE_OUT).withLayer().start();
        View close = panel.findViewById(R.id.linkClose);
        close.setOnClickListener(v -> closeLink());
        // Modal: the arrows stay on the panel instead of moving focus to the page behind it.
        close.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
        close.requestFocus();
        repo.startLink(new ContentRepository.LinkCallback() {
            @Override
            public void onCode(String c, String link, String qrUrl) {
                code.setText(c);
                ImageLoader.into(qr, qrUrl, (int) (200 * density));
                hint.setText(getString(R.string.link_hint, link.replaceFirst("^https?://", "")));
            }

            @Override
            public void onSignedIn() {
                closeLink();
                Toast.makeText(MainActivity.this, R.string.link_done, Toast.LENGTH_SHORT).show();
                loadWatch();
            }

            @Override
            public void onFailed() {
                hint.setText(R.string.link_failed);
            }
        });
    }

    private void closeLink() {
        if (linkPanel == null) return;
        repo.cancelLink();
        View p = linkPanel;
        linkPanel = null;
        watch.requestFocus();
        p.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(150).setInterpolator(Motion.EASE_OUT)
                .withLayer().withEndAction(() -> root.removeView(p)).start();
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(R.style.PopupFade);   // enter is animated in code; exit fades (120 ms)
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.96f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(Motion.EASE_OUT).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
