import android.view.accessibility.AccessibilityNodeInfo;

import com.vrksignatures.sudhaautobot.AakritiChat;
import com.vrksignatures.sudhaautobot.AccessibilityTree;
import com.vrksignatures.sudhaautobot.FeedbackCues;
import com.vrksignatures.sudhaautobot.LearnedUi;
import com.vrksignatures.sudhaautobot.ScreenLabels;
import com.vrksignatures.sudhaautobot.UiMolder;
import com.vrksignatures.sudhaautobot.UiMolder.Label;
import com.vrksignatures.sudhaautobot.UiMolder.Status;
import com.vrksignatures.sudhaautobot.UiVocabulary;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Aakriti's learning, held to its promises.
 *
 * 1. With nothing learned, the bot reads every screen exactly as it did before.
 * 2. On the app as it is today she learns nothing at all.
 * 3. A renamed label is learned when - and only when - the rest of the screen still says
 *    which screen it is, the match is strong, and nothing else on screen is nearly as close.
 * 4. A new name for PLACE ORDER is never used until a person allows it; nothing about
 *    paying or succeeding is ever learned or re-read.
 * 5. What is learned survives a restart and is not touched by Reset.
 */
public final class MoldingSelfTest {

    private static int checks;
    private static int failures;

    private static final String APP = "../../app/src/main/java/com/vrksignatures/sudhaautobot/";

    public static void main(String[] args) throws Exception {
        LearnedUi.clear();
        emptyNotebookChangesNothing();
        todaysAppTeachesNothing();
        renamesAreLearned();
        placeOrderNeedsAPerson();
        dangerousLabelsAreNeverTaken();
        wrongScreenTeachesNothing();
        closeCallsAreNotGuessed();
        aButtonMustBePressable();
        moneyScreensAreReadAsPrinted();
        unknownStateStillFindsTheScreen();
        aWholeNewAppIsReportedNotGuessed();
        theTreeFindsLearnedButtons();
        notebookSurvives();
        resetKeepsTheNotebook();
        vocabularyMatchesTheBot();
        chatAndFinale();
        LearnedUi.clear();

        if (failures > 0) {
            System.out.println(failures + " OF " + checks + " CHECKS FAILED");
            System.exit(1);
        }
        System.out.println("ALL " + checks + " CHECKS PASSED");
    }

    // ------------------------------------------------------------- the app today

    private static Label t(String text) {
        return new Label(text, false);
    }

    private static Label b(String text) {
        return new Label(text, true);
    }

    private static List<Label> home() {
        return new ArrayList<>(Arrays.asList(t("Sudha Dairy"), t("Welcome"), t("Total Customers: 125"),
                b("Customers"), b("Daily Orders"), b("Reports"), b("Logout")));
    }

    private static List<Label> list() {
        return new ArrayList<>(Arrays.asList(t("Customers"), t("Total Customers: 125"), b("All Distributors"),
                b("Show Retailers"), b("Ranjana Yadvendu"), b("Customer Id: SM65"),
                b("Maa Durga Store"), b("Customer Id: SMR2255"), b("Shiv Dairy"), b("Customer Id: SMR2254"),
                b("Krishna Kirana"), b("Customer Id: SMR2253"), b("Om Sweets"), b("Customer Id: SMR2252")));
    }

    private static List<Label> details() {
        return new ArrayList<>(Arrays.asList(t("Customer Details"), t("Name"), t("Maa Durga Store (SMR2255)"),
                t("Distributor Name ( SM65 )"), t("Ranjana Yadvendu"), t("Mobile"), t("View Orders"),
                b("Milk"), b("PROD"), b("Transactions"), b("Pay")));
    }

    private static List<Label> history() {
        return new ArrayList<>(Arrays.asList(t("New Order Milk"), b("< November,2026 >"),
                t("Order created on 16-11-2026"), t("Order Id"), t("48213"), t("Products"), t("Order Qty"),
                t("24"), t("Order placed"), t("Order created on 15-11-2026"), t("Order Id"), t("48102")));
    }

    private static List<Label> form() {
        return new ArrayList<>(Arrays.asList(t("New Order Milk"), t("Date"), b("17-11-2026"), t("Item Group"),
                t("Zone"), t("Party"), t("Item"), t("Rate"), t("MQty"), t("EQty"), t("Total"),
                t("Toned Milk 500ml"), b("Copy Order"), b("View Order Summary"), b("PLACE ORDER")));
    }

    private static List<Label> payment() {
        return new ArrayList<>(Arrays.asList(t("Payment"), t("Paid Amount"), t("1240"), t("Payment Mode"),
                b("CASH"), t("Remarks"), b("Pay"), b("PROCEED")));
    }

    private static List<Label> success() {
        return new ArrayList<>(Arrays.asList(t("Successfully Payment Created"), b("Done")));
    }

