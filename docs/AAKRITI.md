# Aakriti — how the bot adapts, counts the roster, and asks for help

> **On screen she is called Sutara** since November 2026, and so is the app. The classes,
> the files and this page keep the name Aakriti; where this page says Aakriti, the phone
> says Sutara.

Aakriti is the name and face of the bot's helper. This page is the architecture: what was
added, where it touches the bot, and — just as important — where it does not.

**Read this first.** "AI" here means *rule-based matching that runs on the phone*: word
families (customer = retailer = party…), spelling distance, and a set of safety rules. It
is not a language model, it does not use the network, and it does not look at pixels to
understand a screen. It handles **renamed labels**. It does not handle a redesign that
changes *how* the app works (a new step, a moved button with no text, a different flow);
for that it stops, writes a report with a picture, and asks a person.

## The pieces

```
                      ┌──────────────────────────────────────────────┐
                      │  SudhaAccessibilityService (the bot, unchanged│
                      │  apart from three fenced hooks)               │
                      └───────┬───────────────┬──────────────┬───────┘
          every look: text    │               │ a stop        │ one retailer given up
                              ▼               ▼               ▼
                      ┌──────────────────────────────────────────────┐
                      │  Aakriti  (coordinator; Android)              │
                      │  see()        afterStop()   afterRetailerError│
                      └──┬────────┬──────────┬───────────┬───────────┘
                         │        │          │           │
              ┌──────────▼─┐ ┌────▼─────┐ ┌──▼────────┐ ┌▼───────────────┐
              │ LearnedUi  │ │ Roster   │ │ UiMolder  │ │ report + picture│
              │ notebook   │ │ AccountId│ │ UiVocabul.│ │ AakritiNotifier │
              │ (pure Java)│ │ (pure)   │ │ (pure)    │ │ ReportProvider  │
              └────────────┘ └──────────┘ └───────────┘ └────────────────┘
```

| Class | What it is | Android? |
| --- | --- | --- |
| `AccountId` | The id rule: contains `SMR` → retailer; contains `SM` or is digits only → distributor. | no |
| `Roster` | Today's roster as read off the screen: the app's own "Total Customers" figure, the ids seen, the distributor and any change of distributor. | no |
| `UiVocabulary` | A table of the words the bot relies on, screen by screen — a copy of what the service looks for, checked against the service's source by a self-test. | no |
| `UiMolder` | Given the screen the bot expected and the labels actually showing, works out whether one of those words has been renamed. | no |
| `LearnedUi` | The notebook: the new names, saved to a file, and the two questions the bot asks it (`translate`, `same`). | no |
| `ScreenLabels` | Reads labels and "can it be pressed" off the accessibility tree for `UiMolder`. | tree only |
| `Aakriti` | Ties them together; decides between "learned it, carry on" and "report to a person". | yes |
| `AakritiNotifier`, `ReportProvider` | Notifications; a read-only provider so a report can be attached to WhatsApp or any other app. | yes |
| `AakritiChat`, `AakritiDesk` | The scripted chat and her card / notebook on the setup screen. | chat: no |

## Where it touches the bot

`git diff` on the service is the whole story — three hooks, each wrapped in
`try { … } catch (Throwable)` so that if Aakriti fails the bot behaves exactly as before:

1. **`aakritiSees(text)`** at the top of `inspect()`. The bot's screen text goes to
   `Aakriti.see()`, which (a) lets the roster count, and (b) returns the text the bot should
   judge. With an empty notebook that is *the same string object* — the self-test checks
   identity, not equality. It also sets `expectedCount` from the roster.
2. **`aakritiAfterStop(reason)`** at the end of `fail()`. After the stop has fully
   happened, she studies the screen.
3. **`aakritiAfterRetailerError(reason)`** where one retailer is abandoned and the run goes on.

`AccessibilityTree` gained two fallbacks, both inert with an empty notebook:
`equalsLabel` also accepts a learned name for the label asked for, and `findContaining`
also searches the translated label. These are what let `tapLabel("PLACE ORDER")` find a
button that now says "Submit Order" — once that name is in the notebook *and allowed*.

Nothing else in the service changed: no wait, no order of checks, no rule about when to
press what.

## Auto-molding: what happens when the Sudha app renames something

1. The bot does not find a word it relies on, and stops (or gives up one retailer).
2. `UiMolder.study(state, labels)` takes the screen the bot's state expects and, for each
   word of that screen that is missing, looks for **one** label that means the same.
3. A new name is believed only if **all** of these hold:
   * the screen is still recognisably the right one by its *other* words (or two renames
     on it agree with each other), and shows none of the words of a different screen;
   * the match scores at least 0.65 and beats the runner-up by 0.15;
   * the label has no word like *cancel, delete, back, edit, pay, proceed, done, success*;
   * for a button, the label belongs to something that can be pressed.
