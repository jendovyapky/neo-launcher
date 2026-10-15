package com.neolauncher.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neolauncher.data.AppEntry;

import java.util.function.Consumer;

/** Menu karty (podrzet kartu a pustit bez pohybu) + dialog prejmenovani. */
public final class AppMenu {
    private AppMenu() {}

    public interface Actions {
        void launch();

        void favorite();

        void rename();

        void pickImage();

        void removeImage();

        void reloadImage();

        void hide();

        void info();

        void uninstall();
    }

    /** @param size zabrane misto ("14,2 GB"), nebo null kdyz neni zname */
    public static View build(Context c, AppEntry app, String label, String size, boolean hasCustomImage,
                             boolean favorite, Actions a) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        // Soustredne rohy: polozky (radius R_TILE) odsazene INSET -> panel R_TILE + INSET.
        root.setBackground(Glass.panel(c, Glass.R_TILE + Glass.INSET));
        int pad = Glass.dpi(c, Glass.INSET);
        root.setPadding(pad, pad, pad, pad);

        TextView title = Glass.text(c, label, 17, Color.WHITE, true);
        title.setPadding(Glass.dpi(c, 14), Glass.dpi(c, 12), Glass.dpi(c, 14), 0);
        title.setSingleLine(true);
        root.addView(title);
        String kind = app.type == AppEntry.TYPE_VR ? "VR hra"
                : app.type == AppEntry.TYPE_PANEL ? "Systémový panel" : "2D aplikace";
        // Typ a zabrane misto, pod tim nazev balicku (drobne).
        TextView sub = Glass.text(c, kind + (size != null ? " · " + size : ""), 13, Palette.text2(), false);
        sub.setPadding(Glass.dpi(c, 14), Glass.dpi(c, 2), Glass.dpi(c, 14), 0);
        sub.setSingleLine(true);
        root.addView(sub);
        TextView pkg = Glass.text(c, app.pkg, 11, Palette.text3(), false);
        pkg.setPadding(Glass.dpi(c, 14), Glass.dpi(c, 2), Glass.dpi(c, 14), Glass.dpi(c, 12));
        pkg.setSingleLine(true);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(pkg);
        // Oddelovac zarovnany s textem polozek, pod nim kousek mista pro zvyrazneni prvni polozky.
        View div = Glass.divider(c);
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) div.getLayoutParams();
        dlp.setMargins(Glass.dpi(c, 14), 0, Glass.dpi(c, 14), Glass.dpi(c, 4));
        root.addView(div, dlp);

        root.addView(Glass.menuItem(c, "Spustit", false, v -> a.launch()));
        root.addView(Glass.menuItem(c, favorite ? "★  Odebrat z oblíbených" : "☆  Přidat do oblíbených",
                false, v -> a.favorite()));
        root.addView(Glass.menuItem(c, "Přejmenovat…", false, v -> a.rename()));
        root.addView(Glass.menuItem(c, "Vlastní obrázek…", false, v -> a.pickImage()));
        if (hasCustomImage) {
            root.addView(Glass.menuItem(c, "Odebrat vlastní obrázek", false, v -> a.removeImage()));
        } else if (!app.isSystemPanel()) {
            root.addView(Glass.menuItem(c, "Stáhnout obrázek znovu", false, v -> a.reloadImage()));
        }
        root.addView(Glass.menuItem(c, "Skrýt z launcheru", false, v -> a.hide()));
        if (!app.isSystemPanel()) {
            root.addView(Glass.menuItem(c, "Informace o aplikaci", false, v -> a.info()));
            root.addView(Glass.menuItem(c, "Odinstalovat…", true, v -> a.uninstall()));
        }
        return root;
    }

    /** @param onSave novy nazev, nebo null = vratit puvodni */
    public static View rename(Context c, String current, String original,
                              Consumer<String> onSave, Runnable onCancel) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.panel(c));
        int pad = Glass.dpi(c, Glass.PAD);
        root.setPadding(pad, pad, pad, pad);
        root.addView(Glass.title(c, "Přejmenovat"));
        TextView hint = Glass.text(c, "Původní název: " + original, 12.5f, Palette.text3(), false);
        hint.setPadding(0, Glass.dpi(c, 4), 0, Glass.dpi(c, 12));
        root.addView(hint);

        final EditText edit = new EditText(c);
        edit.setText(current);
        edit.setSelectAllOnFocus(true);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(0x66FFFFFF);
        edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.COBALT_LIGHT));
        edit.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSave.accept(edit.getText().toString());
                return true;
            }
            return false;
        });
        root.addView(edit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Glass.dpi(c, Glass.SECTION), 0, 0);
        View reset = Glass.button(c, "Původní", false, v -> onSave.accept(null));
        View cancel = Glass.button(c, "Zrušit", false, v -> onCancel.run());
        View save = Glass.button(c, "Uložit", true, v -> onSave.accept(edit.getText().toString()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Glass.dpi(c, Glass.GAP_S);
        buttons.addView(reset, lp);
        buttons.addView(cancel, new LinearLayout.LayoutParams(lp));
        buttons.addView(save, new LinearLayout.LayoutParams(lp));
        root.addView(buttons);

        edit.post(() -> {
            edit.requestFocus();
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        });
        return root;
    }

    /** Intent pro vyber obrazku z uloziste (systemovy vyber souboru). */
    public static Intent pickImageIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        return i;
    }
}