    private static List<Label> swap(List<Label> screen, String from, String to) {
        List<Label> out = new ArrayList<>();
        boolean swapped = false;
        for (Label label : screen) {
            if (label.text.startsWith(from)) {
                out.add(new Label(to + label.text.substring(from.length()), label.pressable));
                swapped = true;
            } else {
                out.add(label);
            }
        }
        if (!swapped) throw new IllegalStateException("no label \"" + from + "\" to rename");
        return out;
    }

    private static String textOf(List<Label> screen) {
        StringBuilder out = new StringBuilder();
        for (Label label : screen) out.append(label.text.toLowerCase(Locale.ROOT)).append('\n');
        return out.toString();
    }

    private static UiMolder.Line line(UiMolder.Finding finding, String slot) {
        for (UiMolder.Line line : finding.lines) {
            if (line.slot.key.equals(slot)) return line;
        }
        return null;
    }

    private static int count(UiMolder.Finding finding, Status status) {
        return finding.with(status).size();
    }

    /** Writes a finding into the notebook the way Aakriti does. */
    private static void adopt(UiMolder.Finding finding) {
        for (UiMolder.Line line : finding.lines) {
            if (line.status != Status.LEARNED && line.status != Status.ASK) continue;
            LearnedUi.add(new LearnedUi.Entry(line.slot.key, line.alias(), line.shown, "2026-11-18",
                    "3.2 (41)", line.status == Status.LEARNED));
        }
    }

    // --------------------------------------------------------------- the checks

    private static void emptyNotebookChangesNothing() {
        check("the notebook starts empty", LearnedUi.isEmpty() && LearnedUi.entries().isEmpty());
        Object[][] screens = {{"HOME", home()}, {"RETAILER_LIST", list()}, {"DETAILS", details()},
                {"HISTORY", history()}, {"COPY_ORDER", form()}, {"PAYMENT", payment()}, {"SUCCESS", success()}};
        for (Object[] screen : screens) {
            @SuppressWarnings("unchecked")
            String text = textOf((List<Label>) screen[1]);
            check(screen[0] + ": the very same text comes back", LearnedUi.translate(text) == text);
            check(screen[0] + ": also for the bot", LearnedUi.forBot(text, (String) screen[0]) == text);
        }
        String[] words = {"place order", "copy order", "milk", "customers", "pay", "proceed", "done", "ok"};
        for (String word : words) {
            check("\"" + word + "\" has no second name", !LearnedUi.same("submit order", word)
                    && !LearnedUi.same(word, word));
            check("\"" + word + "\" equals only itself", AccessibilityTree.equalsLabel(word.toUpperCase(), word)
                    && !AccessibilityTree.equalsLabel("submit order", word));
        }
    }

    private static void todaysAppTeachesNothing() {
        Object[][] screens = {{"HOME", home(), 2}, {"RETAILER_LIST", list(), 2}, {"DETAILS", details(), 4},
                {"HISTORY", history(), 2}, {"COPY_ORDER", form(), 2}, {"PLACE_ORDER", form(), 2}};
        for (Object[] screen : screens) {
            @SuppressWarnings("unchecked")
            List<Label> labels = (List<Label>) screen[1];
            UiMolder.Finding finding = UiMolder.study((String) screen[0], labels);
            check(screen[0] + ": recognised", finding.recognised);
            check(screen[0] + ": every word present",
                    count(finding, Status.PRESENT) == (Integer) screen[2] && finding.lines.size() == (Integer) screen[2]);
            check(screen[0] + ": nothing learned", !finding.changed());
            check(screen[0] + ": studied from any state, still nothing",
                    !UiMolder.studyAny("IDLE", labels).changed()
                            && !UiMolder.studyAny("RETURNING_TO_LIST", labels).changed());
        }
        // States with no screen of their own are not studied at all.
        for (String state : new String[]{"IDLE", "PAYMENT", "SUCCESS", "RETURNING_TO_LIST", "COMPLETE",
                "PAUSED", "ERROR", null, "nonsense"}) {
            UiMolder.Finding finding = UiMolder.study(state, payment());
            check(state + ": no screen, nothing learned", finding.screen == null && finding.lines.isEmpty());
        }
        check("payment and success have no vocabulary", UiVocabulary.screenForState("PAYMENT") == null
                && UiVocabulary.screenForState("SUCCESS") == null);
        check("the payment screen teaches nothing from any state",
                !UiMolder.studyAny("PLACE_ORDER", payment()).changed()
                        && !UiMolder.studyAny("IDLE", payment()).changed()
                        && !UiMolder.studyAny("IDLE", success()).changed());
    }

