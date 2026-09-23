package com.tongsir.radio;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * 播放核心。
 *
 * 设计依据全部来自 LX04 真机实测：
 *  - HLS(TS) 源 MediaPlayer 可直接播，起播 1.1~1.8s；嵌套一层的 satellitepull 源 3.5s
 *  - 直播流 duration=-1、getCurrentPosition() 从 0 持续递增 —— 用"位置是否推进"做卡死探测
 *  - 直播流"暂停"没有续播语义（恢复会跳到实时点），所以暂停 = 释放，恢复 = 重连
 *  - 切台必须能打断上一条连接，否则多个后台连接会抢带宽、声音打架 → 用递增 requestId
 */
public class RadioPlayer {

    private static final String TAG = "RadioPlayer";

    public static final int IDLE       = 0;
    public static final int CONNECTING = 1;
    public static final int BUFFERING  = 2;
    public static final int PLAYING    = 3;
    public static final int PAUSED     = 4;
    public static final int RETRYING   = 5;
    public static final int ERROR      = 6;

    public interface Listener {
        void onState(int state, String text);
        void onStationChanged(Station s, int index);
    }

    private static final int MAX_RETRY = 4;
    private static final long[] BACKOFF = {1000L, 2000L, 4000L, 8000L};
    private static final long STALL_TICK = 3000L;
    /** 连续稳定播放超过这个时长，才认为"这次连接是好的"，允许把重试计数清零 */
    private static final long STABLE_MS = 60000L;

