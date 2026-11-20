package com.vrksignatures.sudhaautobot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Aakriti's part of the app's screen: her card (face, title, a small chat) and her
 * notebook (the roster, what she has learned about the Sudha app, her last report).
 *
 * <p>Built in code like the rest of the screen. Nothing here drives the bot: the only
 * things it can change are the notebook entries a person allows or forgets.</p>
 */
final class AakritiDesk {

    private static final String[] WHATSAPP = {"com.whatsapp", "com.whatsapp.w4b"};
    /** Longest text sent along with a report (a caption, or a message of its own). */
    private static final int MAX_SHARED_TEXT = 3200;

    private final Activity activity;
    private final Aakriti aakriti;

    private ImageView face;
    private TextView bubble;
    private TextView asked;
    private EditText question;
    private View notebookTop;
    private Runnable notebookOpen;
    private View chatCard;

    private TextView rosterLine;
    private TextView sudhaLine;
    private LinearLayout learnedList;
    private TextView reportLine;
    private TextView reportBrief;
    private LinearLayout reportButtons;

    AakritiDesk(Activity activity) {
        this.activity = activity;
        this.aakriti = Aakriti.with(activity);
    }

    private int dp(float value) {
        return MagicUi.dp(activity, value);
    }

    private String text(int id, Object... args) {
        return activity.getString(id, args);
    }

    // ------------------------------------------------------------------ card

