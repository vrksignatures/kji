package com.vrksignatures.sudhaautobot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Aakriti says when she is asked something on the setup screen.
 *
 * <p>She is a small scripted assistant, not a language model: she recognises a handful of
 * questions by their key words (in English or Hinglish) and answers each from the facts of
 * the day - the ledger, the roster, her notebook, the last stop. Anything else gets an
 * honest "I can answer these" list. Nothing typed here leaves the phone.</p>
 */
public final class AakritiChat {

    /** What the setup screen should do after showing the answer. */
    public enum Action { NONE, SHARE_REPORT, SHOW_NOTEBOOK }

    public static final class Reply {
        public final String text;
        public final Action action;

        Reply(String text, Action action) {
            this.text = text;
            this.action = action;
        }
    }

    /** Everything Aakriti may speak about. Filled by the setup screen. */
    public static final class Facts {
        public int placed;
        public int skipped;
        public int toCheck;
        public int handled;
        public int expected;
        /** {@link Roster#describe()}. */
        public String roster = "";
        public String distributor = "";
        public String distributorBefore = "";
        /** One line per name in the notebook that is in use. */
        public List<String> learned = new ArrayList<>();
        /** One line per name waiting for a yes. */
        public List<String> waiting = new ArrayList<>();
        public String lastStop = "";
        public String lastStopWhen = "";
        public String reportBrief = "";
        public boolean hasReport;
        /** "3.1 (40) → 3.2 (41)" when the Sudha app was updated, else "". */
        public String sudhaUpdate = "";
        public boolean ready;
        /** The newest lines of the file of skipped retailers, oldest of them first. */
        public List<String> skippedLog = new ArrayList<>();
        /** How many lines that file holds. */
        public int skippedTotal;
    }

    /** Questions offered as buttons under the chat. */
    public static final String[] SUGGESTIONS = {
            "Aaj ka haal?", "Kya seekha?", "Kyon ruki?", "Kitne retailer?", "Reset se kya hoga?",
            "Meri job?",
    };

    private AakritiChat() {
    }

    public static String greeting(Facts facts) {
        StringBuilder out = new StringBuilder("Namaste sir! Main Sutara — aapki AI employee. ");
        if (facts != null && facts.handled > 0) {
            out.append("Aaj ").append(facts.handled).append(" retailer ho chuke hain.");
        } else {
            out.append("Order lagana mera kaam, chai peena aapka.");
        }
        if (facts != null && !facts.sudhaUpdate.isEmpty()) {
            out.append("\nSudha app update hui hai (").append(facts.sudhaUpdate)
                    .append("). Main nazar rakhungi ki kuch badla to nahin.");
        }
        if (facts != null && !facts.waiting.isEmpty()) {
            out.append("\nEk cheez par aapki haan chahiye — neeche \"Sutara's notebook\" dekhiye.");
        }
        return out.toString();
    }

