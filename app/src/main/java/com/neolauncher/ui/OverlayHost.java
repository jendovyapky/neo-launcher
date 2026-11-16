package com.neolauncher.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Vrstva nad launcherem pro dialogy (nastaveni, menu aplikace, rychle menu).
 * Klepnuti mimo panel dialog zavre.
 * <p>
 * Prostorova navaznost (spatial continuity jako v iOS/visionOS): dialog
 * "vyroste" na pruzine z mista, odkud byl otevren (logo, karta, hodiny),
 * a pri zavreni se do nej zase stahne. Stav je jen "otevreno/zavreno" a meni
 * se OKAMZITE - zaviraci animace je jen obrazek ("duch"), ktery uz nebere
 * dotyky, a po dobehnuti pruziny se odstrani.
 */
public final class OverlayHost extends FrameLayout {

    public interface Listener {
        void onOverlayShown();

        void onOverlayClosed();
    }

    private View panel;
    private View ghost;
    private Listener listener;
    private final Spring open = new Spring(0, 0.42f, 0.84f, 0.001f);
    private final Spring closing = new Spring(0, 0.30f, 1f, 0.002f);
    private final RectF origin = new RectF();
    private final RectF target = new RectF();
    private final RectF ghostOrigin = new RectF();
    private final RectF ghostTarget = new RectF();
    private final Runnable frame = this::onFrame;
    private boolean framePosted;

    public OverlayHost(Context c) {
        super(c);
        setVisibility(GONE);
        setClickable(true);
        setOnClickListener(v -> close());
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isOpen() {
        return panel != null;
    }

    public View panel() {
        return panel;
    }

    public void show(View content, RectF anchor, RectF area, int maxW) {
        show(content, anchor, null, area, maxW);
    }

    /**
     * @param content obsah dialogu (musi byt clickable, aby klik dovnitr nezaviral)
     * @param anchor  obdelnik, u ktereho se ma dialog objevit, nebo null = na stred
     * @param from    odkud dialog "vyroste" (null = z anchor, jinak ze stredu)
     * @param area    oblast skleneneho panelu, ze ktere dialog nesmi vylezt
     * @param maxW    maximalni sirka dialogu v px
     */
    public void show(View content, RectF anchor, RectF from, RectF area, int maxW) {
        removeGhost();
        final boolean wasOpen = panel != null;
        if (panel != null) removeView(panel);
        panel = content;
        content.setClickable(true);

        final int margin = Glass.dpi(getContext(), 16);
        final int availW = Math.max(1, Math.round(area.width()) - 2 * margin);
        final int availH = Math.max(1, Math.round(area.height()) - 2 * margin);
        final int w = Math.min(maxW, availW);
        content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(availH, MeasureSpec.AT_MOST));
        final int h = Math.min(content.getMeasuredHeight(), availH);

        float left, top;
        if (anchor == null) {
            left = area.centerX() - w / 2f;
            top = area.centerY() - h / 2f;
        } else {
            left = anchor.centerX() - w / 2f;
            top = anchor.bottom + Glass.dp(getContext(), 8);
            if (top + h > area.bottom - margin) top = anchor.top - h - Glass.dp(getContext(), 8);
        }
        left = Math.max(area.left + margin, Math.min(left, area.right - margin - w));
        top = Math.max(area.top + margin, Math.min(top, area.bottom - margin - h));

        LayoutParams lp = new LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round(left);
        lp.topMargin = Math.round(top);
        addView(content, lp);
        target.set(left, top, left + w, top + h);
        if (from != null) origin.set(from);
        else if (anchor != null) origin.set(anchor);
        else origin.set(target.centerX() - w * 0.1f, target.centerY() - h * 0.1f,
                target.centerX() + w * 0.1f, target.centerY() + h * 0.1f);

        setVisibility(VISIBLE);
        final long now = System.nanoTime();
        open.snap(0f);
        open.set(1f, now);
        apply(now);
        schedule();
        if (!wasOpen && listener != null) listener.onOverlayShown();
    }