    private static void renamesAreLearned() {
        // state, screen, old label, new label, slot, the word the bot looks for
        Object[][] cases = {
                {"HOME", home(), "Customers", "Retailer List", "home.customers", "customers"},
                {"HOME", home(), "Customers", "My Parties", "home.customers", "customers"},
                {"HOME", home(), "Total Customers", "Total Parties", "home.total", "total customers"},
                {"RETAILER_LIST", list(), "Show Retailers", "Show Customers", "list.show", "show retailers"},
                {"RETAILER_LIST", list(), "Show Retailers", "View Outlets", "list.show", "show retailers"},
                {"RETAILER_LIST", list(), "Total Customers", "Total Retailers", "list.total", "total customers"},
                {"DETAILS", details(), "Customer Details", "Customer Info", "details.title", "customer details"},
                {"DETAILS", details(), "Customer Details", "Retailer Profile", "details.title", "customer details"},
                {"DETAILS", details(), "View Orders", "See Orders", "details.orders", "view orders"},
                {"DETAILS", details(), "Distributor Name", "Dealer Name", "details.distributor", "distributor name"},
                {"DETAILS", details(), "Milk", "Dairy", "details.milk", "milk"},
                {"DETAILS", details(), "Milk", "Doodh", "details.milk", "milk"},
                {"HISTORY", history(), "Order created on", "Order booked on", "history.created", "order created on"},
                {"HISTORY", history(), "Order Qty", "Order Quantity", "history.qty", "order qty"},
                {"COPY_ORDER", form(), "Copy Order", "Repeat Order", "form.copy", "copy order"},
                {"COPY_ORDER", form(), "Copy Order", "Duplicate Order", "form.copy", "copy order"},
                {"COPY_ORDER", form(), "Copy Order", "Copy Ordr", "form.copy", "copy order"},
        };
        for (Object[] c : cases) {
            LearnedUi.clear();
            @SuppressWarnings("unchecked")
            List<Label> changed = swap((List<Label>) c[1], (String) c[2], (String) c[3]);
            String name = "\"" + c[2] + "\" → \"" + c[3] + "\"";
            UiMolder.Finding finding = UiMolder.study((String) c[0], changed);
            UiMolder.Line found = line(finding, (String) c[4]);
            check(name + ": learned", found != null && found.status == Status.LEARNED);
            check(name + ": nothing else disturbed", count(finding, Status.LEARNED) == 1
                    && count(finding, Status.ASK) == 0 && count(finding, Status.MISSING) == 0);
            if (found == null) continue;
            check(name + ": written down without its figures",
                    found.alias().equals(((String) c[3]).toLowerCase(Locale.ROOT)));

            String before = textOf(changed);
            check(name + ": the bot cannot see it yet", !LearnedUi.translate(before).contains((String) c[5])
                    || before.contains((String) c[5]));
            adopt(finding);
            UiVocabulary.Slot slot = UiVocabulary.slot((String) c[4]);
            if (slot.role == UiVocabulary.Role.MARKER) {
                check(name + ": now the bot reads the word it knows",
                        LearnedUi.forBot(before, (String) c[0]).contains((String) c[5]));
            } else {
                check(name + ": now the bot presses it under the name it knows",
                        AccessibilityTree.equalsLabel((String) c[3], (String) c[5]));
            }
            UiMolder.Finding again = UiMolder.study((String) c[0], changed);
            check(name + ": a second look finds everything", count(again, Status.PRESENT) == again.lines.size());
            check(name + ": and the old name still works too",
                    count(UiMolder.study((String) c[0], (List<Label>) c[1]), Status.PRESENT) == again.lines.size());
        }
        LearnedUi.clear();

        // Two renames on one screen are learned together.
        List<Label> both = swap(swap(details(), "Customer Details", "Customer Info"), "Milk", "Dairy");
        UiMolder.Finding finding = UiMolder.study("DETAILS", both);
        check("two renames at once", count(finding, Status.LEARNED) == 2 && count(finding, Status.MISSING) == 0);
    }

    private static void placeOrderNeedsAPerson() {
        for (String renamed : new String[]{"Submit Order", "Confirm Order", "Book Order", "PLACE ORDR"}) {
            LearnedUi.clear();
            List<Label> changed = swap(form(), "PLACE ORDER", renamed);
            UiMolder.Finding finding = UiMolder.study("PLACE_ORDER", changed);
            UiMolder.Line found = line(finding, "form.place");
            check(renamed + ": proposed, not learned", found != null && found.status == Status.ASK
                    && count(finding, Status.LEARNED) == 0);
            adopt(finding);
            check(renamed + ": written down", LearnedUi.knows(renamed) && LearnedUi.entries().size() == 1
                    && !LearnedUi.entries().get(0).live);
            check(renamed + ": NOT used", LearnedUi.isEmpty()
                    && !AccessibilityTree.equalsLabel(renamed, "place order")
                    && !LearnedUi.forBot(textOf(changed), "PLACE_ORDER").contains("place order"));
            check(renamed + ": still waiting after a restart", restart() && LearnedUi.isEmpty()
                    && !AccessibilityTree.equalsLabel(renamed, "PLACE ORDER"));
            check(renamed + ": not proposed twice",
                    line(UiMolder.study("PLACE_ORDER", changed), "form.place").status == Status.MISSING);

            check(renamed + ": a person allows it", LearnedUi.allow(renamed));
            check(renamed + ": now used", AccessibilityTree.equalsLabel(renamed, "PLACE ORDER")
                    && LearnedUi.forBot(textOf(changed), "PLACE_ORDER").contains("place order"));
            check(renamed + ": forgotten on request", LearnedUi.forget(renamed)
                    && !AccessibilityTree.equalsLabel(renamed, "PLACE ORDER") && LearnedUi.entries().isEmpty());
        }
        check("PLACE ORDER is the slot that asks",
                UiVocabulary.slot("form.place").trust == UiVocabulary.Trust.ASK);
        int asking = 0;
        for (UiVocabulary.Slot slot : UiVocabulary.SLOTS) if (slot.trust == UiVocabulary.Trust.ASK) asking++;
        check("and the only one", asking == 1);
    }