    public static Reply reply(String question, Facts facts) {
        Facts f = facts == null ? new Facts() : facts;
        String q = " " + (question == null ? "" : question.toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9]+", " ").trim() + " ";
        if (q.trim().isEmpty()) return new Reply("Ji? Kuch poochhiye.", Action.NONE);

        if (any(q, "report", "whatsapp", "bhejo", "bhej", "send", "share")) {
            if (!f.hasReport) {
                return new Reply("Abhi koi report nahin hai — matlab sab theek chal raha hai.", Action.NONE);
            }
            return new Reply("Report taiyaar hai, share khol rahi hoon. WhatsApp chuniye aur bhej dijiye.",
                    Action.SHARE_REPORT);
        }
        if (any(q, "skip", "skipped", "skips", "chhoota", "chhoote", "chhoot", "chhut", "chhute", "chhuta",
                "failed", "fail", "log", "baaki", "baki", "empty", "khali")) {
            if (f.skippedLog.isEmpty()) {
                return new Reply("Skip wali file khaali hai — \"Orders list is empty\" ya T-1 se T-5 tak "
                        + "order na milne ki wajah se koi retailer nahin chhoota.", Action.NONE);
            }
            StringBuilder out = new StringBuilder();
            int total = Math.max(f.skippedTotal, f.skippedLog.size());
            out.append("Skip file mein ").append(total).append(total == 1 ? " retailer hai" : " retailer hain");
            if (total > f.skippedLog.size()) out.append(" (aakhri ").append(f.skippedLog.size()).append(")");
            out.append(":\n");
            for (String line : f.skippedLog) out.append("  • ").append(line).append('\n');
            out.append("Poori list app mein: Advanced → Skipped retailers.");
            return new Reply(out.toString(), Action.NONE);
        }
        if (any(q, "reset")) {
            return new Reply("Reset sirf aaj ka session saaf karta hai — aaj ke placed / skipped / "
                    + "error record. Jo maine Sudha app ke baare mein seekha hai (naye label, roster) "
                    + "woh bilkul safe rehta hai. Use bhulane ka alag button hai: \"Forget what "
                    + "Sutara learned\".", Action.NONE);
        }
        if (any(q, "seekha", "sikha", "seekh", "learn", "learned", "notebook", "adapt", "badla", "change", "update")) {
            StringBuilder out = new StringBuilder();
            if (!f.sudhaUpdate.isEmpty()) out.append("Sudha app update hui: ").append(f.sudhaUpdate).append(".\n");
            if (f.learned.isEmpty() && f.waiting.isEmpty()) {
                out.append("Abhi tak kuch naya seekhne ki zaroorat nahin padi — Sudha app wahi "
                        + "purane naam use kar rahi hai.");
            } else {
                if (!f.learned.isEmpty()) {
                    out.append("Yeh naye naam maine seekh liye:\n");
                    for (String line : f.learned) out.append("  • ").append(line).append('\n');
                }
                if (!f.waiting.isEmpty()) {
                    out.append("Inke liye aapki haan chahiye:\n");
                    for (String line : f.waiting) out.append("  • ").append(line).append('\n');
                }
            }
            return new Reply(out.toString().trim(), f.waiting.isEmpty() ? Action.NONE : Action.SHOW_NOTEBOOK);
        }
        if (any(q, "kyon", "kyu", "kyun", "why", "ruki", "ruka", "ruk", "stop", "stopped", "error",
                "problem", "dikkat", "atak", "stuck", "galat")) {
            if (f.lastStop.isEmpty()) {
                return new Reply("Main ruki hi nahin! Ab tak koi stop record nahin hai.", Action.NONE);
            }
            StringBuilder out = new StringBuilder("Pichhli baar");
            if (!f.lastStopWhen.isEmpty()) out.append(" (").append(f.lastStopWhen).append(")");
            out.append(" run yahan ruka:\n").append(f.lastStop);
            if (!f.reportBrief.isEmpty()) out.append("\n\nMaine jo dekha:\n").append(f.reportBrief);
            if (f.hasReport) out.append("\n\n\"Report bhejo\" boliye to main share khol dungi.");
            return new Reply(out.toString(), Action.NONE);
        }
        if (any(q, "retailer", "retailers", "distributor", "roster", "list", "kitne retailer", "smr", "sm")) {
            StringBuilder out = new StringBuilder("Sudha app ke hisaab se: ").append(
                    f.roster.isEmpty() ? "list abhi padhi nahin gayi" : f.roster).append('.');
            if (!f.distributorBefore.isEmpty() && !f.distributor.isEmpty()) {
                out.append("\nDistributor badla hai: pehle ").append(f.distributorBefore)
                        .append(", ab ").append(f.distributor).append('.');
            }
            out.append("\nGinti main app se khud padhti hoon — 125 ho ya 300. "
                    + "ID mein SMR = retailer; SM ya sirf number = distributor.");
            return new Reply(out.toString(), Action.NONE);
        }
        if (any(q, "haal", "status", "progress", "kitne", "kitna", "how many", "done", "hua", "ho gaye",
                "aaj", "today", "count")) {
            StringBuilder out = new StringBuilder();
            out.append(f.placed).append(" order lag gaye");
            if (f.skipped > 0) out.append(", ").append(f.skipped).append(" skip");
            if (f.toCheck > 0) out.append(", ").append(f.toCheck).append(" ek baar dekhne hain");
            out.append(". Kul ").append(f.handled).append(" of ").append(Math.max(f.expected, f.handled))
                    .append(" retailer.");
            if (f.expected > 0 && f.handled >= f.expected) out.append(" Kaam complete hua!");
            else if (!f.ready) out.append(" (Upar ki teeno keys abhi poori on nahin hain.)");
            return new Reply(out.toString(), Action.NONE);
        }
        if (any(q, "job", "naukri", "shaurya", "salary", "tankha", "boss", "chutti", "promotion")) {
            return new Reply("AI ne Shaurya ki job chheen li. Salary nahin maangti, chhutti nahin "
                    + "leti — bas kabhi kabhi charger chahiye. Employee of the Month, har month.",
                    Action.NONE);
        }
        if (any(q, "kaun", "who", "naam", "name", "aakriti", "hello", "hi", "hey", "namaste", "hii")) {
            return new Reply(greeting(f), Action.NONE);
        }
        if (any(q, "thank", "thanks", "shukriya", "dhanyavad", "great", "badhiya", "shabash")) {
            return new Reply("Mogambo khush hua!", Action.NONE);
        }
        return new Reply("Yeh mujhe samajh nahin aaya — main chhoti si scripted assistant hoon, "
                + "poori AI nahin. In par pooch sakte hain:\n"
                + "  • Aaj ka haal?\n  • Kya seekha?\n  • Kyon ruki?\n  • Kitne retailer?\n"
                + "  • Reset se kya hoga?\n  • Skip list?\n  • Report bhejo", Action.NONE);
    }

    private static boolean any(String padded, String... words) {
        for (String word : words) {
            if (padded.contains(" " + word + " ")) return true;
        }
        return false;
    }
}
