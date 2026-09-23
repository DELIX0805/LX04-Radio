package com.tongsir.radio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 声波视图：播放时竖条跳动，暂停/停止时压平。
 * 代替不存在的台标 —— 每台都没有 logo 图，摆个默认图更丑。
 */
public class WaveView extends View {

    private static final int BARS = 5;
    private static final long FRAME = 90L;

    private final float[] level = new float[BARS];
    private final float[] target = new float[BARS];
    private final Random rnd = new Random();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    /** 亮条矩形：复用，避免每帧 new（90ms 一帧 × 5 条 = 每秒 55 个临时对象） */
    private final RectF lit = new RectF();

    private int waveColor = 0xFFFF7A45;
    private int baseColor = 0xFF44586C;
    private boolean running = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            for (int i = 0; i < BARS; i++) {
                if (rnd.nextFloat() < 0.45f) target[i] = 0.30f + rnd.nextFloat() * 0.70f;
                level[i] += (target[i] - level[i]) * 0.42f;
            }
            invalidate();
            postDelayed(this, FRAME);
        }
    };

    public WaveView(Context c) { super(c); }

    public WaveView(Context c, AttributeSet a) { super(c, a); }

    public void setWaveColor(int c) { waveColor = c; invalidate(); }

    /** 是否处于"正在出声"状态 */
    public void setRunning(boolean r) {
        if (running == r) return;
        running = r;
        removeCallbacks(tick);
        if (r) {
            post(tick);
        } else {
            for (int i = 0; i < BARS; i++) { target[i] = 0.18f; level[i] = 0.18f; }
            invalidate();
        }
    }

    public boolean isRunning() { return running; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // View 未 attach 时 post() 会被丢弃。若在 attach 前就 setRunning(true)，
        // 动画链会永远起不来 —— 这里补一次启动。
        if (running) { removeCallbacks(tick); post(tick); }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 这里只摘掉动画链，**不清 running**：running 表示"是否正在出声"，
        // 是业务状态而不是 attach 状态。清了以后重新 attach 波形会永久压平，
        // 要等下一次 onState 回调才重新动（onAttachedToWindow 负责补启动）。
        removeCallbacks(tick);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = Math.min(w, h) * 0.06f;
        float usable = w - pad * 2f;
        float gap = usable * 0.10f;
        float bw = (usable - gap * (BARS - 1)) / BARS;
        float maxH = h - pad * 2f;
        float cy = h / 2f;
        float r = bw * 0.5f;

        for (int i = 0; i < BARS; i++) {
            float lv = level[i];
            if (lv < 0.06f) lv = 0.06f;
            float bh = maxH * lv;
            float left = pad + i * (bw + gap);
            bar.set(left, cy - bh / 2f, left + bw, cy + bh / 2f + 2f);
            p.setColor(baseColor);
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(bar, r, r, p);

            // 内部亮条（顶部一段）
            float litH = bh * 0.62f;
            lit.set(bar.left, bar.top, bar.right, bar.top + litH);
            p.setColor(waveColor);
            cv.drawRoundRect(lit, r, r, p);
        }
    }
}
