package com.tongsir.radio;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 极简网络收音机主界面。目标机 LX04：800x480 / 240dpi / API 27 / 无返回键。 */
public class MainActivity extends Activity implements RadioPlayer.Listener {

    private static final int C_ACCENT   = 0xFFFF7A45;
    private static final int C_ICON     = 0xFFD7E0EA;
    private static final int C_MUTED    = 0xFFB7C9DA;
    /** 底栏非主操作键的统一底盘色：深灰蓝 60% 透明。与橙色播放底盘一起，
     *  让六个键"尺寸一致、主次靠颜色区分"。 */
    private static final int C_DISC     = 0x991C242D;
    private static final String TAG     = "MainActivity";

    // ---- 左边缘右滑返回（App 内自实现）--------------------------------------
    // 背景：LX04 的 ROM 并没有把"左边缘右滑"转成 KEYCODE_BACK。真机实测（真人手指）
    // 两次标准左滑（起手 x=3 / x=1，250ms 内横移 390 / 418px）都没有任何返回事件下发，
    // 而物理最左 30px 又被 com.android.systemui 的 TYPE_NAVIGATION_BAR_PANEL 透明窗口
    // 占死（dumpsys window 实测 left/right/bottom 各 30px，layer 271000 > App 21000），
    // App 在这一带收不到任何触摸。所以返回手势只能自己实现，且起手带必须避开那 30px。
    /** 起手带：屏幕左侧 31~144px（约 21~96dp）。下限取 31 而不是 32：
     *  systemui 占的是 x<=30，留 1px 容差抵消 InputReader 的 XScale=0.999
     *  （内核注入 x=32 落到 App 侧是 31.97，卡死在 32 就收不到）。 */
    private static final float SWIPE_START_MIN = 31f;
    private static final float SWIPE_START_MAX = 144f;
    /** 触发阈值：横向位移 >=110px(约73dp)、纵向偏移 <=60px 且不超过横向的 1/2（即夹角<27°）、
     *  900ms 内完成。三个条件一起挡住"在左侧带里纵向滚动/斜拖"的误触。 */
    private static final float SWIPE_DX_MIN = 110f;
    private static final float SWIPE_DY_MAX = 60f;
    private static final long  SWIPE_MS_MAX = 900L;
    private float swX0, swY0;
    private long  swT0;
    private boolean swTrack, swFired;

    /** 顶栏：txtGroup = 左上地域分组标签，txtNet/clock = 右上在线状态与时间 */
    private TextView txtGroup, txtNet, clock;
    private View netDot, stateDot, bufBar;
    private WaveView wave;
    private TextView txtStation, txtTrack, badgeCodec, txtBitrate, txtState;
    private IconView btnVolume, btnPrev, btnPlay, btnNext, btnFav, btnList;
    /** 当前高亮在哪一行。切台时靠它找到"上一台"那行去重绑（全量重绑太贵）。 */
    private int shownIndex = -1;
    /** 底栏透明命中层：铺满整槽，真正吃点击的是它们，图标只负责画 */
    private View hitVolume, hitPrev, hitPlay, hitNext, hitFav, hitList;
    private View listPanel, volPanel;
    private ScrollView listScroll;
    private LinearLayout listContent;
    private TextView txtVol, btnCloseList;
    private SeekBar seekVol;

    private RadioPlayer player;
    private AudioManager am;
    private List<Station> all = new ArrayList<Station>();
    private Set<String> favs = new HashSet<String>();
    /** 收藏顺序：旧的在前、新的在后。收藏时间倒序 = 从后往前读。 */
    private ArrayList<String> favOrder = new ArrayList<String>();
    /** id -> 下标。电台表只加载一次，这份映射也只建一次（收藏/开机定位都靠它）。 */
    private final java.util.HashMap<String, Integer> indexById = new java.util.HashMap<String, Integer>();
    private SharedPreferences sp;

