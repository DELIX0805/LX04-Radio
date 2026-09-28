package com.tongsir.radio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 自绘图标按钮。不用字体/图片资源 —— 定制 ROM 上字符图标会变方块，这个不会。
 */
public class IconView extends View {

    public static final int NONE = 0;
    public static final int PLAY = 1;
    public static final int PAUSE = 2;
    public static final int PREV = 3;
    public static final int NEXT = 4;
    public static final int VOLUME = 5;
    public static final int HEART = 6;
    public static final int HEART_ON = 7;
    /** 分组展开/收起箭头。默认画成朝下的三角，朝右靠外部 rotation(-90) 旋转，避免字符图标缺字。 */
    public static final int CHEVRON = 8;
    /** 电台列表：三条"圆点+横条"，一眼看出是清单。底栏辅助入口用。 */
    public static final int LIST = 9;

    private int type = NONE;
    private int iconColor = 0xFFD7E0EA;
    private int bgColor = 0;
    private int ringColor = 0;
    private boolean pressed = false;
    /** 按下进度 0~1：由 setPressed 触发，120ms 过渡，描边/缩放都跟着它走 */
    private float pressT = 0f;
    private static final int PRESS_MS = 120;
    private android.animation.ValueAnimator pressAnim;

    /**
     * 底栏图标视觉归一化 —— 解决的问题：六个图标形状完全不同，用同一个 s 画出来，
     * 视觉外接框能差将近一倍。实测（改造前，控件 34/42/50/42/34/40dp）：
     *   播放三角纵向满 ±s（2.00s）→ 45px 高；心形贝塞尔极值只有 1.575s → 24px 高。
     * 于是"播放比收藏大快一倍"，并排放着就是没对齐。
     * <p>
     * 这里按形状反推 s，把所有图标的<b>视觉外接高</b>收敛到同一个值，
     * 于是布局层可以真正把六个控件写成同一个尺寸规格。
     * 返回 0 表示不参与归一（CHEVRON 在电台列表的分组头上，不在底栏，保持原样）。
     */
    private static float shapeHeight(int t) {
        switch (t) {
            case PLAY:
            case PAUSE:
            case PREV:
            case NEXT:     return 2.000f;   // 纵向满 ±s
            case VOLUME:   return 1.809f;   // 弧顶 ±1.14s'·sin58° = ±0.967s'，加 0.17s' 描边外扩
                                            // (s' = 0.86s) → 1.663s + 0.146s
            case HEART:    return 1.835f;   // 填充 1.575s 之外再加一圈 0.26s 描边
            case HEART_ON: return 1.575f;   // 贝塞尔极值：顶 -0.619k / 底 +0.80k，k = 1.11s
            case LIST:     return 1.900f;   // 三条 ±0.95s
            default:       return 0f;       // 不参与归一
        }
    }

    /** 目标视觉外接高 / 控件边长。40dp(60px) 控件上约 34px，
     *  即图标实高约为底盘(58px)的 59%，与改造前播放键 45/73 的比例同量级。 */
    private static final float FIT_H = 34f / 60f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arc = new RectF();

    public IconView(Context c) { super(c); }

    public IconView(Context c, AttributeSet a) { super(c, a); }

    public void setType(int t) { if (type != t) { type = t; invalidate(); } }

    public int getType() { return type; }

    public void setIconColor(int c) { iconColor = c; invalidate(); }

    public void setBgColor(int c) { bgColor = c; invalidate(); }

    public void setRingColor(int c) { ringColor = c; invalidate(); }

    @Override
    public void setPressed(boolean v) {
        if (pressed != v) { pressed = v; animatePress(v); }
        super.setPressed(v);
    }

