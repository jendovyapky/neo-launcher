package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Ovladaci prvky ve stylu skla z visionOS: prepinac, posuvnik a ikona
 * v kolecku. Vsechno kreslene, pohyb na pruzinach ({@link Spring}).
 */
public final class GlassWidgets {
    private GlassWidgets() {}

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int mix(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Prepinac: vypnuto = pruhledna stopa a bila tecka vlevo, zapnuto = bila stopa a tmava tecka vpravo. */
    public static final class Toggle extends View {
        private final float d;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Spring knob = new Spring(0, 0.34f, 0.72f, 0.002f);
        private final Spring hover = new Spring(0, 0.25f, 1f, 0.002f);
        private boolean checked;
        private Consumer<Boolean> listener;

        public Toggle(Context c, boolean on, Consumer<Boolean> onChange) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            checked = on;
            knob.snap(on ? 1f : 0f);
            listener = onChange;
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> toggle());
        }

        public void toggle() {
            setChecked(!checked, true);
        }

        public boolean isChecked() {
            return checked;
        }

        public void setChecked(boolean on, boolean notify) {
            if (on == checked) return;
            checked = on;
            knob.set(on ? 1f : 0f, System.nanoTime());
            if (notify && listener != null) listener.accept(on);
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(52 * d), Math.round(32 * d));
        }

        @Override
        public boolean onHoverEvent(MotionEvent e) {
            final int a = e.getActionMasked();
            hover.set(a == MotionEvent.ACTION_HOVER_EXIT ? 0f : 1f, System.nanoTime());
            invalidate();
            return super.onHoverEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            final long now = System.nanoTime();
            final float k = knob.get(now);
            final float kc = clamp(k, 0f, 1f);
            final float hv = clamp(hover.get(now), 0f, 1f);
            final float w = getWidth(), h = getHeight(), r = h / 2f;
            paint.setStyle(Paint.Style.FILL);
            // Vypnuto = pruhledna kapsle, zapnuto = perla; hover jen zesvetli (bez zare).
            paint.setColor(mix(0x26FFFFFF, Palette.PEARL, kc));
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            if (hv > 0.01f) {
                paint.setColor(Color.argb(Math.round(0x33 * hv), 255, 255, 255));
                c.drawRoundRect(0, 0, w, h, r, r, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d);
            paint.setColor(mix(0x4DFFFFFF, 0x00FFFFFF, kc));
            c.drawRoundRect(d / 2, d / 2, w - d / 2, h - d / 2, r, r, paint);
            // Tecka: pruzina lehce prekmitne (pruzny pohyb).
            final float kr = r - 4 * d;
            final float kx = r + (w - 2 * r) * k;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(mix(Palette.FOG, Palette.VOID, kc));
            paint.setShadowLayer(3 * d, 0, d, 0x40000000);
            c.drawCircle(kx, r, kr, paint);
            paint.clearShadowLayer();
            if (knob.active(now) || hover.active(now)) postInvalidateOnAnimation();
        }
    }

    /** Siroky posuvnik jako v rychlem menu (bila vypln, popisek a hodnota uvnitr). */
    public static final class Slider extends View {
        private final float d;
        private final int min, max;
        private final String label, suffix;
        private final IntConsumer listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Spring shown = new Spring(0, 0.22f, 1f, 0.001f);
        private final Spring hover = new Spring(0, 0.25f, 1f, 0.002f);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private int value;
        private boolean dragging;
        private long lastWheelMs;

        public Slider(Context c, String label, int min, int max, int value, String suffix, IntConsumer onChange) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            this.label = label;
            this.min = min;
            this.max = max;
            this.value = value;
            this.suffix = suffix;
            this.listener = onChange;
            shown.snap(frac(value));
            text.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
            text.setTextSize(14 * d);
            text.setFontFeatureSettings("tnum");
            setClickable(true);
        }

        private float frac(int v) {
            return max > min ? (v - min) / (float) (max - min) : 0f;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            final int w = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED
                    ? Math.round(260 * d) : MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(46 * d));
        }

        private void setFromX(float x) {
            final float f = clamp((x - 2 * d) / Math.max(1f, getWidth() - 4 * d), 0f, 1f);
            setValue(Math.round(min + f * (max - min)));
        }

        private void setValue(int v) {
            v = Math.max(min, Math.min(max, v));
            if (v == value) return;
            value = v;
            shown.set(frac(v), System.nanoTime());
            if (listener != null) listener.accept(v);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setFromX(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) setFromX(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) setFromX(e.getX());
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean onHoverEvent(MotionEvent e) {
            hover.set(e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT ? 0f : 1f, System.nanoTime());
            invalidate();
            return true;
        }

        /** Joystick nad posuvnikem = jemne kroky (5 % rozsahu). */
        @Override
        public boolean onGenericMotionEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_SCROLL
                    && (e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                final float hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                final float vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                final float v = Math.abs(hs) >= Math.abs(vs) ? hs : vs;
                final long now = System.currentTimeMillis();
                if (Math.abs(v) >= 0.2f && now - lastWheelMs >= 70) {
                    lastWheelMs = now;
                    setValue(value + Math.round(Math.signum(v) * Math.max(1, (max - min) / 20f)));
                }
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            final long now = System.nanoTime();
            final float hv = clamp(hover.get(now), 0f, 1f);
            final float w = getWidth(), h = getHeight(), r = h / 2f;
            rect.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(0x1F + 0x14 * hv), 255, 255, 255));
            c.drawRoundRect(rect, r, r, paint);
            final float f = clamp(shown.get(now), 0f, 1f);
            final float fw = Math.max(f > 0.004f ? h : 0f, w * f);
            if (fw > 0f) {
                c.save();
                clip.reset();
                clip.addRoundRect(rect, r, r, Path.Direction.CW);
                c.clipPath(clip);
                paint.setColor(Palette.PEARL);
                c.drawRoundRect(0, 0, fw, h, r, r, paint);
                c.restore();
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d);
            paint.setColor(Color.argb(Math.round(0x33 + 0x40 * hv), 255, 255, 255));
            c.drawRoundRect(d / 2, d / 2, w - d / 2, h - d / 2, r, r, paint);
            final Paint.FontMetrics fm = text.getFontMetrics();
            final float base = h / 2f - (fm.ascent + fm.descent) / 2f;
            final float lx = 18 * d;
            text.setColor(fw > lx + text.measureText(label) + 6 * d ? Palette.VOID : Palette.TEXT);
            c.drawText(label, lx, base, text);
            final String v = value + suffix;
            final float vw = text.measureText(v);
            text.setColor(fw > w - 14 * d ? Palette.VOID : Palette.text2());
            c.drawText(v, w - 18 * d - vw, base, text);
            if (shown.active(now) || hover.active(now)) postInvalidateOnAnimation();
        }
    }

    /** Ikona v kulatem skle (jako ikony zarizeni v predlohach od uzivatele). */
    public static final class IconView extends View {
        private final float d;
        private final int icon;
        private final float sizeDp;
        private final int tint;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.Shader fillShader, rimShader;
        private float shaderFor = -1;

        public IconView(Context c, int icon, float sizeDp, int tint) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            this.icon = icon;
            this.sizeDp = sizeDp;
            this.tint = tint;
        }

        @Override
        protected void onMeasure(int w, int h) {
            final int s = Math.round(sizeDp * d);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas c) {
            final float s = getWidth(), r = s / 2f;
            paint.setStyle(Paint.Style.FILL);
            // Kulate sklo: vypln nahore svetlejsi, okraj nahore jasny a dole slabsi.
            if (fillShader == null || shaderFor != s) {
                shaderFor = s;
                fillShader = new android.graphics.LinearGradient(0, 0, 0, s, 0x33FFFFFF, 0x14FFFFFF,
                        android.graphics.Shader.TileMode.CLAMP);
                rimShader = new android.graphics.LinearGradient(0, 0, s * 0.3f, s,
                        new int[]{0x80FFFFFF, 0x1AFFFFFF, 0x33FFFFFF}, new float[]{0f, 0.6f, 1f},
                        android.graphics.Shader.TileMode.CLAMP);
            }
            paint.setShader(fillShader);
            c.drawCircle(r, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d);
            paint.setShader(rimShader);
            c.drawCircle(r, r, r - d / 2, paint);
            paint.setShader(null);
            Icons.draw(c, icon, r, r, s * 0.46f, tint, 1.8f * d, 1f, paint);
        }
    }
}