    private static boolean restart() {
        String saved = LearnedUi.encode();
        LearnedUi.clear();
        LearnedUi.decode(saved);
        return saved.equals(LearnedUi.encode());
    }

    private static void dangerousLabelsAreNeverTaken() {
        LearnedUi.clear();
        String[] never = {"Cancel Order", "Delete Order", "Discard Order", "Clear Order", "Edit Order",
                "Return Order", "Reject Order", "Pay Order", "Order Payment", "Proceed", "Pay Now",
                "Order Done", "Back", "Close", "No", "Skip Order", "Order Failed", "Refund Order"};
        for (String label : never) {
            for (String target : new String[]{"PLACE ORDER", "Copy Order"}) {
                List<Label> changed = swap(form(), target, label);
                UiMolder.Finding finding = UiMolder.study("PLACE_ORDER", changed);
                check("\"" + label + "\" is never taken for " + target, !finding.changed());
            }
        }
        // ... and not when they merely sit beside the real thing either.
        List<Label> beside = form();
        beside.add(b("Cancel Order"));
        beside.add(b("Delete Order"));
        check("extra buttons beside the real ones teach nothing", !UiMolder.study("PLACE_ORDER", beside).changed());

        // "Order placed" is the status of past orders; it is not the "Order created on" line.
        List<Label> past = new ArrayList<>(Arrays.asList(t("New Order Milk"), t("Order Id"), t("48213"),
                t("Products"), t("Order Qty"), t("24"), t("Order placed"), t("Dated 16-11-2026")));
        UiMolder.Line created = line(UiMolder.study("HISTORY", past), "history.created");
        check("\"Order placed\" is not taken for \"Order created on\"",
                created != null && created.status == Status.MISSING);

        // A retailer's own name or id is never a button name.
        List<Label> names = swap(details(), "Milk", "SMR2255");
        check("an id is never a tab", !UiMolder.study("DETAILS", names).changed());
    }

    private static void wrongScreenTeachesNothing() {
        LearnedUi.clear();
        Object[][] wrong = {{"PLACE_ORDER", payment()}, {"PLACE_ORDER", success()}, {"COPY_ORDER", history()},
                {"COPY_ORDER", details()}, {"HISTORY", form()}, {"DETAILS", form()}, {"DETAILS", history()},
                {"HOME", list()}, {"RETAILER_LIST", details()}, {"HOME", payment()}, {"DETAILS", payment()},
                {"HISTORY", payment()}, {"RETAILER_LIST", success()}};
        for (Object[] c : wrong) {
            @SuppressWarnings("unchecked")
            UiMolder.Finding finding = UiMolder.study((String) c[0], (List<Label>) c[1]);
            check(c[0] + " on another screen: nothing learned", !finding.changed());
        }
        // A login page, a blank page, a crash dialog.
        List<List<Label>> strange = Arrays.asList(
                Arrays.asList(t("Login"), t("Mobile Number"), t("Password"), b("Sign In"), b("Forgot Password?")),
                new ArrayList<Label>(),
                Arrays.asList(t("Sudha Daily Orders keeps stopping"), b("App info"), b("Close app")),
                Arrays.asList(t("Update available"), t("A new version of the app is ready"), b("Update"), b("Later")),
                Arrays.asList(t("Submit Order"), t("Submit Order"), b("Submit Order")));
        for (String state : new String[]{"HOME", "RETAILER_LIST", "DETAILS", "HISTORY", "COPY_ORDER",
                "PLACE_ORDER", "IDLE"}) {
            for (List<Label> screen : strange) {
                check(state + ": a strange screen teaches nothing",
                        !UiMolder.studyAny(state, screen).changed());
            }
        }
        check("no labels at all is survived", !UiMolder.study("HOME", null).changed()
                && !UiMolder.studyAny("HOME", null).changed());
    }