    /** 同一家电台可能在「我的收藏」和原分组里各出现一次，所以索引 -> 行的关系是一对多 */
    private final java.util.HashMap<Integer, java.util.ArrayList<View>> stationRows =
            new java.util.HashMap<Integer, java.util.ArrayList<View>>();
    /** 「我的收藏」置顶分组的 View 引用：收藏变化时只原地重建这一块，不动下面几百个 View */
    private View favHeader = null;
    private LinearLayout favBody = null;
    private View favDivider = null;
    private final ArrayList<View> favRows = new ArrayList<View>();
    private final ArrayList<Integer> favRowIdx = new ArrayList<Integer>();
    /** 电台 -> 所属分组标题，收起状态下把当前台滚到分组标题而不是一片空白 */
    private final java.util.HashMap<Integer, View> stationGroupHeader = new java.util.HashMap<Integer, View>();
    /** 列表是否已构建过。304 台会 inflate 600+ 个 View，不复用的话每次开列表都要重建一遍。 */
    private boolean listBuilt = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.US);

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;

    private android.animation.ValueAnimator scanAnim;
    private int bufW = 0;

    // ================= 生命周期 =================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // 界面在前台期间保持常亮：本应用是"挂着听"的收音机，熄屏等于不能切台/看曲目。
        // FLAG_KEEP_SCREEN_ON 是窗口级标志，窗口不可见（切后台/被盖住/退出）时系统自动失效，
        // 所以后台不会再耗电，不需要 WakeLock，也就没有忘记释放的风险。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sp = getSharedPreferences("radio", MODE_PRIVATE);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        bindViews();
        wireIcons();

        all = StationRepo.load(this);
        // id -> 下标 的映射只依赖电台表，建一次就够。
        // 早前在 favIndexesNewestFirst() 里"每次调用都重建 304 项"，开一次列表就白跑一遍。
        for (int i = 0; i < all.size(); i++) indexById.put(all.get(i).id, Integer.valueOf(i));
        loadFavs();

        player = new RadioPlayer(this);
        player.setListener(this);
        player.setStations(all);

        setupVolumePanel();
        setupButtons();

        updateVolumeLabel(volPercent());

        if (all.isEmpty()) {
            txtStation.setText(R.string.list_empty);
            txtState.setText(R.string.list_empty_detail);
        } else {
            int last = indexOfId(sp.getString("lastId", ""));   // 优先按 id 定位
            if (last < 0) {                                     // 老版本只存了下标 / id 已下线
                last = sp.getInt("lastIndex", 0);
                if (last < 0 || last >= all.size()) last = 0;
            }
            showStation(all.get(last));
            playIndex(last);                       // 开机即播上次的台
        }
        // 注意：clockTick 只在 onResume 启动。onCreate 里再 post 一次会让首次进入
        // 存在两条并行时钟链（每条每 20s 自我重投），随 Resume/Pause 循环累积。
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerNet();
        ui.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(clockTick);
        unregisterNet();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (scanAnim != null) scanAnim.cancel();
        releaseListUi();
        if (player != null) player.release();
    }

    private void bindViews() {
        txtGroup    = (TextView) findViewById(R.id.txtGroup);
        txtNet      = (TextView) findViewById(R.id.txtNet);
        clock       = (TextView) findViewById(R.id.clock);
        netDot      = findViewById(R.id.netDot);
        stateDot    = findViewById(R.id.stateDot);
        bufBar      = findViewById(R.id.bufBar);
        wave        = (WaveView) findViewById(R.id.wave);
        txtStation  = (TextView) findViewById(R.id.txtStation);
        txtTrack    = (TextView) findViewById(R.id.txtTrack);
        badgeCodec  = (TextView) findViewById(R.id.badgeCodec);
        txtBitrate  = (TextView) findViewById(R.id.txtBitrate);
        txtState    = (TextView) findViewById(R.id.txtState);
        btnVolume   = (IconView) findViewById(R.id.btnVolume);
        btnPrev     = (IconView) findViewById(R.id.btnPrev);
        btnPlay     = (IconView) findViewById(R.id.btnPlay);
        btnNext     = (IconView) findViewById(R.id.btnNext);
        btnFav      = (IconView) findViewById(R.id.btnFav);
        btnList     = (IconView) findViewById(R.id.btnList);
        hitVolume   = findViewById(R.id.hitVolume);
        hitPrev     = findViewById(R.id.hitPrev);
        hitPlay     = findViewById(R.id.hitPlay);
        hitNext     = findViewById(R.id.hitNext);
        hitFav      = findViewById(R.id.hitFav);
        hitList     = findViewById(R.id.hitList);
        listPanel   = findViewById(R.id.listPanel);
        volPanel    = findViewById(R.id.volPanel);
        listScroll  = (ScrollView) findViewById(R.id.listScroll);
        listContent = (LinearLayout) findViewById(R.id.listContent);
        txtVol      = (TextView) findViewById(R.id.txtVol);
        seekVol     = (SeekBar) findViewById(R.id.seekVol);
        btnCloseList= (TextView) findViewById(R.id.btnCloseList);

        // LX04 会把滚动条画在左侧且延迟淡出，XML 的 scrollbars=none 拦不住，这里再关一遍
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setHorizontalScrollBarEnabled(false);
        listScroll.setScrollbarFadingEnabled(true);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    private void wireIcons() {
        // 底栏六个图标统一规格：
        //   尺寸  —— XML 里统一 40dp（底盘 58px），形状差异由 IconView.shapeHeight 归一；
        //   底盘  —— 这里统一"都有底盘"。改造前是"四盘两裸"：
        //            音量只有一圈常驻环(49px)、收藏完全裸着(33x28)，
        //            与播放的 73px 橙盘并排，视觉重量差 2 倍以上。
        // 播放键改用"橙色底盘"而非"更大尺寸"来体现主操作地位。
        btnVolume.setType(IconView.VOLUME);
        btnVolume.setIconColor(C_MUTED);
        btnVolume.setBgColor(C_DISC);
        btnPrev.setType(IconView.PREV);
        btnPrev.setIconColor(C_ICON);
        btnPrev.setBgColor(C_DISC);
        btnPlay.setType(IconView.PLAY);
        btnPlay.setIconColor(0xFFFFFFFF);
        btnPlay.setBgColor(C_ACCENT);
        btnNext.setType(IconView.NEXT);
        btnNext.setIconColor(C_ICON);
        btnNext.setBgColor(C_DISC);
        btnFav.setType(IconView.HEART);
        btnFav.setIconColor(C_MUTED);
        btnFav.setBgColor(C_DISC);
        btnList.setType(IconView.LIST);
        btnList.setIconColor(C_ICON);
        btnList.setBgColor(C_DISC);
        wave.setWaveColor(C_ACCENT);
    }

    // ================= 按钮 =================

    private void setupButtons() {
        btnCloseList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleList(); }
        });
        // 底栏：点击全部挂在铺满槽位的透明命中层上（hitXxx），
        // IconView 只负责画。按下态由 ViewGroup.dispatchSetPressed 自动回传给图标。
        // 列表入口只剩底栏最右这一个（左上角那个重复入口已删除）
        hitList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleList(); }
        });
        hitPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { player.prev(); }
        });
        hitNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { player.next(); }
        });
        hitPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { player.toggle(); }
        });
        // 无返回键设备：长按播放键退出
        hitPlay.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                player.release();
                finish();
                return true;
            }
        });
        hitFav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFav(); }
        });
        hitVolume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openVolumePanel(); }
        });
        volPanel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeVolumePanel(); }
        });
    }

    /**
     * 左边缘右滑返回。
     * 收口在 onBackPressed()，所以"先关列表面板、再关音量面板、最后才退出"的逐层退栈
     * 与实体返回键完全一致。不消费事件，照常交给 super 派发给子 View。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swTrack = !swFired
                        && ev.getX() >= SWIPE_START_MIN && ev.getX() <= SWIPE_START_MAX;
                if (swTrack) { swX0 = ev.getX(); swY0 = ev.getY(); swT0 = ev.getEventTime(); }
                break;
            case MotionEvent.ACTION_MOVE:
                if (swTrack && ev.getPointerCount() == 1) {
                    float dx = ev.getX() - swX0;
                    float dy = Math.abs(ev.getY() - swY0);
                    if (dx >= SWIPE_DX_MIN && dy <= SWIPE_DY_MAX && dy <= dx * 0.5f
                            && ev.getEventTime() - swT0 <= SWIPE_MS_MAX) {
                        swTrack = false;
                        swFired = true;      // 同一次手势只触发一次，抬起后才复位
                        android.util.Log.i(TAG, "edge swipe dx=" + (int) dx + " dy=" + (int) dy
                                + " -> onBackPressed");
                        onBackPressed();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swTrack = false;
                swFired = false;
                break;
            default:
                break;      // 多指按下/抬起：不打断已开始跟踪的手势
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 实体音量键：交给系统调 STREAM_MUSIC，这里只同步弹层显示 */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    updateVolumeLabel(volPercent());
                    if (seekVol != null) seekVol.setProgress(volPercent());
                }
            }, 120);
            return super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            // 这里只留痕，不再 return true 吞掉。
            // 旧实现直接吞掉 BACK，等于掐断了 Activity 的 startTracking() ->
            // onKeyUp() -> onBackPressed() 这条链路：ROM 的"左侧右滑返回"手势若以
            // KEYCODE_BACK 注入，也会一并被吞掉，导致全局返回手势在本应用内失效。
            android.util.Log.i(TAG, "BACK down  list=" + (listPanel.getVisibility() == View.VISIBLE)
                    + " vol=" + (volPanel.getVisibility() == View.VISIBLE));
        }
        return super.onKeyDown(code, e);
    }

    /**
     * 返回语义统一在这里收口（ROM 手势 / 实体返回键 / 框架注入都汇到这一个入口）。
     * 逐层退：先关列表，再关音量面板，都没有才真正退出。
     */
    @Override
    public void onBackPressed() {
        android.util.Log.i(TAG, "onBackPressed  list=" + (listPanel.getVisibility() == View.VISIBLE)
                + " vol=" + (volPanel.getVisibility() == View.VISIBLE));
        if (listPanel.getVisibility() == View.VISIBLE) { toggleList(); return; }
        if (volPanel.getVisibility() == View.VISIBLE) { closeVolumePanel(); return; }
        if (player != null) player.release();     // 先停播放/让出音频焦点，再走默认 finish
        super.onBackPressed();
    }

    private void toggleList() {
        boolean show = listPanel.getVisibility() != View.VISIBLE;
        if (show) {
            if (!listBuilt) { buildListUi(); listBuilt = true; }
            else {
                // 列表在缓存期间收藏可能变过、当前台可能变过
                refreshFavGroup();
                refreshListHighlight();
            }
            shownIndex = player.getIndex();   // 上面两条路径都是全量绑定，同步一下"上一台"的锚点
            scrollToCurrent();
        }
        listPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ================= 电台列表 =================

    /** 打开列表时全量重建。用 ScrollView 手动摆 View —— ListView 的滚动条在 LX04 上关不掉，还会残留。 */
    private void buildListUi() {
        listContent.removeAllViews();
        stationRows.clear();
        stationGroupHeader.clear();
        favRows.clear();
        favRowIdx.clear();
        favHeader = null;
        favBody = null;
        favDivider = null;
        LayoutInflater inf = LayoutInflater.from(this);

        // 「我的收藏」置顶分组（无收藏时整块不渲染），插在所有地域分组之前
        buildFavGroup(inf);

        // 按 group 聚合，保持电台在原始数据里的先后顺序；无归属的进"其他"
        java.util.LinkedHashMap<String, java.util.List<Integer>> groups =
                new java.util.LinkedHashMap<String, java.util.List<Integer>>();
        for (int i = 0; i < all.size(); i++) {
            Station s = all.get(i);
            String g = (s.group == null || s.group.length() == 0) ? getString(R.string.group_other) : s.group;
            java.util.List<Integer> l = groups.get(g);
            if (l == null) {
                l = new ArrayList<Integer>();
                groups.put(g, l);
            }
            l.add(Integer.valueOf(i));
        }

        for (java.util.Map.Entry<String, java.util.List<Integer>> e : groups.entrySet()) {
            final java.util.List<Integer> ids = e.getValue();
            if (ids.isEmpty()) continue;                    // 空分组不渲染

            final View header = inf.inflate(R.layout.item_group, listContent, false);
            ((TextView) header.findViewById(R.id.groupName)).setText(e.getKey());
            ((TextView) header.findViewById(R.id.groupCount)).setText(getString(R.string.group_count, Integer.valueOf(ids.size())));
            IconView arrow = (IconView) header.findViewById(R.id.groupArrow);
            arrow.setType(IconView.CHEVRON);
            arrow.setIconColor(C_MUTED);
            arrow.setRotation(-90f);                        // 默认收起：箭头朝右

            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            body.setVisibility(View.GONE);                  // 默认全部收起

            for (int k = 0; k < ids.size(); k++) {
                final int idx = ids.get(k).intValue();
                Station s = all.get(idx);
                View row = inf.inflate(R.layout.item_station, body, false);
                bindStationRow(row, s, idx);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        playIndex(idx);
                        listPanel.setVisibility(View.GONE);
                    }
                });
                body.addView(row);
                addStationRow(idx, row);
                stationGroupHeader.put(Integer.valueOf(idx), header);

                View div = new View(this);
                div.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
                div.setBackgroundColor(0xFF1A222B);
                body.addView(div);
            }

            header.setTag(body);
            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleGroup(header); }
            });
            listContent.addView(header);
            listContent.addView(body);
        }
    }

    /** 索引 -> 行（一对多：收藏分组里可能还有一份） */
    private void addStationRow(int idx, View row) {
        Integer k = Integer.valueOf(idx);
        java.util.ArrayList<View> l = stationRows.get(k);
        if (l == null) {
            l = new java.util.ArrayList<View>();
            stationRows.put(k, l);
        }
        l.add(row);
    }

    /** 把「我的收藏」这一块从列表里摘掉（连同它在 stationRows 里的行） */
    private void clearFavGroup() {
        for (int k = 0; k < favRowIdx.size(); k++) {
            java.util.ArrayList<View> l = stationRows.get(favRowIdx.get(k));
            if (l != null) {
                l.remove(favRows.get(k));
                if (l.isEmpty()) stationRows.remove(favRowIdx.get(k));
            }
        }
        favRows.clear();
        favRowIdx.clear();
        if (favHeader != null) { listContent.removeView(favHeader); favHeader = null; }
        if (favBody != null) { listContent.removeView(favBody); favBody = null; }
        if (favDivider != null) { listContent.removeView(favDivider); favDivider = null; }
    }

    /** 收藏电台的列表下标，按收藏时间倒序（最新收藏排最前）；收藏表里已不存在的条目跳过。 */
    private ArrayList<Integer> favIndexesNewestFirst() {
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int k = favOrder.size() - 1; k >= 0; k--) {      // favOrder 旧的在前，倒着读 = 倒序
            String id = favOrder.get(k);
            Integer idx = indexById.get(id);
            if (idx != null && favs.contains(id)) out.add(idx);
        }
        return out;
    }

    /**
     * 构建「我的收藏」置顶分组并插到列表最前（index 0/1/2）。
     * 组内电台同时在原分组里保留一份 —— 这里只是多渲染一行，不改原始数据与分组结构。
     * 没有任何收藏时整块不渲染，不占空位。
     */
    private void buildFavGroup(LayoutInflater inf) {
        clearFavGroup();
        final ArrayList<Integer> ids = favIndexesNewestFirst();
        if (ids.isEmpty()) return;

        final View header = inf.inflate(R.layout.item_group, listContent, false);
        TextView nm = (TextView) header.findViewById(R.id.groupName);
        nm.setText(R.string.fav_group_title);
        nm.setTextColor(C_ACCENT);
        ((TextView) header.findViewById(R.id.groupCount)).setText(getString(R.string.group_count, Integer.valueOf(ids.size())));
        // 与下方各分组完全一致：同一个 CHEVRON 组件、同样的颜色/尺寸/间距
        IconView mark = (IconView) header.findViewById(R.id.groupArrow);
        mark.setType(IconView.CHEVRON);
        mark.setIconColor(C_MUTED);
        mark.setRotation(0f);                     // 默认展开：三角朝下

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        body.setVisibility(View.VISIBLE);         // 置顶分组默认展开，其余分组仍默认收起

        for (int k = 0; k < ids.size(); k++) {
            final int idx = ids.get(k).intValue();
            Station s = all.get(idx);
            View row = inf.inflate(R.layout.item_station, body, false);
            bindStationRow(row, s, idx);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    playIndex(idx);
                    listPanel.setVisibility(View.GONE);
                }
            });
            body.addView(row);
            addStationRow(idx, row);
            favRows.add(row);
            favRowIdx.add(Integer.valueOf(idx));

            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(0xFF1A222B);
            body.addView(div);
        }

        header.setTag(body);
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleGroup(header); }
        });

        // 与下方地域分组之间的分隔线：橙色细线，进一步区分置顶分组
        View line = new View(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2));
        line.setBackgroundColor(0x59FF7A45);

        favHeader = header;
        favBody = body;
        favDivider = line;
        listContent.addView(header, 0);
        listContent.addView(body, 1);
        listContent.addView(line, 2);
    }

    /** 收藏变化后就地重建置顶分组（列表没建过就不用管，下次建列表自然是对的） */
    private void refreshFavGroup() {
        if (!listBuilt || listContent == null) return;
        buildFavGroup(LayoutInflater.from(this));
    }

    /** 展开/收起某个分组。只切 visibility，不重建 —— 304 台全量 inflate 只在首次建列表时做一次。 */
    private void toggleGroup(View header) {
        View body = (View) header.getTag();
        if (body == null) return;
        boolean open = body.getVisibility() != View.VISIBLE;
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        View arrow = header.findViewById(R.id.groupArrow);
        if (arrow != null) {
            arrow.animate().cancel();
            arrow.animate()
                    .rotation(open ? 0f : -90f)
                    .setDuration(150)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        }
    }

    /** 把当前台滚到可见处。要等一帧才有 getTop()，所以 post。 */
    private void scrollToCurrent() {
        final java.util.ArrayList<View> rows = stationRows.get(Integer.valueOf(player.getIndex()));
        final View hdr = stationGroupHeader.get(Integer.valueOf(player.getIndex()));
        listScroll.post(new Runnable() {
            @Override public void run() {
                // 同一家电台可能有多行（收藏分组 + 原分组），取第一个真正可见的
                View target = null;
                if (rows != null) {
                    for (int i = 0; i < rows.size(); i++) {
                        View r = rows.get(i);
                        View parent = (r != null && r.getParent() instanceof View)
                                ? (View) r.getParent() : null;
                        if (r != null && r.getVisibility() == View.VISIBLE
                                && (parent == null || parent.getVisibility() == View.VISIBLE)) {
                            target = r;
                            break;
                        }
                    }
                }
                // 都收起时该行不参与布局，getTop() 无意义 —— 退而滚到分组标题
                if (target == null) target = hdr;
                if (target != null) listScroll.scrollTo(0, Math.max(0, target.getTop() - 70));
                else listScroll.scrollTo(0, 0);
            }
        });
    }

    /** Activity 销毁时断开 View 引用，别把 600+ 个 View 挂在 HashMap 上等 GC。 */
    private void releaseListUi() {
        listContent.removeAllViews();
        stationRows.clear();
        stationGroupHeader.clear();
        favRows.clear();
        favRowIdx.clear();
        favHeader = null;
        favBody = null;
        favDivider = null;
        listBuilt = false;
    }

    private void bindStationRow(View cv, Station s, int stationIndex) {
        TextView nm  = (TextView) cv.findViewById(R.id.itemName);
        TextView sub = (TextView) cv.findViewById(R.id.itemSub);
        TextView tag = (TextView) cv.findViewById(R.id.itemTag);
        View dot     = cv.findViewById(R.id.itemDot);

        nm.setText(s.name);
        StringBuilder sb = new StringBuilder();
        if (s.group != null && s.group.length() > 0) sb.append(s.group);
        if (s.codec != null && s.codec.length() > 0) sb.append(sb.length() > 0 ? " · " : "").append(s.codec);
        if (s.verified) sb.append(" · ").append(getString(R.string.badge_verified));
        sub.setText(sb.toString());

        boolean isFav = favs.contains(s.id);
        tag.setVisibility(isFav ? View.VISIBLE : View.GONE);
        tag.setText(R.string.tag_fav);

        boolean cur = (stationIndex == player.getIndex());
        nm.setTextColor(cur ? C_ACCENT : 0xFFE6EDF3);
        dot.setBackgroundResource(cur ? R.drawable.dot_on
                : (s.verified ? R.drawable.dot_off : R.drawable.dot_warn));
    }

    /** 列表已展开时刷新高亮/收藏标记（不重建，避免滚动位置丢失） */
    private void refreshListHighlight() {
        for (Integer k : stationRows.keySet()) {
            java.util.ArrayList<View> rows = stationRows.get(k);
            int i = k.intValue();
            if (rows == null || i >= all.size()) continue;
            for (int j = 0; j < rows.size(); j++) {
                View row = rows.get(j);
                if (row != null) bindStationRow(row, all.get(i), i);
            }
        }
    }

    /**
     * 只重绑"某一家电台"的那些行（收藏组里的那份也一起更新）。
     * 切台 / 收藏只影响当前台和上一个当前台这两行，没必要把 300 多行全跑一遍。
     * 全量刷新只在"列表刚打开"这种一次性的场景用。
     */
    private void refreshRow(int idx) {
        if (idx < 0 || idx >= all.size()) return;
        java.util.ArrayList<View> rows = stationRows.get(Integer.valueOf(idx));
        if (rows == null) return;
        for (int j = 0; j < rows.size(); j++) {
            View row = rows.get(j);
            if (row != null) bindStationRow(row, all.get(idx), idx);
        }
    }

    // ================= 音量 =================

    private int volPercent() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return 0;
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private void updateVolumeLabel(int pct) {
        txtVol.setText(String.valueOf(pct));
        btnVolume.setRingColor(pct == 0 ? 0xFF6E8090 : C_ACCENT);
    }

    private void setupVolumePanel() {
        seekVol.setMax(100);
        seekVol.setProgress(volPercent());
        seekVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int v = Math.round(max * p / 100f);
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); }
                catch (Throwable t) { android.util.Log.w(TAG, "setStreamVolume(" + v + "): " + t); }
                updateVolumeLabel(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void openVolumePanel() {
        int p = volPercent();
        seekVol.setProgress(p);
        updateVolumeLabel(p);
        volPanel.setVisibility(View.VISIBLE);
    }

    private void closeVolumePanel() { volPanel.setVisibility(View.GONE); }

    // ================= 收藏 =================

    private void loadFavs() {
        favs = new HashSet<String>(sp.getStringSet("favs", new HashSet<String>()));
        // 电台表里已下线的 id 会永久堆在 prefs 里：既占空间，又让每次开列表都要白跑一遍。
        // 这里按当前表剪一次，只有真的剪掉了才回写。
        int before = favs.size();
        HashSet<String> live = new HashSet<String>();
        for (int i = 0; i < all.size(); i++) live.add(all.get(i).id);
        favs.retainAll(live);
        favOrder = loadFavOrder();
        if (favs.size() != before) {
            saveFavs();
            android.util.Log.i(TAG, "清理已下线电台的收藏 " + (before - favs.size()) + " 条");
        }
    }

    /**
     * 收藏顺序（旧的在前）。老版本没有这份数据时按电台表顺序补齐，
     * 保证升级前已收藏的电台照样出现在「我的收藏」里，只是没有精确时间戳。
     */
    private ArrayList<String> loadFavOrder() {
        ArrayList<String> out = new ArrayList<String>();
        String raw = sp.getString("favs_seq", "");
        if (raw != null && raw.length() > 0) {
            String[] parts = raw.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].length() > 0 && !out.contains(parts[i])) out.add(parts[i]);
            }
        }
        for (int i = 0; i < all.size(); i++) {          // 补齐：收藏里有、序列里没有的
            String id = all.get(i).id;
            if (favs.contains(id) && !out.contains(id)) out.add(id);
        }
        java.util.Iterator<String> it = out.iterator();  // 清掉已取消收藏 / 电台表里已没有的
        while (it.hasNext()) {
            if (!favs.contains(it.next())) it.remove();
        }
        return out;
    }

    private void saveFavs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < favOrder.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(favOrder.get(i));
        }
        sp.edit()
                .putStringSet("favs", new HashSet<String>(favs))
                .putString("favs_seq", sb.toString())
                .apply();
    }

    private void toggleFav() {
        Station s = player.current();
        if (s == null) return;
        if (favs.contains(s.id)) {
            favs.remove(s.id);
            favOrder.remove(s.id);
        } else {
            favs.add(s.id);
            favOrder.remove(s.id);
            favOrder.add(s.id);                 // 追加到末尾 = 最新收藏
        }
        saveFavs();
        refreshFavIcon();
        refreshFavGroup();                      // 置顶分组实时增删（会重建收藏组自己的行）
        refreshRow(player.getIndex());          // 原分组里那一行的"收藏"标记要跟着变
    }

    private void refreshFavIcon() {
        Station s = player.current();
        boolean on = (s != null && favs.contains(s.id));
        btnFav.setType(on ? IconView.HEART_ON : IconView.HEART);
        btnFav.setIconColor(on ? C_ACCENT : C_MUTED);
    }

    // ================= 时间 / 网络 =================

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            clock.setText(hhmm.format(new Date()));
            ui.postDelayed(this, 20000);
        }
    };

    private void registerNet() {
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { ui.post(new Runnable() {
                    @Override public void run() { setNet(true); } }); }
                @Override public void onLost(Network n) { ui.post(new Runnable() {
                    @Override public void run() { setNet(false); } }); }
            };
            cm.registerDefaultNetworkCallback(netCb);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            setNet(ni != null && ni.isConnected());
        } catch (Throwable t) {
            // 拿不到联网状态回调不影响播放，但留痕便于区分"真的离线"和"回调注册失败"
            android.util.Log.w(TAG, "注册网络回调失败: " + t);
            setNet(true);
        }
    }

    private void unregisterNet() {
        try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); }
        catch (Throwable t) { android.util.Log.d(TAG, "unregisterNetworkCallback: " + t); }
        netCb = null;
    }

    private void setNet(boolean ok) {
        netDot.setBackgroundResource(ok ? R.drawable.dot_on : R.drawable.dot_bad);
        txtNet.setText(ok ? getString(R.string.net_online) : getString(R.string.net_offline));
        // 颜色实测（背景是遮罩后的顶栏 (47,95,135)）：
        //   在线 原 #7E8C9A 对比度仅 1.96 -> #DCE8F4 = 5.44
        //   离线 原 #FF5A5A 对比度仅 2.21 -> #FFC0C0 = 4.36
        // 红色相保留（离线仍是红色语义），只是提亮到能看清。
        txtNet.setTextColor(ok ? 0xFFDCE8F4 : 0xFFFFC0C0);
    }

    // ================= 状态回调 =================

    @Override
    public void onState(int state, String text) {
        txtState.setText(text);
        boolean busy = (state == RadioPlayer.CONNECTING
                || state == RadioPlayer.BUFFERING
                || state == RadioPlayer.RETRYING);
        boolean playing = (state == RadioPlayer.PLAYING);

        switch (state) {
            case RadioPlayer.PLAYING:
                stateDot.setBackgroundResource(R.drawable.dot_on);
                break;
            case RadioPlayer.ERROR:
                stateDot.setBackgroundResource(R.drawable.dot_bad);
                break;
            case RadioPlayer.CONNECTING:
            case RadioPlayer.BUFFERING:
            case RadioPlayer.RETRYING:
                stateDot.setBackgroundResource(R.drawable.dot_warn);
                break;
            default:
                stateDot.setBackgroundResource(R.drawable.dot_off);
                break;
        }

        btnPlay.setType(playing || busy ? IconView.PAUSE : IconView.PLAY);
        wave.setRunning(playing);
        setScanning(busy);
    }

    @Override
    public void onStationChanged(Station s, int index) {
        showStation(s);
        refreshFavIcon();
        // 列表开着时若从别的途径切台，旧行的高亮会留在原地。
        // 只重绑"上一台 + 当前台"这两行；列表没开时下面几百行本来是陈旧的，开列表时会全量刷。
        if (listBuilt && listPanel.getVisibility() == View.VISIBLE) {
            if (shownIndex >= 0 && shownIndex != index) refreshRow(shownIndex);
            refreshRow(index);
        }
        shownIndex = index;
        // 存 id 也存下标：id 是真正的主键（电台表重排后下标会指向另一家台），
        // 下标只作为"id 查不到"时的兜底，兼容旧版本写下的 prefs。
        SharedPreferences.Editor ed = sp.edit().putInt("lastIndex", index);
        if (s != null && s.id != null && s.id.length() > 0) ed.putString("lastId", s.id);
        ed.apply();
    }

    /** 按电台 id 反查下标，找不到返回 -1。 */
    private int indexOfId(String id) {
        if (id == null || id.length() == 0) return -1;
        Integer i = indexById.get(id);
        return i == null ? -1 : i.intValue();
    }

    private void showStation(Station s) {
        if (s == null) return;
        txtStation.setText(s.name);
        txtGroup.setText(s.group == null ? "" : s.group);
        txtTrack.setText(s.desc != null && s.desc.length() > 0 ? s.desc : getString(R.string.track_live));

        if (s.codec != null && s.codec.length() > 0) {
            badgeCodec.setVisibility(View.VISIBLE);
            badgeCodec.setText(s.codec);
        } else {
            badgeCodec.setVisibility(View.GONE);
        }
        // HLS 直播流拿不到码率（MediaPlayer 不暴露，ICY 头也不存在于 TS 流），不写死假数据
        txtBitrate.setVisibility(View.GONE);
    }

    // ================= 缓冲扫光条 =================

    private void setScanning(boolean on) {
        if (on) {
            if (bufBar.getVisibility() != View.VISIBLE) {
                bufBar.setVisibility(View.VISIBLE);
                startScan();
            }
        } else {
            if (bufBar.getVisibility() == View.VISIBLE) {
                bufBar.setVisibility(View.INVISIBLE);
                stopScan();
            }
        }
    }

    private void startScan() {
        final View parent = (View) bufBar.getParent();
        int total = parent.getWidth();
        if (total <= 0) {
            total = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    533, getResources().getDisplayMetrics());
        }
        bufW = Math.max(60, (int) (total * 0.28f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bufBar.getLayoutParams();
        lp.width = bufW;
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
        bufBar.setLayoutParams(lp);

        if (scanAnim != null) scanAnim.cancel();
        scanAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        scanAnim.setDuration(1100);
        scanAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scanAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        scanAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        final int span = total - bufW;
        scanAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                bufBar.setTranslationX(span * (Float) a.getAnimatedValue());
            }
        });
        scanAnim.start();
    }

    private void stopScan() {
        if (scanAnim != null) { scanAnim.cancel(); scanAnim = null; }
        bufBar.setTranslationX(0f);
    }

    private void playIndex(int i) {
        player.playIndex(i);
    }
}