    private final Context app;
    private final AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());

    private MediaPlayer mp;
    private List<Station> list;
    private int index = -1;
    private int state = IDLE;
    private int retry = 0;
    private int reqId = 0;
    private boolean userPaused = false;

    private int lastPos = -1;
    private int stallCount = 0;
    private long playStartedAt = 0;
    /** 焦点被临时抢走（来电/导航播报等），拿到焦点后应自动恢复播放 */
    private boolean focusTransient = false;

    private Listener listener;

    /**
     * 音频焦点回调。
     * LX04 上只有一路 STREAM_MUSIC，不处理 TRANSIENT 会出现"导航播报和电台同时出声"。
     * 各分支都带 userPaused 守卫：pause() 内部会主动 abandonFocus，那会再触发一次
     * AUDIOFOCUS_LOSS，没有守卫就会无限递归。
     */
    private final AudioManager.OnAudioFocusChangeListener focusCb =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int f) {
                    switch (f) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            focusTransient = false;
                            if (!userPaused) pause(true);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            if (!userPaused) { focusTransient = true; pause(true); }
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            if (focusTransient) {
                                focusTransient = false;
                                retry = 0;
                                userPaused = false;
                                start();
                            }
                            break;
                        default:
                            break;
                    }
                }
            };

    public RadioPlayer(Context c) {
        this.app = c.getApplicationContext();
        this.am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(Listener l) { listener = l; }

    public void setStations(List<Station> l) { list = l; }

    public List<Station> getStations() { return list; }

    public int getState() { return state; }

    public int getIndex() { return index; }

    public Station current() {
        if (list == null || index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    // ---------------- 对外操作 ----------------

    public void playIndex(int i) {
        if (list == null || list.isEmpty()) return;
        int n = list.size();
        i = ((i % n) + n) % n;
        index = i;
        retry = 0;
        userPaused = false;
        start();
    }

    public void next() { if (index >= 0) playIndex(index + 1); else playIndex(0); }

    public void prev() { if (index >= 0) playIndex(index - 1); else playIndex(0); }

    public void toggle() {
        if (state == PLAYING || state == BUFFERING || state == CONNECTING || state == RETRYING) {
            pause(false);
        } else {
            if (current() == null) playIndex(0);
            else { retry = 0; userPaused = false; start(); }
        }
    }

    /** @param silent true 时不改 UI 文案（来电等外部原因触发的静默暂停） */
    public void pause(boolean silent) {
        userPaused = true;
        stopInternal();
        abandonFocus();                  // 暂停即让出焦点，否则别的播放器请求不到
        if (state == PAUSED) return;     // 幂等：焦点抖动时不要反复刷 UI
        setState(PAUSED, app.getString(silent ? R.string.player_paused
                    : R.string.player_paused_hint));
    }

    public void release() {
        reqId++;
        stopInternal();
        abandonFocus();
        focusTransient = false;
        state = IDLE;
    }

    // ---------------- 内部 ----------------

    private void start() {
        Station s = current();
        if (s == null) return;
        stopInternal();
        requestFocus();

        final int my = ++reqId;
        setState(CONNECTING, app.getString(R.string.player_connecting));
        if (listener != null) listener.onStationChanged(s, index);

        MediaPlayer m = new MediaPlayer();
        mp = m;
        try {
            m.setAudioStreamType(AudioManager.STREAM_MUSIC);
            m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    if (my != reqId) return;      // 已被切台打断
                    stallCount = 0;
                    lastPos = -1;
                    playStartedAt = System.currentTimeMillis();
                    try { p.start(); } catch (Throwable t) {
                        Log.w(TAG, "start() 失败: " + t);
                    }
                    setState(PLAYING, app.getString(R.string.player_playing));
                    h.removeCallbacks(stallCheck);
                    h.postDelayed(stallCheck, STALL_TICK);
                }
            });
            m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) {
                    if (my != reqId) return true;
                    Log.w(TAG, "onError what=" + what + " extra=" + extra);
                    scheduleRetry(describeError(extra));
                    return true;
                }
            });
            m.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer p, int what, int extra) {
                    if (my != reqId) return false;
                    if (what == 701) {                       // MEDIA_INFO_BUFFERING_START
                        if (state != RETRYING) setState(BUFFERING, app.getString(R.string.player_buffering));
                    } else if (what == 702 || what == 801 || what == 802) {
                        // 702 = BUFFERING_END；801/802 = MTK 的音频渲染开始（实测每条 HLS 起播都会带 801）
                        if (state == BUFFERING || state == CONNECTING) setState(PLAYING, app.getString(R.string.player_playing));
                    }
                    return false;
                }
            });
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) {
                    if (my != reqId) return;
                    // 直播流理论上不会走这里；真走到说明流被服务端主动断开
                    scheduleRetry(app.getString(R.string.retry_ended));
                }
            });
            m.setDataSource(s.url);
            m.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "setDataSource/prepareAsync 异常: " + t);
            if (my == reqId) scheduleRetry(app.getString(R.string.retry_bad_url));
        }
    }

    private void stopInternal() {
        h.removeCallbacks(stallCheck);
        MediaPlayer m = mp;
        mp = null;
        if (m != null) {
            // 释放失败不致命（切台路径上很常见），但必须留痕，否则"某台切完没声音"
            // 这类问题在 logcat 里完全查不到。
            try { m.stop(); } catch (Throwable t) { Log.d(TAG, "stop(): " + t); }
            try { m.reset(); } catch (Throwable t) { Log.d(TAG, "reset(): " + t); }
            try { m.release(); } catch (Throwable t) { Log.w(TAG, "release(): " + t); }
        }
        lastPos = -1;
        stallCount = 0;
    }

    private void scheduleRetry(String why) {
        stopInternal();
        if (userPaused) return;
        if (retry >= MAX_RETRY) {
            setState(ERROR, app.getString(R.string.player_error, why));
            return;
        }
        long d = BACKOFF[Math.min(retry, BACKOFF.length - 1)];
        retry++;
        setState(RETRYING, app.getString(R.string.player_retrying, Integer.valueOf(retry)));
        final int my = reqId;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (my == reqId && !userPaused) start();
            }
        }, d);
    }

    /**
     * 卡死探测：直播流 duration=-1，没有"播完了"事件，所以只能看位置有没有推进。
     * 连续 2 次（约 6 秒）不动就判卡死并重连。
     */
    private final Runnable stallCheck = new Runnable() {
        @Override public void run() {
            if (state != PLAYING) return;
            MediaPlayer m = mp;
            if (m == null) return;
            int p = -1;
            try { p = m.getCurrentPosition(); } catch (Throwable t) { Log.d(TAG, "getCurrentPosition: " + t); }
            // 位置没推进（或回退）即算卡住。上一版拆成"是否 isPlaying"两个分支，
            // 条件其实等价，留着只会让人误以为两种情况处理不同。
            stallCount = (p >= 0 && lastPos >= 0 && p <= lastPos) ? stallCount + 1 : 0;
            lastPos = p;
            if (stallCount >= 2) {
                Log.w(TAG, "位置连续不推进（" + stallCount + " 次），判卡死");
                // 安稳播过一段才卡 → 偶发卡顿，计数清零，允许继续重连；
                // 连上就卡 → 保持累加，最终收敛到 ERROR（否则 retry 永不达上限 = 无限重连）
                if (playStartedAt > 0 && System.currentTimeMillis() - playStartedAt >= STABLE_MS) {
                    retry = 0;
                }
                scheduleRetry(app.getString(R.string.retry_stalled));
                return;
            }
            h.postDelayed(this, STALL_TICK);
        }
    };

    private String describeError(int extra) {
        switch (extra) {
            case -1004: return app.getString(R.string.err_net);
            case -1007: return app.getString(R.string.err_format);
            case -1010: return app.getString(R.string.err_codec);
            case -110:  return app.getString(R.string.err_timeout);
            case -1:    return app.getString(R.string.err_refused);
            default:    return app.getString(R.string.err_code, Integer.valueOf(extra));
        }
    }

    private void setState(int s, String text) {
        state = s;
        if (listener != null) listener.onState(s, text);
    }

    private void requestFocus() {
        try {
            int r = am.requestAudioFocus(focusCb, AudioManager.STREAM_MUSIC,
                                         AudioManager.AUDIOFOCUS_GAIN);
            // 拿到焦点才出声；被拒时 MediaPlayer 仍会起播但没有音频路由，
            // 表现为"进度在走却没声音"。留一条日志让这种静默失败可诊断。
            if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "音频焦点申请被拒 code=" + r + "（可能被其它 App 独占）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestAudioFocus 异常: " + t);
        }
    }

    private void abandonFocus() {
        try { am.abandonAudioFocus(focusCb); } catch (Throwable t) { Log.d(TAG, "abandonAudioFocus: " + t); }
    }
}