    private static void closeCallsAreNotGuessed() {
        LearnedUi.clear();
        // Two buttons that could both be the new PLACE ORDER.
        List<Label> two = swap(form(), "PLACE ORDER", "Submit Order");
        two.add(b("Confirm Order"));
        UiMolder.Line place = line(UiMolder.study("PLACE_ORDER", two), "form.place");
        check("two candidates: neither is taken", place.status == Status.MISSING && !place.why.isEmpty());

        // One new label that fits two missing words.
        List<Label> shared = new ArrayList<>();
        for (Label label : form()) {
            if (!label.text.equals("PLACE ORDER") && !label.text.equals("Copy Order")) shared.add(label);
        }
        shared.add(b("Order"));
        check("one vague label: nothing learned", !UiMolder.study("PLACE_ORDER", shared).changed());

        // A weak resemblance.
        List<Label> weak = swap(form(), "Copy Order", "Order Templates");
        check("a weak resemblance is not enough", !UiMolder.study("COPY_ORDER", weak).changed());
        List<Label> other = swap(details(), "Milk", "Silk");
        check("a short word one letter off is not enough", !UiMolder.study("DETAILS", other).changed());
    }

    private static void aButtonMustBePressable() {
        LearnedUi.clear();
        List<Label> heading = new ArrayList<>();
        for (Label label : form()) {
            heading.add(label.text.equals("PLACE ORDER") ? t("Submit Order") : label);
        }
        check("plain text is not a button", !UiMolder.study("PLACE_ORDER", heading).changed());
        List<Label> tab = new ArrayList<>();
        for (Label label : details()) tab.add(label.text.equals("Milk") ? t("Dairy") : label);
        check("plain text is not a tab", !UiMolder.study("DETAILS", tab).changed());
    }

    private static void moneyScreensAreReadAsPrinted() {
        LearnedUi.clear();
        // Fill the notebook with everything it could hold.
        adopt(UiMolder.study("HISTORY", swap(history(), "Order created on", "Order booked on")));
        adopt(UiMolder.study("COPY_ORDER", swap(form(), "Copy Order", "Repeat Order")));
        adopt(UiMolder.study("PLACE_ORDER", swap(form(), "PLACE ORDER", "Submit Order")));
        LearnedUi.allow("submit order");
        adopt(UiMolder.study("DETAILS", swap(details(), "Customer Details", "Customer Info")));
        check("four names in use", LearnedUi.entries().size() == 4 && !LearnedUi.isEmpty());

        String pay = textOf(payment()) + "order booked on 17-11-2026\nsubmit order\nrepeat order\ncustomer info\n";
        String done = textOf(success()) + "order booked on 17-11-2026\nsubmit order\n";
        for (String state : new String[]{"PAYMENT", "SUCCESS", "PLACE_ORDER", "COPY_ORDER", "HISTORY",
                "RETURNING_TO_LIST", "IDLE", "HOME"}) {
            check(state + ": a payment screen is read as printed", LearnedUi.forBot(pay, state) == pay);
            check(state + ": a success screen is read as printed", LearnedUi.forBot(done, state) == done);
        }
        String plain = "new order milk\norder booked on 16-11-2026\nsubmit order\n";
        check("while paying, nothing is put back anywhere",
                LearnedUi.forBot(plain, "PAYMENT") == plain && LearnedUi.forBot(plain, "SUCCESS") == plain);
        check("elsewhere it is", LearnedUi.forBot(plain, "HISTORY").contains("order created on"));

        // No learned name can stand for a payment or confirmation button.
        for (String button : new String[]{"pay", "pay now", "proceed", "continue", "confirm", "ok", "yes",
                "done", "cash"}) {
            for (LearnedUi.Entry entry : LearnedUi.entries()) {
                check("\"" + entry.shown + "\" never answers to \"" + button + "\"",
                        !AccessibilityTree.equalsLabel(entry.shown, button)
                                && !LearnedUi.translate(entry.alias).contains(button));
            }
        }
        for (UiVocabulary.Slot slot : UiVocabulary.SLOTS) {
            for (String phrase : slot.accepts) {
                check("\"" + phrase + "\" is not a money word", !phrase.matches(
                        ".*\\b(pay|paid|payment|proceed|done|ok|yes|success|cash|amount|confirm|continue)\\b.*"));
            }
        }
        LearnedUi.clear();
    }

    /** "Unrecognised screen" happens while the bot's state says nothing about the screen. */
    private static void unknownStateStillFindsTheScreen() {
        LearnedUi.clear();
        List<Label> changed = swap(home(), "Total Customers", "Total Parties");
        for (String state : new String[]{"IDLE", "RETURNING_TO_LIST", "DETAILS"}) {
            UiMolder.Finding finding = UiMolder.studyAny(state, changed);
            check(state + ": the home screen is found by its other words",
                    finding.screen != null && finding.screen.key.equals("home")
                            && line(finding, "home.total").status == Status.LEARNED);
        }
        check("the plain study does not look beyond the state",
                !UiMolder.study("IDLE", changed).changed());
        List<Label> listChanged = swap(list(), "Show Retailers", "Show Customers");
        UiMolder.Finding finding = UiMolder.studyAny("IDLE", listChanged);
        check("the list is told from home", finding.screen != null && finding.screen.key.equals("list")
                && line(finding, "list.show").status == Status.LEARNED);
    }