    /** 按下/松开都走 120ms 过渡，而不是瞬间跳变（与分组箭头 150ms 同一节奏体系） */
    private void animatePress(boolean to) {
        if (pressAnim != null) { pressAnim.cancel(); pressAnim = null; }
        float target = to ? 1f : 0f;
        if (pressT == target) { invalidate(); return; }
        pressAnim = android.animation.ValueAnimator.ofFloat(pressT, target);
        pressAnim.setDuration(PRESS_MS);
        pressAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pressAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                pressT = ((Float) a.getAnimatedValue()).floatValue();
                invalidate();
            }
        });
        pressAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pressAnim != null) { pressAnim.cancel(); pressAnim = null; }
    }

    /** 鼠标/触摸笔悬浮。自绘 View 不会自动重绘，这里补一次 invalidate。 */
    @Override
    public void setHovered(boolean v) {
        super.setHovered(v);
        invalidate();
    }

    /** DPAD / 键盘导航聚焦，同样要自己重绘才能看到聚焦环。 */
    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        invalidate();
    }

    /** 聚焦/悬停也给一圈亮环，与按下共用同一套视觉权重 */
    private float hotAlpha() {
        return Math.max(pressT, (isFocused() || isHovered()) ? 1f : 0f);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        float rad = Math.min(w, h) / 2f - 1f;

        float hot = hotAlpha();

        int cnt = cv.save();
        // 按下时缩到 0.93：pressT 是 120ms 渐变进度，所以是"压下去"的过程而不是瞬间跳
        if (pressT > 0f) {
            float k = 1f - 0.07f * pressT;
            cv.scale(k, k, cx, cy);
        }

        if (bgColor != 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(bgColor);
            cv.drawCircle(cx, cy, rad, p);
        }
        // 聚焦/悬停：画一圈半透明白环。白色在深色底和橙色播放键上都看得见；
        // 外部若显式 setRingColor 过，仍以外部为准。
        int ring = ringColor != 0 ? ringColor
                : (hot > 0f ? ((((int) (0xB3 * hot)) << 24) | 0x00FFFFFF) : 0);
        if (ring != 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, rad * 0.07f));
            p.setColor(ring);
            // 环向内缩 rad*0.09 而不是压在盘沿上：底栏六个键的底盘直径是统一的，
            // 环若骑在盘边上会把那一圈像素染亮，实测暗阈值下音量盘只有 53px（其它 58px），
            // 视觉上就是"这个键的盘小一圈"。内缩后盘的外轮廓与其它五键完全一致，
            // 环仍清楚地表达音量状态（有声橙 C_ACCENT / 静音灰 0xFF6E8090）。
            cv.drawCircle(cx, cy, rad - p.getStrokeWidth() / 2f - rad * 0.09f, p);
        }

        // 图标半尺寸 s。参与归一的形状按"目标视觉高 / 该形状的纵向系数"反推，
        // 使六个图标的视觉外接高一致；CHEVRON 等不参与归一的仍用原基准 0.30。
        float side = Math.min(w, h);
        float sh = shapeHeight(type);
        float s = sh > 0f ? (side * FIT_H / sh) : (side * 0.30f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();

        switch (type) {
            case PLAY:
                p.setStyle(Paint.Style.FILL);
                p.setColor(iconColor);
                path.moveTo(cx - s * 0.58f, cy - s);
                path.lineTo(cx + s * 0.92f, cy);
                path.lineTo(cx - s * 0.58f, cy + s);
                path.close();
                cv.drawPath(path, p);
                break;

            case PAUSE:
                p.setStyle(Paint.Style.FILL);
                p.setColor(iconColor);
                cv.drawRoundRect(cx - s * 0.78f, cy - s, cx - s * 0.22f, cy + s, s * 0.16f, s * 0.16f, p);
                cv.drawRoundRect(cx + s * 0.22f, cy - s, cx + s * 0.78f, cy + s, s * 0.16f, s * 0.16f, p);
                break;

            case PREV:
                p.setStyle(Paint.Style.FILL);
                p.setColor(iconColor);
                cv.drawRoundRect(cx - s * 0.95f, cy - s * 0.86f, cx - s * 0.63f, cy + s * 0.86f,
                        s * 0.14f, s * 0.14f, p);
                path.moveTo(cx + s * 0.84f, cy - s);
                path.lineTo(cx - s * 0.36f, cy);
                path.lineTo(cx + s * 0.84f, cy + s);
                path.close();
                cv.drawPath(path, p);
                break;

            case NEXT:
                p.setStyle(Paint.Style.FILL);
                p.setColor(iconColor);
                cv.drawRoundRect(cx + s * 0.63f, cy - s * 0.86f, cx + s * 0.95f, cy + s * 0.86f,
                        s * 0.14f, s * 0.14f, p);
                path.moveTo(cx - s * 0.84f, cy - s);
                path.lineTo(cx + s * 0.36f, cy);
                path.lineTo(cx - s * 0.84f, cy + s);
                path.close();
                cv.drawPath(path, p);
                break;

            case VOLUME:
                // 声波弧线最外圈会伸到 cx+1.32s，必须内缩，否则顶到外圈描边上
                s *= 0.86f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(iconColor);
                path.moveTo(cx - s * 1.0f, cy - s * 0.34f);
                path.lineTo(cx - s * 0.52f, cy - s * 0.34f);
                path.lineTo(cx - s * 0.02f, cy - s * 0.92f);
                path.lineTo(cx - s * 0.02f, cy + s * 0.92f);
                path.lineTo(cx - s * 0.52f, cy + s * 0.34f);
                path.lineTo(cx - s * 1.0f, cy + s * 0.34f);
                path.close();
                cv.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, s * 0.17f));
                p.setColor(iconColor);
                arc.set(cx - s * 0.30f, cy - s * 0.68f, cx + s * 0.72f, cy + s * 0.68f);
                cv.drawArc(arc, -58, 116, false, p);
                arc.set(cx - s * 0.30f, cy - s * 1.14f, cx + s * 1.32f, cy + s * 1.14f);
                cv.drawArc(arc, -58, 116, false, p);
                break;

            case HEART:
            case HEART_ON: {
                boolean on = (type == HEART_ON);
                p.setColor(iconColor);
                p.setStrokeWidth(Math.max(2f, s * 0.26f));
                p.setStyle(on ? Paint.Style.FILL : Paint.Style.STROKE);
                // 心形实际宽度只有 1.684k（贝塞尔极值，不是 3.1k），故 k 取 1.11s
                // 才与音量/前后台图标 20~24dp 的视觉重量对齐
                float k = s * 1.11f;
                path.moveTo(cx, cy + k * 0.80f);
                path.cubicTo(cx - k * 1.55f, cy - k * 0.28f,
                        cx - k * 0.60f, cy - k * 1.06f, cx, cy - k * 0.34f);
                path.cubicTo(cx + k * 0.60f, cy - k * 1.06f,
                        cx + k * 1.55f, cy - k * 0.28f, cx, cy + k * 0.80f);
                path.close();
                cv.drawPath(path, p);
                break;
            }

            case CHEVRON:
                p.setStyle(Paint.Style.FILL);
                p.setColor(iconColor);
                path.moveTo(cx - s * 0.95f, cy - s * 0.50f);
                path.lineTo(cx + s * 0.95f, cy - s * 0.50f);
                path.lineTo(cx, cy + s * 0.72f);
                path.close();
                cv.drawPath(path, p);
                break;

            case LIST:
                p.setStyle(Paint.Style.FILL);
                p.setColor(iconColor);
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * s * 0.80f;
                    cv.drawCircle(cx - s * 0.62f, y, s * 0.17f, p);
                    cv.drawRoundRect(cx - s * 0.26f, y - s * 0.15f,
                            cx + s * 0.98f, y + s * 0.15f, s * 0.15f, s * 0.15f, p);
                }
                break;

            default:
                break;
        }
        cv.restoreToCount(cnt);
    }
}