    /**
     * Bocni panel (jako detail vpravo ve visionOS): pres celou vysku oblasti
     * u praveho okraje, vyjede zprava na pruzine.
     */
    public void showSide(View content, RectF area, int width) {
        removeGhost();
        final boolean wasOpen = panel != null;
        if (panel != null) removeView(panel);
        panel = content;
        content.setClickable(true);
        final int margin = Glass.dpi(getContext(), 12);
        final int w = Math.min(width, Math.max(1, Math.round(area.width()) - 2 * margin));
        final int h = Math.max(1, Math.round(area.height()) - 2 * margin);
        content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        final float left = area.right - margin - w;
        final float top = area.top + margin;
        LayoutParams lp = new LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round(left);
        lp.topMargin = Math.round(top);
        addView(content, lp);
        target.set(left, top, left + w, top + h);
        origin.set(target);
        origin.offset(Glass.dp(getContext(), 90), 0);

        setVisibility(VISIBLE);
        final long now = System.nanoTime();
        open.snap(0f);
        open.set(1f, now);
        apply(now);
        schedule();
        if (!wasOpen && listener != null) listener.onOverlayShown();
    }

    public void close() {
        if (panel == null) return;
        final long now = System.nanoTime();
        removeGhost();
        ghost = panel;
        panel = null;
        ghostOrigin.set(origin);
        ghostTarget.set(target);
        closing.snap(Math.max(0f, Math.min(1f, open.get(now))));
        closing.set(0f, now);
        apply(now);
        schedule();
        if (listener != null) listener.onOverlayClosed();
    }

    // --- Animace (jen vizual, stav uz je nastaveny) ---------------------------------

    private void schedule() {
        if (framePosted) return;
        framePosted = true;
        postOnAnimation(frame);
    }

    private void onFrame() {
        framePosted = false;
        final long now = System.nanoTime();
        apply(now);
        if (ghost != null && !closing.active(now)) removeGhost();
        if (open.active(now) || closing.active(now)) schedule();
    }

    private void apply(long now) {
        float scrim = 0f;
        if (ghost != null) {
            final float q = closing.get(now);
            transform(ghost, q, ghostOrigin, ghostTarget);
            scrim = q;
        }
        if (panel != null) {
            final float p = open.get(now);
            transform(panel, p, origin, target);
            scrim = Math.max(scrim, p);
        }
        scrim = Math.max(0f, Math.min(1f, scrim));
        setBackgroundColor(Color.argb(Math.round(0x1F * scrim), 0, 0, 0));
    }

    /** p = 0: velikost a poloha puvodu (from), p = 1: cilove misto. Pruzina muze lehce prekmitnout. */
    private static void transform(View v, float p, RectF from, RectF to) {
        final float s0 = Math.max(0.12f, Math.min(0.95f,
                Math.max(from.width() / Math.max(1f, to.width()), from.height() / Math.max(1f, to.height()))));
        final float s = s0 + (1f - s0) * p;
        v.setPivotX(to.width() / 2f);
        v.setPivotY(to.height() / 2f);
        v.setScaleX(s);
        v.setScaleY(s);
        v.setTranslationX((from.centerX() - to.centerX()) * (1f - p));
        v.setTranslationY((from.centerY() - to.centerY()) * (1f - p));
        v.setAlpha(Math.max(0f, Math.min(1f, p * 1.8f)));
    }

    private void removeGhost() {
        if (ghost == null) return;
        removeView(ghost);
        ghost = null;
        if (panel == null) {
            setVisibility(GONE);
            setBackgroundColor(0);
        }
    }

    // Zavirajici se "duch" nebere zadne udalosti - propadnou do launcheru pod nim.
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return panel != null && super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        return panel != null && super.dispatchGenericMotionEvent(ev);
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent ev) {
        return panel != null && super.dispatchHoverEvent(ev);
    }
}