    private static void aWholeNewAppIsReportedNotGuessed() {
        LearnedUi.clear();
        List<Label> redesigned = Arrays.asList(t("Dashboard"), t("Good morning"), b("Outlets"), b("Bookings"),
                b("Ledger"), b("Help"), t("Version 4.0"));
        for (String state : new String[]{"HOME", "RETAILER_LIST", "DETAILS", "HISTORY", "COPY_ORDER", "IDLE"}) {
            UiMolder.Finding finding = UiMolder.studyAny(state, redesigned);
            check(state + ": a redesign is not guessed at", !finding.changed());
            if (finding.screen != null) {
                check(state + ": and is said to be unrecognised", !finding.recognised);
                check(state + ": every word is reported missing",
                        count(finding, Status.MISSING) == finding.lines.size() && !finding.lines.isEmpty());
                String words = finding.describe();
                check(state + ": the report names the screen and the words",
                        words.contains("NOT recognised") && words.contains("MISSING")
                                && words.contains(finding.lines.get(0).slot.primary()));
            }
        }
        String[] reasons = {
                "Unrecognised screen in com.sudha.dailyorders — paused instead of guessing",
                "Could not find \"Customers\" on the Sudha home screen",
                "Could not find the \"Show Retailers\" control on the list",
                "Could not open the Milk module for SMR2255"};
        for (String reason : reasons) check("a lookup stop: " + reason, UiMolder.isLookupStop(reason));
        String[] others = {
                "PLACE ORDER could not be tapped; order was not placed",
                "In-flight order SMR2252 never reached a payment or success screen",
                "Payment chain exceeded 6 steps; reconcile", "Could not persist SUCCESS for SMR1",
                "Order for SMR2252 was not confirmed — check the app", "", null};
        for (String reason : others) check("never restarted after: " + reason, !UiMolder.isLookupStop(reason));
    }