    /** Her face, her job title, and the chat. */
    void addCard(LinearLayout root) {
        LinearLayout card = MagicUi.card(activity);
        chatCard = card;

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        face = new ImageView(activity);
        face.setImageResource(R.drawable.aakriti);
        face.setContentDescription(text(R.string.aakriti_name));
        face.setOnClickListener(v -> wave());
        top.addView(face, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout side = new LinearLayout(activity);
        side.setOrientation(LinearLayout.VERTICAL);
        TextView name = MagicUi.text(activity, text(R.string.aakriti_title), 20, MagicUi.TEXT);
        name.setTypeface(MagicUi.MEDIUM);
        side.addView(name);
        side.addView(MagicUi.text(activity, text(R.string.aakriti_role), 12, MagicUi.MUTED),
                MagicUi.wrap(activity, 2));
        // The one joke on the screen, worn like a name badge.
        TextView badge = MagicUi.text(activity, text(R.string.aakriti_badge), 11, MagicUi.WARN);
        badge.setTypeface(MagicUi.MEDIUM);
        badge.setBackground(MagicUi.rounded(activity, MagicUi.WARN_SOFT, 0, 10));
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        side.addView(badge, MagicUi.wrap(activity, 6));
        top.addView(side, MagicUi.share(activity, 1f, 14));
        card.addView(top);

        // What was asked (small), and what she says.
        asked = MagicUi.text(activity, "", 12, MagicUi.FAINT);
        asked.setGravity(Gravity.END);
        asked.setVisibility(View.GONE);
        card.addView(asked, MagicUi.fill(activity, 14));

        bubble = MagicUi.text(activity, "", 14, MagicUi.TEXT);
        bubble.setLineSpacing(0, 1.25f);
        GradientDrawable speech = new GradientDrawable();
        speech.setColor(MagicUi.BRAND_SOFT);
        float round = dp(16);
        speech.setCornerRadii(new float[]{dp(4), dp(4), round, round, round, round, round, round});
        bubble.setBackground(speech);
        bubble.setPadding(dp(14), dp(12), dp(14), dp(12));
        bubble.setTextIsSelectable(true);
        card.addView(bubble, MagicUi.fill(activity, 8));

        // Ready-made questions, on a strip that scrolls sideways.
        HorizontalScrollView strip = new HorizontalScrollView(activity);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        boolean first = true;
        for (String suggestion : AakritiChat.SUGGESTIONS) {
            TextView chip = MagicUi.chip(activity, suggestion, v -> ask(suggestion));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!first) at.setMarginStart(dp(8));
            first = false;
            chips.addView(chip, at);
        }
        strip.addView(chips);
        card.addView(strip, MagicUi.fill(activity, 12));

        LinearLayout ask = new LinearLayout(activity);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        question = new EditText(activity);
        question.setSingleLine(true);
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        question.setImeOptions(EditorInfo.IME_ACTION_SEND);
        question.setHint(R.string.chat_hint);
        question.setTextSize(15);
        question.setTextColor(MagicUi.TEXT);
        question.setHintTextColor(MagicUi.FAINT);
        question.setBackground(MagicUi.rounded(activity, MagicUi.BG, MagicUi.LINE, MagicUi.RADIUS_CONTROL));
        question.setPadding(dp(14), dp(11), dp(14), dp(11));
        question.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND && action != EditorInfo.IME_ACTION_DONE
                    && action != EditorInfo.IME_NULL) {
                return false;
            }
            ask(question.getText().toString());
            return true;
        });
        ask.addView(question, MagicUi.share(activity, 1f, 0));
        TextView send = MagicUi.tonalButton(activity, text(R.string.chat_send),
                v -> ask(question.getText().toString()));
        LinearLayout.LayoutParams sendAt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendAt.setMarginStart(dp(8));
        ask.addView(send, sendAt);
        card.addView(ask, MagicUi.fill(activity, 12));

        root.addView(card, MagicUi.fill(activity, 26));
        bubble.setText(AakritiChat.greeting(facts()));
    }

    /** She leans in and settles: one small movement, then still. */
    void wave() {
        if (face == null) return;
        face.animate().cancel();
        face.setRotation(-8f);
        face.setScaleX(0.92f);
        face.setScaleY(0.92f);
        face.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator(2f)).start();
    }

    private void ask(String words) {
        String asking = words == null ? "" : words.trim();
        if (asking.isEmpty()) return;
        AakritiChat.Reply reply = AakritiChat.reply(asking, facts());
        asked.setText(text(R.string.chat_you) + ":  " + asking);
        asked.setVisibility(View.VISIBLE);
        bubble.setText(reply.text);
        bubble.setAlpha(0.2f);
        bubble.animate().alpha(1f).setDuration(260).start();
        question.setText("");
        wave();
        if (reply.action == AakritiChat.Action.SHARE_REPORT) share(true);
        else if (reply.action == AakritiChat.Action.SHOW_NOTEBOOK) showNotebook();
    }

    /** Everything she may talk about, read fresh. */
    private AakritiChat.Facts facts() {
        AakritiChat.Facts facts = new AakritiChat.Facts();
        try {
            DailyLedger ledger = new DailyLedger(activity);
            facts.placed = ledger.countBucket("SUCCESS");
            facts.skipped = ledger.countBucket("SKIPPED");
            facts.toCheck = ledger.countBucket("ERROR");
            facts.handled = ledger.processedCount();
            int configured = activity.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE)
                    .getInt(MainActivity.KEY_EXPECTED_COUNT, RetailerPolicy.EXPECTED_RETAILER_COUNT);
            facts.expected = aakriti.expectedCount(configured);
            Roster roster = aakriti.roster();
            facts.roster = roster.describe();
            facts.distributor = roster.distributorLabel();
            facts.distributorBefore = roster.distributorBefore();
            for (LearnedUi.Entry entry : LearnedUi.entries()) {
                (entry.live ? facts.learned : facts.waiting).add(describe(entry));
            }
            facts.lastStop = aakriti.lastStopReason();
            facts.lastStopWhen = when(aakriti.lastStopAt());
            facts.reportBrief = aakriti.reportBrief();
            facts.hasReport = Aakriti.latestReport(activity) != null;
            facts.sudhaUpdate = aakriti.sudhaUpdateNote();
            facts.ready = android.provider.Settings.canDrawOverlays(activity);
        } catch (Throwable ignored) {
        }
        return facts;
    }

    private static String describe(LearnedUi.Entry entry) {
        UiVocabulary.Slot slot = UiVocabulary.slot(entry.slot);
        return "\"" + (slot == null ? entry.slot : slot.primary()) + "\" → \"" + entry.shown + "\"";
    }

    private static String when(long at) {
        if (at <= 0) return "";
        return new SimpleDateFormat("d MMM, HH:mm", Locale.US).format(new Date(at));
    }

    // -------------------------------------------------------------- notebook

    /** Fills the notebook section; {@code open} unfolds it. */
    void fillNotebook(LinearLayout card, View heading, Runnable open) {
        notebookTop = heading;
        notebookOpen = open;

        TextView intro = MagicUi.text(activity, text(R.string.notebook_intro), 14, MagicUi.MUTED);
        intro.setLineSpacing(0, 1.25f);
        card.addView(intro);

        card.addView(label(R.string.notebook_roster), MagicUi.wrap(activity, 16));
        rosterLine = line();
        card.addView(rosterLine, MagicUi.fill(activity, 2));

        card.addView(label(R.string.notebook_sudha), MagicUi.wrap(activity, 14));
        sudhaLine = line();
        card.addView(sudhaLine, MagicUi.fill(activity, 2));

        card.addView(label(R.string.notebook_learned), MagicUi.wrap(activity, 14));
        learnedList = new LinearLayout(activity);
        learnedList.setOrientation(LinearLayout.VERTICAL);
        card.addView(learnedList, MagicUi.fill(activity, 2));

        card.addView(label(R.string.notebook_last_report), MagicUi.wrap(activity, 16));
        reportLine = line();
        card.addView(reportLine, MagicUi.fill(activity, 2));
        reportBrief = MagicUi.text(activity, "", 12, MagicUi.MUTED);
        reportBrief.setLineSpacing(0, 1.15f);
        reportBrief.setMaxLines(9);
        reportBrief.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(reportBrief, MagicUi.fill(activity, 4));

        reportButtons = new LinearLayout(activity);
        reportButtons.setOrientation(LinearLayout.HORIZONTAL);
        reportButtons.setBaselineAligned(false);
        reportButtons.addView(MagicUi.quietButton(activity, text(R.string.notebook_view_report),
                MagicUi.TEXT, v -> readReport()), MagicUi.share(activity, 0.8f, 0));
        reportButtons.addView(MagicUi.quietButton(activity, text(R.string.notebook_share_whatsapp),
                MagicUi.GOOD, v -> share(true)), MagicUi.share(activity, 1.4f, 8));
        reportButtons.addView(MagicUi.quietButton(activity, text(R.string.notebook_share_other),
                MagicUi.TEXT, v -> share(false)), MagicUi.share(activity, 0.8f, 8));
        card.addView(reportButtons, MagicUi.fill(activity, 8));

        card.addView(MagicUi.quietButton(activity, text(R.string.notebook_forget_all), MagicUi.BAD,
                v -> confirmForgetAll()), MagicUi.fill(activity, 18));
        refresh();
    }

    private TextView label(int id) {
        TextView view = MagicUi.text(activity, text(id), 12, MagicUi.MUTED);
        view.setTypeface(MagicUi.MEDIUM);
        view.setLetterSpacing(0.04f);
        return view;
    }

    private TextView line() {
        TextView view = MagicUi.text(activity, "", 14, MagicUi.TEXT);
        view.setLineSpacing(0, 1.2f);
        return view;
    }

    /** Re-reads the roster, the notebook and the last report. */
    void refresh() {
        if (rosterLine == null) return;
        try {
            Roster roster = aakriti.roster();
            StringBuilder who = new StringBuilder(roster.describe());
            if (!roster.distributorBefore().isEmpty() && !roster.distributorLabel().isEmpty()) {
                who.append('\n').append(text(R.string.notebook_distributor_changed,
                        roster.distributorBefore(), roster.distributorLabel()));
            }
            rosterLine.setText(who);

            String version = aakriti.sudhaVersion();
            StringBuilder sudha = new StringBuilder(text(R.string.notebook_sudha_version,
                    version.isEmpty() ? text(R.string.notebook_sudha_unknown) : version));
            String update = aakriti.sudhaUpdateNote();
            if (!update.isEmpty()) sudha.append('\n').append(text(R.string.notebook_sudha_updated, update));
            sudhaLine.setText(sudha);
            sudhaLine.setTextColor(update.isEmpty() ? MagicUi.TEXT : MagicUi.WARN);

            learnedList.removeAllViews();
            List<LearnedUi.Entry> entries = new ArrayList<>(LearnedUi.entries());
            if (entries.isEmpty()) {
                TextView none = line();
                none.setText(R.string.notebook_empty);
                learnedList.addView(none);
            }
            for (LearnedUi.Entry entry : entries) learnedList.addView(entryView(entry), MagicUi.fill(activity, 8));

            File report = Aakriti.latestReport(activity);
            if (report == null) {
                reportLine.setText(R.string.notebook_no_report);
                reportLine.setTextColor(MagicUi.MUTED);
                reportBrief.setVisibility(View.GONE);
                reportButtons.setVisibility(View.GONE);
            } else {
                reportLine.setText(text(R.string.notebook_report_when, when(aakriti.reportAt()),
                        aakriti.reportHeadline()));
                reportLine.setTextColor(MagicUi.WARN);
                reportBrief.setText(aakriti.reportBrief());
                reportBrief.setVisibility(View.VISIBLE);
                reportButtons.setVisibility(View.VISIBLE);
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Notebook could not be shown", e);
        }
    }

    private View entryView(LearnedUi.Entry entry) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(MagicUi.rounded(activity,
                entry.live ? MagicUi.GOOD_SOFT : MagicUi.WARN_SOFT, 0, MagicUi.RADIUS_CONTROL));
        block.setPadding(dp(12), dp(10), dp(12), dp(10));

        UiVocabulary.Slot slot = UiVocabulary.slot(entry.slot);
        String known = slot == null ? entry.slot : slot.primary();
        TextView what = MagicUi.text(activity, text(R.string.notebook_entry, known, entry.shown), 14,
                MagicUi.TEXT);
        block.addView(what);
        TextView meta = MagicUi.text(activity, text(R.string.notebook_entry_meta,
                slot == null ? "" : slot.says, entry.when.isEmpty() ? "—" : entry.when,
                entry.appVersion.isEmpty() ? "?" : entry.appVersion), 12,
                MagicUi.MUTED);
        block.addView(meta, MagicUi.wrap(activity, 2));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setBaselineAligned(false);
        if (!entry.live) {
            TextView waiting = MagicUi.text(activity, text(R.string.notebook_entry_waiting), 12,
                    MagicUi.WARN);
            waiting.setTypeface(MagicUi.MEDIUM);
            block.addView(waiting, MagicUi.wrap(activity, 6));
            buttons.addView(MagicUi.tonalButton(activity, text(R.string.notebook_allow),
                    v -> confirmAllow(entry, slot)), MagicUi.share(activity, 1f, 0));
        }
        buttons.addView(MagicUi.quietButton(activity, text(R.string.notebook_forget), MagicUi.MUTED,
                v -> {
                    aakriti.forget(entry.alias);
                    refresh();
                }), MagicUi.share(activity, 1f, entry.live ? 0 : 8));
        block.addView(buttons, MagicUi.fill(activity, 8));
        return block;
    }

    /** Allowing a new name for a button the bot presses is asked twice, in plain words. */
    private void confirmAllow(LearnedUi.Entry entry, UiVocabulary.Slot slot) {
        String known = slot == null ? entry.slot : slot.primary().toUpperCase(Locale.ROOT);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.allow_title)
                .setMessage(text(R.string.allow_message, slot == null ? known : slot.says, entry.shown, known))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.notebook_allow, (dialog, which) -> {
                    aakriti.allow(entry.alias);
                    refresh();
                })
                .show();
    }

    private void confirmForgetAll() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.forget_all_title)
                .setMessage(R.string.forget_all_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget_all_confirm, (dialog, which) -> {
                    aakriti.forgetEverything();
                    refresh();
                    Toast.makeText(activity, R.string.forget_all_done, Toast.LENGTH_LONG).show();
                })
                .show();
    }

    /** Scrolls the setup screen to the notebook. */
    void showNotebook() {
        refresh();
        if (notebookTop == null) return;
        if (notebookOpen != null) notebookOpen.run();
        scrollTo(notebookTop);
    }

    /** Scrolls the setup screen to the chat and puts the cursor in the question box. */
    void showChat() {
        if (chatCard == null) return;
        scrollTo(chatCard);
        if (question != null) question.requestFocus();
        wave();
    }

    private void scrollTo(View target) {
        target.post(() -> {
            View parent = target;
            while (parent != null && !(parent instanceof ScrollView)) {
                parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            }
            if (parent != null) ((ScrollView) parent).smoothScrollTo(0, Math.max(0, target.getTop() - dp(12)));
        });
    }

    // ---------------------------------------------------------------- report

    private void readReport() {
        File report = Aakriti.latestReport(activity);
        if (report == null) {
            Toast.makeText(activity, R.string.share_none, Toast.LENGTH_SHORT).show();
            return;
        }
        TextView body = new TextView(activity);
        body.setText(LearnedUi.read(report));
        body.setTextSize(12);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(18), dp(12), dp(18), dp(12));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.share_subject)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.notebook_share_whatsapp, (dialog, which) -> share(true))
                .show();
    }

    private String installedWhatsApp() {
        for (String name : WHATSAPP) {
            try {
                activity.getPackageManager().getPackageInfo(name, 0);
                return name;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /**
     * Hands the last report to WhatsApp (or to whichever app the person picks): the picture
     * of the screen the bot stopped on, with what went wrong as its caption, and the full
     * report as a text file. The person chooses the chat and presses send; nothing is sent
     * by the app on its own.
     */
    void share(boolean viaWhatsApp) {
        try {
            File report = Aakriti.latestReport(activity);
            if (report == null) {
                Toast.makeText(activity, R.string.share_none, Toast.LENGTH_LONG).show();
                return;
            }
            File picture = Aakriti.pictureOf(report);
            String full = LearnedUi.read(report);
            String caption = text(R.string.share_subject) + "\n" + aakriti.reportHeadline()
                    + "\n\n" + aakriti.reportBrief();
            // Without a picture the report itself is the message.
            String message = picture == null ? full : caption;
            if (message.length() > MAX_SHARED_TEXT) message = message.substring(0, MAX_SHARED_TEXT) + "…";

            String whatsApp = viaWhatsApp ? installedWhatsApp() : null;
            if (viaWhatsApp && whatsApp == null) {
                Toast.makeText(activity, R.string.share_no_whatsapp, Toast.LENGTH_LONG).show();
            }

            Intent send;
            Uri textFile = ReportProvider.uriFor(activity, report);
            if (whatsApp != null) {
                // WhatsApp takes one attachment with a caption most reliably.
                send = new Intent(Intent.ACTION_SEND);
                if (picture != null) {
                    Uri image = ReportProvider.uriFor(activity, picture);
                    send.setType("image/jpeg");
                    send.putExtra(Intent.EXTRA_STREAM, image);
                    send.setClipData(ClipData.newRawUri("report", image));
                } else {
                    send.setType("text/plain");
                }
                send.setPackage(whatsApp);
            } else {
                ArrayList<Uri> files = new ArrayList<>();
                if (picture != null) files.add(ReportProvider.uriFor(activity, picture));
                files.add(textFile);
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.setType(picture == null ? "text/plain" : "*/*");
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
                ClipData clip = ClipData.newRawUri("report", files.get(0));
                for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
                send.setClipData(clip);
            }
            send.putExtra(Intent.EXTRA_SUBJECT, text(R.string.share_subject));
            send.putExtra(Intent.EXTRA_TEXT, message);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            try {
                activity.startActivity(whatsApp != null ? send
                        : Intent.createChooser(send, text(R.string.share_title)));
            } catch (android.content.ActivityNotFoundException e) {
                send.setPackage(null);
                activity.startActivity(Intent.createChooser(send, text(R.string.share_title)));
            }
            AakritiNotifier.clearStuck(activity);
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Report could not be shared", e);
            Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** What a notification asked for when it opened the screen. */
    void open(String what) {
        if (what == null) return;
        switch (what) {
            case AakritiNotifier.OPEN_SHARE_WHATSAPP:
                showNotebook();
                share(true);
                break;
            case AakritiNotifier.OPEN_SHARE:
                showNotebook();
                share(false);
                break;
            case AakritiNotifier.OPEN_NOTEBOOK:
                showNotebook();
                break;
            case AakritiNotifier.OPEN_CHAT:
                showChat();
                break;
            default:
                break;
        }
    }
}
