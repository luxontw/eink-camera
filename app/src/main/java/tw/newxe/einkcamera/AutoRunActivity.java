package tw.newxe.einkcamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Run settings, reached from the more-features sidebar. Lets the user opt
 * into two automatic reactions to scanned QR content (evaluated in MainActivity's
 * {@code maybeAutoRun}):
 *   1. Auto-save matching content as a QR image (keyword substring match).
 *   2. Auto-open matching links (host whitelist).
 *
 * Each rule's entries are typed one-per-row. When the rule is off there is no
 * input at all; turning it on reveals a single grey-underlined text row with a
 * "+" beneath it that appends another row (only once the current last row has
 * text). Values are stored as a newline-joined string in the same
 * SharedPreferences store MainActivity uses ({@link MainActivity#PREFS_NAME}),
 * and MainActivity splits them back one-entry-per-line.
 *
 * The screen reuses the About screen's full-screen white shell (see
 * activity_auto_run.xml / {@link AboutActivity}).
 */
public class AutoRunActivity extends AppCompatActivity {

    private RuleList mKeywords;
    private RuleList mWhitelist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Match MainActivity's / AboutActivity's immersive full-screen look.
        EdgeToEdge.enable(this);
        var flags = WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        getWindow().setFlags(flags, flags);
        setContentView(R.layout.activity_auto_run);

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);

        mKeywords = new RuleList(
                findViewById(R.id.switch_auto_save),
                findViewById(R.id.section_keywords),
                findViewById(R.id.rows_keywords),
                findViewById(R.id.btn_add_keyword),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        mWhitelist = new RuleList(
                findViewById(R.id.switch_auto_open),
                findViewById(R.id.section_whitelist),
                findViewById(R.id.rows_whitelist),
                findViewById(R.id.btn_add_whitelist),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        // Restore stored values + toggle state. Never show ON with no entries —
        // guards against any stale "on + empty" value left by an older build.
        mKeywords.restore(prefs.getBoolean(MainActivity.PREF_AUTO_SAVE_ENABLED, false),
                prefs.getString(MainActivity.PREF_AUTO_SAVE_KEYWORDS, ""));
        mWhitelist.restore(prefs.getBoolean(MainActivity.PREF_AUTO_OPEN_ENABLED, false),
                prefs.getString(MainActivity.PREF_AUTO_OPEN_WHITELIST, ""));

        ImageView back = findViewById(R.id.btn_auto_run_back);
        back.setOnClickListener(v -> finish());
    }

    private void showKeyboard(View target) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard(View from) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(from.getWindowToken(), 0);
        }
    }

    // Tapping anywhere outside the focused input row pulls focus out of it (blank
    // space, the title, the description, a switch, or the "+" button) instead of
    // letting it cling to the EditText. Clearing focus fires the row's
    // onFocusChangeListener — which removes a now-empty row — and is what makes
    // "tap elsewhere" behave like "leave the field". The event is still dispatched
    // normally afterwards, so whatever was tapped (a switch, the "+") still acts.
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                Rect bounds = new Rect();
                focused.getGlobalVisibleRect(bounds);
                int x = (int) ev.getRawX(), y = (int) ev.getRawY();
                if (!bounds.contains(x, y)) {
                    RuleList owner = ownerOf(focused);
                    // Tapping this rule's own "+" while the focused row is empty is a
                    // no-op: keep focus on the row so we don't trigger the focus-loss
                    // auto-off → which (with the row re-focusing) caused a
                    // close/reopen flicker. There's nothing to add from an empty row.
                    if (owner != null && owner.addButtonContains(x, y)
                            && focused instanceof EditText
                            && ((EditText) focused).getText().toString().trim().isEmpty()) {
                        return super.dispatchTouchEvent(ev);
                    }
                    // If the tap is on this rule's own switch, the switch's click
                    // (on ACTION_UP) will toggle it. Don't let the focus-loss auto-off
                    // pre-flip an empty rule OFF here — the subsequent toggle would
                    // just turn it back ON, leaving the user unable to switch it off.
                    if (owner != null && owner.switchContains(x, y)) {
                        owner.skipAutoOffOnce = true;
                    }
                    focused.clearFocus();
                    hideKeyboard(focused);
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private RuleList ownerOf(View field) {
        for (RuleList r : new RuleList[]{mKeywords, mWhitelist}) {
            if (field.getParent() == r.rows) return r;
        }
        return null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Persist on the way out (back press, close button, home, or navigating to
        // the camera). onPause fires for every exit path — including the close
        // button, a system back, and being swiped away from Recents — so the
        // "on requires entries" invariant is enforced here rather than relying on a
        // focus listener that those paths can bypass. A rule is only persisted ON
        // when it has at least one non-blank entry; otherwise it's forced OFF, and
        // that correction is reflected back onto the UI for a resumed screen.
        String keywords = mKeywords.join();
        String whitelist = mWhitelist.join();
        boolean saveOn = mKeywords.switchView.isChecked() && !keywords.isEmpty();
        boolean openOn = mWhitelist.switchView.isChecked() && !whitelist.isEmpty();
        if (mKeywords.switchView.isChecked() != saveOn) mKeywords.switchView.setChecked(saveOn);
        if (mWhitelist.switchView.isChecked() != openOn) mWhitelist.switchView.setChecked(openOn);

        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(MainActivity.PREF_AUTO_SAVE_ENABLED, saveOn)
                .putBoolean(MainActivity.PREF_AUTO_OPEN_ENABLED, openOn)
                .putString(MainActivity.PREF_AUTO_SAVE_KEYWORDS, keywords)
                .putString(MainActivity.PREF_AUTO_OPEN_WHITELIST, whitelist)
                .apply();
    }

    /**
     * One rule's input list: a switch, a hidden-until-on section, a vertical row
     * container of single-line underlined EditTexts, and a "+" add button.
     */
    private class RuleList {
        final SwitchCompat switchView;
        final View section;
        final LinearLayout rows;
        final ImageView addButton;
        final int inputType;
        // Set for one focus-loss when the loss is caused by tapping this rule's own
        // switch, so the switch's toggle (not the focus listener) owns turning it off.
        boolean skipAutoOffOnce = false;

        RuleList(SwitchCompat switchView, View section, LinearLayout rows, ImageView addButton,
                 int inputType) {
            this.switchView = switchView;
            this.section = section;
            this.rows = rows;
            this.addButton = addButton;
            this.inputType = inputType;

            // "+" appends a row, but only once the current last row has text — an
            // empty trailing row would be wasted. If it's still empty, just focus it.
            addButton.setOnClickListener(v -> {
                // No-op while the last row is still empty. Re-focusing the empty row
                // here would fight the focus-loss auto-off (tapping "+" pulls focus
                // off the row → it's empty → rule wants to close), producing a
                // close/reopen flicker. Nothing to add until there's content.
                EditText last = lastRow();
                if (last == null || last.getText().toString().trim().isEmpty()) {
                    return;
                }
                EditText added = addRow("");
                added.requestFocus();
                showKeyboard(added);
            });
        }

        void restore(boolean enabledPref, String stored) {
            List<String> entries = splitLines(stored);
            for (String e : entries) addRow(e);
            // Turning on requires entries; an empty stored value can't keep it on.
            boolean on = enabledPref && !entries.isEmpty();
            switchView.setChecked(on);
            // Install the toggle listener AFTER the initial setChecked so re-opening
            // a screen that's already on doesn't pop the keyboard.
            switchView.setOnCheckedChangeListener((b, checked) -> apply(checked));
            apply(on);
        }

        // Show/hide the section with the switch. When turned on, make sure there's
        // a row to type into. Only auto-focus that row (and pop the keyboard) in the
        // one case where the rule is being enabled with nothing configured yet — a
        // fresh empty rule has nowhere else to go, so guide the user into the input.
        // Every other path that lands here ON — re-opening a screen whose rule is
        // already configured, or re-enabling a rule that still has entries — must NOT
        // grab focus or raise the keyboard.
        private void apply(boolean on) {
            section.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                boolean noEntries = join().isEmpty();
                EditText first = rows.getChildCount() == 0 ? addRow("") : (EditText) rows.getChildAt(0);
                if (noEntries) {
                    first.requestFocus();
                    first.post(() -> showKeyboard(first));
                }
                // Give the "+" the height of one input row so it stays vertically
                // centred at the same position, but keep its width to the glyph only
                // so its left edge aligns with the input's start. Done after layout,
                // when the row's measured height is known.
                first.post(this::sizeAddButtonToRow);
            }
        }

        private void sizeAddButtonToRow() {
            int rowH = rows.getChildCount() == 0 ? 0 : rows.getChildAt(0).getHeight();
            if (rowH <= 0) return;
            ViewGroup.LayoutParams lp = addButton.getLayoutParams();
            // Width stays the glyph size (from XML, 16dp); only the height tracks the
            // row so the "+" is vertically centred but left-aligned to the input.
            if (lp.height != rowH) {
                lp.height = rowH;
                addButton.setLayoutParams(lp);
            }
            installAddButtonTouchDelegate();
        }

        // Expand the "+" tappable area well beyond the small glyph, without changing
        // its visual size/position, via a TouchDelegate on the parent section.
        private void installAddButtonTouchDelegate() {
            View parent = (View) addButton.getParent();
            parent.post(() -> {
                Rect hit = new Rect();
                addButton.getHitRect(hit);
                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                hit.inset(-pad, -pad);
                parent.setTouchDelegate(new android.view.TouchDelegate(hit, addButton));
            });
        }

        private EditText addRow(String text) {
            // Themed context so the blinking text cursor renders black, not the
            // AppCompat teal-green default (colorControlActivated / colorAccent).
            EditText et = new EditText(new androidx.appcompat.view.ContextThemeWrapper(
                    AutoRunActivity.this, R.style.BlackCursorInput));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
            et.setLayoutParams(lp);
            et.setBackgroundResource(R.drawable.edit_underline);
            // Pin a single underline colour. AppCompat otherwise tints the EditText
            // background by focus state, so the focused row's line would look a
            // different shade from the rest — force them all to the same black.
            et.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF000000));
            et.setTextColor(0xFF000000);
            et.setTextSize(14);
            et.setMaxLines(1);
            et.setSingleLine(true);
            et.setGravity(Gravity.CENTER_VERTICAL);
            et.setInputType(inputType);
            et.setText(text);
            // Leaving a row reconciles the rule. An emptied row is removed (but one
            // row always stays so there's somewhere to type); then if the rule has
            // no non-blank entries at all, the switch is turned off — an empty
            // enabled rule does nothing, so it shouldn't stay on. Done on focus loss
            // rather than per-keystroke so a mid-edit clear isn't disruptive. (onPause
            // is still the catch-all for exit paths that skip the focus change.)
            et.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                if (et.getText().toString().trim().isEmpty() && rows.getChildCount() > 1) {
                    rows.removeView(et);
                }
                // When the focus loss was caused by tapping this rule's own switch,
                // let that switch's toggle turn it off — auto-offing here would race
                // the toggle and flip it back on.
                if (skipAutoOffOnce) {
                    skipAutoOffOnce = false;
                    return;
                }
                if (switchView.isChecked() && join().isEmpty()) {
                    switchView.setChecked(false);
                }
            });
            rows.addView(et);
            return et;
        }

        private EditText lastRow() {
            int n = rows.getChildCount();
            return n == 0 ? null : (EditText) rows.getChildAt(n - 1);
        }

        boolean switchContains(int rawX, int rawY) {
            Rect r = new Rect();
            switchView.getGlobalVisibleRect(r);
            return r.contains(rawX, rawY);
        }

        boolean addButtonContains(int rawX, int rawY) {
            Rect r = new Rect();
            addButton.getGlobalVisibleRect(r);
            // Match the enlarged TouchDelegate area (installAddButtonTouchDelegate).
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            r.inset(-pad, -pad);
            return r.contains(rawX, rawY);
        }

        // Trimmed, non-blank entries joined one-per-line for storage / matching.
        String join() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rows.getChildCount(); i++) {
                String t = ((EditText) rows.getChildAt(i)).getText().toString().trim();
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
            return sb.toString();
        }
    }

    private static List<String> splitLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