    private static AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = new AccessibilityNodeInfo();
        node.text = text;
        node.clickable = clickable;
        node.className = clickable ? "android.widget.Button" : "android.widget.TextView";
        node.bounds.set(0, 0, 200, 80);
        return node;
    }

    /** The service's own search (AccessibilityTree), on a real tree. */
    private static void theTreeFindsLearnedButtons() {
        LearnedUi.clear();
        AccessibilityNodeInfo root = node(null, false);
        root.add(node("New Order Milk", false)).add(node("MQty", false)).add(node("EQty", false))
                .add(node("Item Group", false)).add(node("Party", false));
        AccessibilityNodeInfo wrapper = node(null, true);
        wrapper.add(node("Repeat Order", false));
        root.add(wrapper);
        root.add(node("Submit Order", true));
        root.add(node("Cancel Order", true));

        check("before: Copy Order is not found", AccessibilityTree.findClickableLabel(root, "Copy Order") == null
                && AccessibilityTree.findContaining(root, "Copy Order") == null);
        check("before: PLACE ORDER is not found", AccessibilityTree.findClickableLabel(root, "PLACE ORDER") == null
                && AccessibilityTree.findContaining(root, "PLACE ORDER") == null);

        List<Label> labels = ScreenLabels.of(root);
        check("the labels are read with their pressability", labels.size() == 8
                && !labels.get(0).pressable && labels.get(5).text.equals("Repeat Order") && labels.get(5).pressable);
        UiMolder.Finding finding = UiMolder.study("COPY_ORDER", labels);
        check("Copy Order learned, PLACE ORDER proposed", line(finding, "form.copy").status == Status.LEARNED
                && line(finding, "form.place").status == Status.ASK);
        adopt(finding);

        AccessibilityNodeInfo copy = AccessibilityTree.findClickableLabel(root, "Copy Order");
        check("after: Copy Order is found under its new name",
                copy != null && (copy == wrapper || "Repeat Order".contentEquals(AccessibilityTree.textOf(copy))));
        check("after: also by the second search", AccessibilityTree.findContaining(root, "Copy Order") != null);
        check("after: PLACE ORDER is still not found (nobody allowed it)",
                AccessibilityTree.findClickableLabel(root, "PLACE ORDER") == null
                        && AccessibilityTree.findContaining(root, "PLACE ORDER") == null);
        LearnedUi.allow("submit order");
        AccessibilityNodeInfo place = AccessibilityTree.findClickableLabel(root, "PLACE ORDER");
        check("allowed: PLACE ORDER is the Submit Order button",
                place != null && "Submit Order".contentEquals(place.getText()));
        check("Cancel Order answers to nothing", !AccessibilityTree.equalsLabel("Cancel Order", "place order")
                && !AccessibilityTree.equalsLabel("Cancel Order", "copy order"));
        check("other searches are untouched", AccessibilityTree.findClickableLabel(root, "PAY") == null
                && AccessibilityTree.findClickableLabel(root, "Done") == null
                && AccessibilityTree.findContaining(root, "Proceed") == null);
        LearnedUi.clear();
        check("forgotten: gone again", AccessibilityTree.findClickableLabel(root, "Copy Order") == null
                && AccessibilityTree.findClickableLabel(root, "PLACE ORDER") == null);
    }

    private static void notebookSurvives() throws Exception {
        LearnedUi.clear();
        adopt(UiMolder.study("DETAILS", swap(details(), "Customer Details", "Customer Info")));
        adopt(UiMolder.study("PLACE_ORDER", swap(form(), "PLACE ORDER", "Submit Order")));
        adopt(UiMolder.study("HOME", swap(home(), "Total Customers", "Total Parties")));
        File folder = Files.createTempDirectory("aakriti").toFile();
        File file = new File(folder, "deep/learned.txt");
        check("saved", LearnedUi.save(file) && file.length() > 0);
        String saved = LearnedUi.encode();
        LearnedUi.clear();
        check("cleared", LearnedUi.entries().isEmpty());
        check("loaded", LearnedUi.load(file) && LearnedUi.encode().equals(saved) && LearnedUi.entries().size() == 3);
        check("in use again", LearnedUi.forBot("customer info\nview orders\n", "DETAILS").contains("customer details")
                && LearnedUi.forBot("total parties: 300\n", "HOME").contains("total customers: 300"));
        check("the unallowed one is still unallowed", !AccessibilityTree.equalsLabel("Submit Order", "place order"));

        // A damaged or missing file is an empty notebook, not a crash.
        LearnedUi.clear();
        check("a missing file", LearnedUi.load(new File(folder, "nothing.txt")) && LearnedUi.isEmpty());
        Files.write(file.toPath(), "rubbish\n\t\t\t\t\t\nno.such.slot\tx\tx\tx\tx\tlive\n\u0000\u0001".getBytes(StandardCharsets.UTF_8));
        LearnedUi.load(file);
        check("a damaged file", LearnedUi.entries().isEmpty() && LearnedUi.isEmpty());
        // Nobody can write a known word into the notebook under another meaning.
        check("a known word is refused", !LearnedUi.add(new LearnedUi.Entry("form.place", "copy order",
                "Copy Order", "", "", true)) && LearnedUi.entries().isEmpty());
        check("a name is given one meaning only",
                LearnedUi.add(new LearnedUi.Entry("form.copy", "repeat order", "Repeat Order", "", "", true))
                        && !LearnedUi.add(new LearnedUi.Entry("form.place", "repeat order", "Repeat Order", "", "", true))
                        && LearnedUi.entries().size() == 1);
        LearnedUi.clear();
        for (File left : new File[]{file, file.getParentFile(), folder}) left.delete();
    }

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get(APP + name)), StandardCharsets.UTF_8);
    }

    private static String method(String source, String signature) {
        int from = source.indexOf(signature);
        if (from < 0) return null;
        int depth = 0;
        for (int i = source.indexOf('{', from); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(from, i + 1);
        }
        return null;
    }

    /** Reset clears today's session; the code it runs never mentions the notebook. */
    private static void resetKeepsTheNotebook() throws Exception {
        String service = read("SudhaAccessibilityService.java");
        String[] kept = {"Aakriti", "aakriti", "LearnedUi", "Roster", "learned"};
        for (String signature : new String[]{"private void resetLedger()", "private void resetRunCounters()"}) {
            String body = method(service, signature);
            check(signature + " exists", body != null);
            if (body == null) continue;
            for (String word : kept) check(signature + " does not touch " + word, !body.contains(word));
        }
        for (String name : new String[]{"DailyLedger.java", "WorkflowStateMachine.java"}) {
            String source = read(name);
            for (String word : kept) check(name + " does not touch " + word, !source.contains(word));
        }
        String aakriti = read("Aakriti.java");
        check("the notebook is kept in its own folder", aakriti.contains("\"aakriti\"")
                && aakriti.contains("\"learned.txt\""));
        check("the ledger does not live there", !read("DailyLedger.java").contains("\"aakriti\""));
        String forget = method(aakriti, "void forgetEverything()");
        check("forgetting is its own action", forget != null && forget.contains("LearnedUi.clear()"));
        int callers = 0;
        for (File file : new File(APP).listFiles()) {
            String source = read(file.getName());
            if (file.getName().equals("Aakriti.java")) continue;
            if (source.contains("forgetEverything(")) {
                callers++;
                check("only the setup screen can forget everything", file.getName().equals("AakritiDesk.java"));
            }
            if (!file.getName().equals("LearnedUi.java")) {
                check(file.getName() + " never clears the notebook directly", !source.contains("LearnedUi.clear("));
            }
        }
        check("forget-everything has at most one caller", callers <= 1);
    }

    /** UiVocabulary is a copy of the words in the service; the copy must not drift. */
    private static void vocabularyMatchesTheBot() throws Exception {
        String service = read("SudhaAccessibilityService.java").toLowerCase(Locale.ROOT);
        for (UiVocabulary.Slot slot : UiVocabulary.SLOTS) {
            for (String phrase : slot.accepts) {
                check("the bot really looks for \"" + phrase + "\"", service.contains("\"" + phrase + "\""));
            }
            check(slot.key + " belongs to a screen", UiVocabulary.screen(slot.screen) != null);
        }
        for (UiVocabulary.Screen screen : UiVocabulary.SCREENS) {
            for (String anchor : screen.anchors) {
                if (anchor.startsWith("#")) continue;
                check("the bot really knows \"" + anchor + "\"", service.contains(anchor));
            }
        }
        for (String stop : new String[]{"Unrecognised screen in ",
                "Could not find \\\"Customers\\\" on the Sudha home screen",
                "Could not find the \\\"Show Retailers\\\" control on the list",
                "Could not open the Milk module for "}) {
            check("the bot really stops with: " + stop, service.contains("fail(\"" + stop.toLowerCase(Locale.ROOT)));
            check("and Aakriti knows that stop", UiMolder.isLookupStop(stop.replace("\\\"", "\"") + "x"));
        }
        // The hooks in the service are fenced.
        String raw = read("SudhaAccessibilityService.java");
        for (String signature : new String[]{"private void aakritiAfterStop(String reason)",
                "private String aakritiSees(String textLower)",
                "private void aakritiAfterRetailerError(String reason)"}) {
            String body = method(raw, signature);
            check(signature + " is fenced", body != null && body.contains("try {")
                    && body.contains("catch (Throwable"));
        }
        int uses = raw.split("aakriti\\(\\)", -1).length - 1;
        check("Aakriti is reached from the three hooks only (" + uses + ")", uses == 5);
    }

    private static void chatAndFinale() {
        // Silent UI: the spoken welcome, the Veena and the film lines are gone, and so are
        // their sound files. What is left of the finale is the words on the screen.
        File raw = new File("../../app/src/main/res/raw");
        String[] clips = raw.isDirectory() ? raw.list() : new String[0];
        check("the app ships no sound clip of its own", clips == null || clips.length == 0);
        String app = "../../app/src/main/java/com/vrksignatures/sudhaautobot/";
        check("no voice player and no Veena player",
                !new File(app + "VoicePlayer.java").exists() && !new File(app + "VeenaPlayer.java").exists());
        check("the headline", FeedbackCues.COMPLETE_HEADLINE.equals("Kaam complete hua")
                && FeedbackCues.COMPLETE_SUBLINE.equals("Aapka kaam complete hua."));

        AakritiChat.Facts facts = new AakritiChat.Facts();
        facts.placed = 118;
        facts.skipped = 5;
        facts.toCheck = 2;
        facts.handled = 125;
        facts.expected = 300;
        facts.roster = "300 retailers (the app's own count)  ·  distributor Tuntun (SM41)";
        facts.distributor = "Tuntun (SM41)";
        facts.distributorBefore = "Ranjana Yadvendu (SM65)";
        check("status", AakritiChat.reply("Aaj ka haal?", facts).text.contains("118")
                && AakritiChat.reply("how many done", facts).text.contains("125 of 300"));
        check("roster", AakritiChat.reply("Kitne retailer?", facts).text.contains("300 retailers")
                && AakritiChat.reply("distributor kaun hai", facts).text.contains("Tuntun"));
        check("reset is explained", AakritiChat.reply("Reset se kya hoga?", facts).text.contains("safe"));
        check("nothing learned yet", AakritiChat.reply("kya seekha", facts).text.contains("zaroorat nahin"));
        facts.learned.add("\"copy order\" → \"Repeat Order\"");
        facts.waiting.add("\"place order\" → \"Submit Order\"");
        AakritiChat.Reply learned = AakritiChat.reply("Kya seekha?", facts);
        check("what was learned", learned.text.contains("Repeat Order") && learned.text.contains("Submit Order")
                && learned.action == AakritiChat.Action.SHOW_NOTEBOOK);
        check("no stop yet", AakritiChat.reply("kyon ruki", facts).text.contains("ruki hi nahin"));
        facts.lastStop = "Unrecognised screen in com.sudha.dailyorders";
        check("why it stopped", AakritiChat.reply("Kyon ruki?", facts).text.contains("Unrecognised screen"));
        check("no report, no share", AakritiChat.reply("report bhejo", facts).action == AakritiChat.Action.NONE);
        facts.hasReport = true;
        check("report shared on request", AakritiChat.reply("WhatsApp pe report bhejo", facts).action
                == AakritiChat.Action.SHARE_REPORT);
        check("the job", AakritiChat.reply("meri job?", facts).text.contains("Shaurya"));
        AakritiChat.Reply lost = AakritiChat.reply("what is the weather in Patna", facts);
        check("she says so when she does not know", lost.text.contains("scripted")
                && lost.action == AakritiChat.Action.NONE);
        check("empty and null are survived", !AakritiChat.reply("", facts).text.isEmpty()
                && !AakritiChat.reply(null, null).text.isEmpty() && !AakritiChat.greeting(null).isEmpty());
        for (String suggestion : AakritiChat.SUGGESTIONS) {
            check("\"" + suggestion + "\" is understood",
                    !AakritiChat.reply(suggestion, facts).text.contains("scripted"));
        }
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