4. A believed name is written to the notebook. From then on `LearnedUi` puts it back to
   the word the bot knows, so the bot's own, unchanged logic carries on.
5. If the stop was a "could not find / do not recognise" stop and something was learned,
   the run is started again 1.5 s later by the same code as the START button — at most
   three times a day, and only if nobody touched anything in between.

After an "unrecognised screen" stop the bot's state says little about which screen it is
on, so `UiMolder.studyAny` tries every screen and believes a rename only if exactly one
screen explains what is showing.

### What is never learned automatically

* **PLACE ORDER.** A new name for the one button that spends money is only *proposed*. It
  sits in the notebook marked "waiting for your OK", a notification asks, and it is not
  used until a person presses **Allow** (and confirms).
* **Payment and success.** These screens have no entry in `UiVocabulary`; nothing about
  them can be learned. In addition `LearnedUi.forBot` returns the text exactly as printed
  whenever the bot is paying / has just succeeded, or the screen mentions "payment" or
  "success" — so nothing learned elsewhere can colour how those screens are read.

## Smart reset

| | Where it lives | Cleared by Reset? |
| --- | --- | --- |
| Today's ledger (placed / skipped / errors, in-flight marker), run counters | the ledger file, service memory | **yes** — unchanged |
| Learned names | `files/aakriti/learned.txt` | **no** |
| Roster, distributor | `files/aakriti/roster.txt` | **no** |
| Last stop, Sudha version seen | preferences `aakriti_memory` | **no** |
| Reports and pictures (6 kept) | `files/aakriti/reports/` | **no** |

Reset's code was not changed; it simply never mentions any of Aakriti's storage, and a
self-test reads the source to keep it that way. Learned names are erased only by
**Forget** on one entry or **Forget what Aakriti learned** in her notebook.

## Human fallback

When a stop cannot be explained by a rename she can trust:

1. `files/aakriti/reports/report-<date>-<time>.txt` is written: what the bot was doing,
   the reason it stopped, the Sudha app's version (and whether it was updated), the roster,
   each word she looked for with the closest thing she found and why it was not trusted,
   and every label on the screen with whether it can be pressed.
2. A picture of the screen is taken (Android 11+; the full-screen drama scene is held back
   1.6 s so the picture shows the Sudha app).
3. A notification says what happened, with **WhatsApp** and **Share** actions. Either
   opens the share sheet with the picture, a short caption, and the text file attached.

**The app never sends anything by itself.** WhatsApp has no way for an app to send a
message silently; the person picks the chat and presses send. If notifications are denied,
the report is still in *Aakriti's notebook → Last report*.

## Dynamic roster

* **How many retailers:** the list prints "Total Customers: N". `Roster` takes that figure
  only while at least three retailer rows are on screen (the collapsed list prints
  "Total Customers: 2", which is not the count) and only if it is not below the number of
  different retailer ids already seen. Otherwise the setup figure is used, raised to the
  number seen. The run still *ends* where it always did — at the bottom of the list — so a
  wrong figure costs at most one extra pass, never a missed retailer.
* **Who is who:** `AccountId.kindOf`. `SMR` is tested before `SM`, since every `SMR` id
  contains `SM`. Which rows the bot may *open* is still `RetailerPolicy.isRetailerId`
  (exactly `SMR` + digits), unchanged.
* **Distributor:** read from the "Distributor Name ( SM65 )" line of the details screen,
  with the name printed beside or under it. A change (Ranjana Yadvendu → Tuntun) is noted
  and shown in the notebook; nothing in the run depends on the distributor's name.

## The panel and the end of a run

* **Panel** (`res/layout/floaty_layout.xml`, `FloatingPanelController`): Aakriti's face
  with a status light, her mood line, state and retailer; a progress line; three counts;
  **Ask Aakriti** (the day's figures in one line while working; opens the chat when idle)
  and **Lock song**; the message; the *Last done ID* box; one filled button and three quiet
  ones. `boundsOnScreen()` — the one thing the bot reads from the panel — is untouched.
* **Silent.** She no longer speaks: the welcome, the Veena and the film lines of the finale
  were removed, with their clips and players. `RUN_STARTED` now only gets the first songs
  ready; `ORDER_PLACED` shows "Order placed" on the panel; `RUN_COMPLETE` shows the card
  "Kaam complete hua / Aapka kaam complete hua." with the day's figures, a toast and a
  notification, none of which makes a sound. The music while working is separate, see
  [`HISTORY-AND-MUSIC.md`](HISTORY-AND-MUSIC.md).

## Tests

`tools/selftest`: `MoldingSelfTest` and `RosterSelfTest` run the
real classes on the JVM. They cover the rules above; they cannot cover the Sudha app
itself. See `tools/selftest/README.md`.
