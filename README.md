# Sutara — Smart Dairy Automation

> **The name.** On the phone the app, its Accessibility entry and its helper are all called
> **Sutara** (सुतारा). It was *Sudha AutoBot* with a helper called *Aakriti* until
> November 2026. Only what is shown on screen changed: the package
> (`com.vrksignatures.sudhaautobot`), the class names (`Aakriti*`, `SudhaAccessibilityService`)
> and the files on the phone are the same, so the new APK installs over the old one, stays
> switched on in Accessibility and keeps its notebook and ledger.

Accessibility-service bot that automates the repetitive daily order-entry task inside
the third-party **Sudha Daily Orders** app (`com.sudha.dailyorders`, also seen as
`com.vrksignatures.sudhadairy`).

The user presses **▶ START** on a floating panel; the bot then walks the whole retailer
list on its own, copies the most recent order from the previous 3 days, and places the
order through the payment chain — without asking for confirmation at every step.

* Java only, `minSdk 24`. The bot itself uses the phone's own APIs and nothing else; two
  libraries are added for the music alone (ExoPlayer and NewPipe Extractor, see
  [`docs/THIRD-PARTY.md`](docs/THIRD-PARTY.md)).
* Music while it works, streamed from YouTube as **sound only**: upbeat 80s / 90s Hindi songs
  while orders go through, sad ones when something fails. Nothing to set up. Otherwise the
  app is **silent**: no voice, no chime, no click — see [Look, music and scenes](#look-music-and-scenes).
* Per-day JSON ledger so a pause, restart or crash never processes a retailer twice.
* Pre-submit marker flushed to disk before `PLACE ORDER` so an order is never placed twice.

---

## Repository layout

```
.
├── .github/workflows/build-apk.yml      # CI: builds the debug APK on push to main
├── build.gradle.kts                     # AGP 8.7.3, declared only
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app
    ├── build.gradle.kts
    └── src/main
        ├── AndroidManifest.xml
        ├── java/com/vrksignatures/sudhaautobot/
        │   ├── MainActivity.java               # the app's one screen: today, setup, settings
        │   ├── SudhaAccessibilityService.java  # the bot loop (screen state machine)
        │   ├── AccessibilityTree.java          # node search / click / scroll helpers
        │   ├── WorkflowStateMachine.java       # current state + active retailer
        │   ├── BotState.java
        │   ├── DailyLedger.java                # per-day JSON ledger + pending marker
        │   ├── LedgerStatus.java
        │   ├── DateRollbackPolicy.java         # the strict T-1 / T-2 / T-3 window
        │   ├── CalendarDateSelector.java       # taps one exact day in the dialog
        │   ├── FloatingPanelController.java    # overlay panel with START/PAUSE/Reset
        │   ├── RetailerPolicy.java             # SMR<digits> only, never SM65 / 65
        │   │
        │   │   # --- carrying on where the last run stopped (docs/HISTORY-AND-MUSIC.md) ---
        │   ├── StartPoint.java                 # the rules: today's date only, "2244" -> SMR2244
        │   ├── RunHistory.java                 # SharedPreferences "run_history"
        │   │
        │   │   # --- Aakriti: adapting to renamed labels, the roster, asking for help ---
        │   │   #     (docs/AAKRITI.md; the bot reaches her through three fenced hooks)
        │   ├── Aakriti.java                    # coordinator: learn and carry on, or report
        │   ├── UiVocabulary.java               # the words the bot relies on, per screen
        │   ├── UiMolder.java                   # "has this label been renamed?" (rules)
        │   ├── LearnedUi.java                  # the notebook of new names (survives Reset)
        │   ├── ScreenLabels.java               # labels + pressability off the tree
        │   ├── AccountId.java                  # SMR = retailer; SM / digits = distributor
        │   ├── Roster.java                     # how many retailers today, who distributes
        │   ├── AakritiNotifier.java            # done / learned / needs your OK / stuck
        │   ├── ReportProvider.java             # hands a report to WhatsApp or any app
        │   │
        │   │   # --- look, music and scenes: nothing below is read by the bot loop ---
        │   ├── MagicUi.java                    # palette, type, card, three kinds of button
        │   ├── MusicMood.java                  # when the song changes; what is searched for
        │   ├── SongBook.java                   # the songs found, own songs, the song lock
        │   ├── YouTubeAudio.java               # YouTube search -> a sound-only stream
        │   ├── MusicEngine.java                # streams and cross-fades (ExoPlayer)
        │   ├── FeedbackCues.java               # which change of state earns a (silent) cue
        │   ├── FeedbackPrefs.java              # the two switches, and the own picture
        │   ├── AakritiChat.java                # her scripted answers on the setup screen
        │   ├── AakritiDesk.java                # her card and notebook on the setup screen
        │   ├── SceneView.java                  # the card shown when a run ends
        │   ├── CinematicOverlay.java           # puts a scene on screen
        │   ├── CustomMedia.java                # own picture for the end-of-run card
        │   └── ErrorPlaybook.java              # empty: where future problems get named
        └── res/
            ├── layout/floaty_layout.xml
            ├── values/strings.xml
            ├── values/themes.xml
            ├── xml/accessibility_service_config.xml
            ├── values/colors.xml               # the palette (same values as MagicUi)
            ├── drawable-nodpi/aakriti.webp     # Aakriti's logo, the fist (source: tools/art/src/aakriti_fist.webp)
            └── drawable/panel_*.xml, ic_*.xml  # panel shapes, line icons, launcher icon
```

## Build

### On GitHub (no local toolchain needed)

1. Create an empty repository and push this folder to `main`.
2. `.github/workflows/build-apk.yml` runs automatically and provisions JDK 17,
   Gradle 8.11.1 and the Android SDK (`compileSdk 35`). The two music libraries are
   downloaded from Google's Maven, Maven Central and JitPack.
3. Download the APK from **Actions → Build APK → Artifacts → `SudhaAutoBot-debug`**,
   or from **Releases → latest** (`SudhaAutoBot-debug.apk`).

You can also trigger it manually with **workflow_dispatch**.

### Locally

```bash
# Android Studio: open the folder, let it sync, then Run.
gradle assembleDebug          # or ./gradlew assembleDebug in Android Studio
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`debug` builds are signed with the standard Android debug keystore, so the artifact is
installable even though it is not release-signed.

## First-time setup on the phone

1. Install the APK and open **Sudha AutoBot**.
2. Under **Settings**, check the target package (default `com.sudha.dailyorders`) and tap
   **Save settings**.
3. Under **Setup**, three rows, each a tick once it is done: allow *Display over other
   apps*; turn on the accessibility service (Settings → Accessibility → *Installed
   services* → **SudhaAutoBot** → On); open **Sudha Daily Orders** and leave it on its
   home screen.
4. Press **Start** on the floating panel (or **Start today's orders** in the app).
5. Music needs no setup. To switch it off: *Music while working* → the switch.

On **Oppo / Vivo / Realme / Xiaomi**, additionally allow *Auto-start*, *Background
activity* and lock the app in the recents screen; otherwise ColorOS/FuntouchOS will kill
the accessibility service mid-run.

## What the bot does

| Step | Screen | Action |
| --- | --- | --- |
| 1 | Home (`Daily Orders`) | Tap **Customers** |
| 2 | Customer list | Count visible retailers; if ≤ 2 or no `SMR` ids, tap **Show Retailers** until ~125 rows appear (the checkbox's `isChecked()` is never read) |
| 3 | Customer list | Scan for `SMR<digits>` only; skip distributor ids (`SM65`) and bare numbers; tap the first unprocessed row; scroll forward when all visible rows are done |
| 4 | Customer details | Verify the header shows the **same** SMR id, then tap **Milk** |
| 5 | Milk history | Tap the floating **+** (FAB) — physically, at its bounds centre (see below) |
| 6 | New Order Milk | Tap **Copy Order** immediately — the form is always empty here and is never inspected |
| 7 | Calendar | Tap the **T-1** day cell, then **OK**. Today is never selectable |
| 8 | Calendar | If the dialog says *"No order to copy from selected date"*: tap its **OK**, then repeat with T-2, T-3, T-4, T-5 (max 5 days, never a week) |
| 9 | Order form | When **no dialog** appears within ~3 s: assume the order loaded → mark pending → **PLACE ORDER** → **PAY** / **PROCEED** / **CONFIRM** / **OK** |
| 10 | Success | Record `SUCCESS`, tap **Done** (or Back), return to the list, re-expand if it collapsed, continue with the next SMR |

When all five dates fail, the final dialog is dismissed, `GLOBAL_ACTION_BACK` leaves the
order form, the retailer is recorded as `SKIPPED_NO_DATA`, and the bot moves to the next SMR.

### The copy flow is driven entirely by the dialog

Success is never inferred from the order items. The app reports a failed copy with an error
dialog and reports success by simply returning a filled form, so:

* **dialog present** → that date has no data → `OK` → next older date;
* **no dialog after ~3 s** → the copy worked → `PLACE ORDER`.

Nothing in the item table is parsed. An earlier version summed numbers near the words
"total"/"qty" and read the `1000` in the item name `FCM 1000 ML` as a quantity, which made an
empty form look ready — the bug this design removes by construction.

## Verified against the real app

The strings below were read off a screen recording of the target app (1080x1920). They
are what the screen predicates now match on:

| Screen | Verified strings |
| --- | --- |
| Retailer list | `Total Customers: 125`, `All Distributors`, `Show Retailers`, `Customer Id: SMR2196` |
| Customer Details | `Customer Details`, `Name`, `Distributor Name ( SM65 )`, `View Orders`, tabs `Milk` / `PROD` |
| Order history | `Order created on 17 Nov,2026`, `Order Id`, `Products 6`, `Order Qty 22.0 Liter/Kg`, `< November,2026 >` |
| Order form | `Date`, `Item Group`, `Zone`, `Party`, `Copy Order`, `Item Rate MQty EQty Total`, `PLACE ORDER` |
| Payment | `Payment`, `Paid Amount`, `Payment Mode`, `CASH`, `Remarks`, orange `Pay` |
| Success | `Successfully Payment Created` + green `Done` |

Two traps this app sets, both now handled:

1. **The order-history screen is titled "New Order Milk"** — the *same* title as the order
   form. History is therefore detected by its content (`Order created on`, `Order Qty`) and
   is routed **before** the form in the screen dispatcher.
2. **"Order placed" appears on history cards** as the status of a *previous* order. It is
   deliberately **not** treated as a success signal, otherwise every retailer would have
   been marked SUCCESS the moment its history opened.

## How the floating "+" button is found

The button is painted by the app but exposes **no label, no view id, and is not reported
as clickable**. Its position therefore cannot be read from the accessibility tree, and a
dp-based guess depends on screen size, aspect ratio, insets, DPI and navigation-bar mode.

So the bot asks the screen instead. On Android 11+ it takes a screenshot
(`canTakeScreenshot`), finds the **orange disc** in the pixels, and taps its centre
(`FabFinder`). Nothing is stored or uploaded. If the screenshot is unavailable the bot
falls back to the node scan and then to fixed coordinates, so behaviour degrades rather
than breaking.

`FabFinder` accepts a blob only when it is roughly square, disc-sized (4.5–30% of screen
width), fills 30%+ of its box and sits in the bottom-right half of the screen. It first
matches this app's orange hue, then falls back to the dominant accent hue so a theme
change does not break it. A wide orange button such as `PLACE ORDER` is rejected by the
squareness test. Verified against 7 synthetic screens (including "no FAB", a beige card
and a row of small orange dots) with 0 errors.

## Tapping the floating "+" button

The add button on the order-history screen is a custom view: it does not run its click
handler for `performAction(ACTION_CLICK)` and may not report itself as clickable at all.
So `onHistory` escalates over successive screen passes rather than repeating one call:

| Attempt | What is tapped |
| --- | --- |
| 1–2 | Best node with an identity hint (`+` label, `fab` id/class, "add" text), tapped **physically at the centre of its bounds** via `dispatchGesture` |
| 3 | Native `ACTION_CLICK` on its clickable ancestor, as a second opinion |
| 4–5 | A shape + corner candidate (small square blob in the bottom-right) that has no text or id at all |
| 6–7 | Fixed coordinates: a FAB-sized square centred at **86.7% width / 90.2% height** (measured from the real device: 937px, 1732px on 1080x1920) |
| 8+ | A ladder of neighbouring corner positions (90/92, 83/88, 90/86, 82/93, 78/88) |

That is 10 physical tap attempts before the retailer is abandoned. Candidates are scored
so a wide list row or a header control can never win: position alone is only worth 50
points and needs corroboration, while a `+` label is worth 100.

If the form still never appears, the retailer is recorded as **`ERROR`** in the ledger and
the run moves on to a *different* SMR id — it never walks back to "Milk" for the same
retailer.

## Safety rules baked into the code

* **Only `SMR<digits>` is ever clicked.** `RetailerPolicy` anchors the pattern, and
  `AccessibilityTree` only treats a row as clickable when its subtree contains exactly
  that one id.
* **Header must match.** Opening `Milk` requires the details screen to show the same id
  that was tapped; otherwise the row is recorded as `SKIPPED_ID_MISMATCH` and the bot
  backs out.
* **Never twice.** Any ledger record (`SUCCESS`, `SKIPPED_*`, `ERROR`) removes the
  retailer from today's queue.
* **Never place twice.** Before `PLACE ORDER` the ledger is fsynced with a `pending`
  marker. A restart mid-payment refuses to re-tap `PAY` and stops for a human instead.
  That is the `IN_FLIGHT_UNVERIFIED` state shown in **MainActivity**.
* **An empty order list is skipped, not waited on.** When Milk opens to *"Orders list is
  empty. Retry?"* (or to the blank white page it leaves behind), the retailer is recorded as
  skipped, Back is pressed and the next SMR is opened. `YES` and `+` are never pressed there.
  See [`docs/REFERENCE-FLOW.md`](docs/REFERENCE-FLOW.md), §8.10.
* **Bounded everything.** Every wait has a tick budget; `Show Retailers` is tapped at
  most 4 times, Back at most 6, the payment chain at most 12 steps. When a budget runs
  out the bot *pauses with a precise reason* instead of guessing.
* **Package isolation.** No window outside the configured package is read or tapped.
* **The network is for music only.** `INTERNET` is used by `MusicEngine` and `YouTubeAudio`
  and by nothing else; `tools/selftest` fails the build if any other class opens a
  connection. Nothing about the orders, the retailers or the screen leaves the phone.

## Day boundary

The ledger file is `files/ledger-yyyyMMdd.json`. Starting a run after midnight creates a
new file, so yesterday's results never block today's orders. **Reset** clears the current
day, including an in-flight marker left behind by an interrupted run — without that, a
half-finished order would make every later START wait for a payment screen that never
appears.

## Carrying on where the last run stopped

Full write-up: [`docs/HISTORY-AND-MUSIC.md`](docs/HISTORY-AND-MUSIC.md).

**Faster, and easier to check afterwards** — [`docs/SPEED-AND-STEALTH.md`](docs/SPEED-AND-STEALTH.md):
the bot goes straight back down the list to where it was (scroll memory), goes on as soon as
a new page has come up instead of after a fixed wait (fast mode; a switch in Settings, on by
default, not used while an order is placed and paid for), presses PLACE ORDER as soon as the
copied order is in the form, keeps a text list of
the retailers it had to skip, and folds its panel into a bare see-through ring (open, the
panel is a sheet of glass with the app visible behind it). That page also says
why running the Sudha app out of sight is not in the app.

* **The day's history.** The retailer handled last and the scroll count are saved with the
  calendar date (`SharedPreferences` file `run_history`). They belong to that date and to
  nothing else: when the date changes, at midnight, the whole file is wiped, however few
  hours have passed, and the list starts from the top. No count of hours is involved.
* **Auto-Dialogues.** A second way of working for the sound, off by default: no songs; a
  rousing Hindi film dialogue found on YouTube when an order is placed, a sad or dramatic
  one on an error. The microphone button on the panel, or *Music while working* in the app.
* **"Last done ID" on the floating panel.** Shown whenever the bot is not running. Type
  `2244`, press **Start**: SMR2244 counts as done, the bot scrolls to it and opens the
  retailer after it. Rows above it are recorded as *done before* and are not opened.
* **A wrong number costs time, never an order.** Nothing is recorded as done until the
  typed retailer has actually been seen in the list. If it is never found, the bot goes
  back to the top and opens every retailer, as it did before this feature existed.
* **Reset** clears the history together with the day's ledger. The panel keeps a hint of
  what the number was ("Reset cleared it. Was SMR2244") but does not use it unless it is
  typed.

## Look, music and scenes

Everything in this section is presentation. The bot loop does not read any of it, and
`tools/selftest` fails the build if that ever stops being true.

**Look.** White cards on a pale grey page, one navy (the navy of Aakriti's blazer), and
three signal colours: green done, amber waiting, red needs a look (`MagicUi`,
`res/values/colors.xml`). Two weights of the system typeface. One filled button per screen.
No name or mark of the maker is shown anywhere.

**The app's screen.** From the top: *Today* (progress, where the next run carries on,
**Start today's orders**), *Setup* (three rows, each a tick or an alert), Aakriti's chat;
then, folded until tapped: her notebook, Settings, Music while working, End-of-run card,
Advanced.

**Floating panel.** Aakriti with a status light, state and retailer; a progress line;
`Placed · Skipped · Check`; **Ask Aakriti** and **Lock song**; the message; *Last done ID*
(only while stopped); **Start / Pause**, Reset, Capture, Hide. The bot asks the panel where
it is each time it plans a touch, so the layout is free to change.

**Music while working** — full write-up in [`docs/HISTORY-AND-MUSIC.md`](docs/HISTORY-AND-MUSIC.md).

| What happens | What is heard |
| --- | --- |
| an order is placed | an upbeat 80s / 90s Hindi song (one already playing carries on) |
| a retailer ends in an error | cross-fade to a sad / tense song, until the next order |
| the run stops on a problem | a sad song for 45 s, then quiet |
| pause, idle, list finished | fade out |

* The songs are **found on YouTube and streamed as sound only** (no picture is fetched or
  drawn), by ExoPlayer, with nothing to set up. The app carries no song and no song
  address — only search words such as "90s bollywood dance hits hindi songs".
* **Lock song** (the note on the panel): tap to keep the song now playing, through orders
  and errors, on repeat; tap again to release it. Press and hold to be told which song it is.
* Under *Music while working* in the app: the switch, what is playing (or why nothing is),
  **Try upbeat** / **Try sad** (half a minute each) and **Stop**. Optionally, your own songs
  per mood — a YouTube link to one song, a direct `https` sound link, or a file on the
  phone; while a mood has own songs, only those are played for it.
* It needs the internet (about 1 MB a minute on mobile data). Without it the bot works
  exactly the same, in silence.

**Silent otherwise.** The app has no voice, no chime and no click: there is no welcome, the
Veena and the film lines are gone (their files are deleted, not switched off), buttons make
no touch sound, and notifications arrive without sound or vibration. Milestones are shown,
not heard:

| Moment | On the panel | Full screen |
| --- | --- | --- |
| an order is placed | "Order placed" for 2½ s | — |
| the whole list is done | "Done" | a card: "Kaam complete hua / Aapka kaam complete hua.", with the figures; also a toast and a notification |
| the run stops on a problem | the outline turns red once | a card: "Run stopped", 6 s |

**Scenes.** Shown only once the bot has stopped driving the screen, so a scene can never
cover a button the bot is about to press or get into a picture it takes of the calendar.
Tap anywhere to close. The picture is Aakriti; under *End-of-run card* each of the two
cards can be given your own picture or GIF from the phone instead.

## Aakriti: adapting to app updates, the roster, asking for help

Full write-up: [`docs/AAKRITI.md`](docs/AAKRITI.md). In short:

* **Renamed labels are learned.** If an update of the Sudha app renames something the bot
  relies on ("Copy Order" → "Repeat Order", "Customer Details" → "Customer Info"), Aakriti
  works out the new name from the rest of the screen, writes it in her notebook, and the
  run carries on. This is rule-based matching on the phone (word families + spelling), not
  a language model, and when it is not sure it does not guess.
* **PLACE ORDER is never re-learned unasked.** A new name for it is proposed and waits for
  **Allow** in her notebook. Nothing about the payment or success screens is ever learned.
* **Reset keeps what she learned.** Reset clears today's ledger and counters only. Learned
  names have their own **Forget** buttons.
* **When she cannot adapt, she reports.** A text report (what she looked for, what she
  found, every label on the screen) and a picture of the screen, with a notification whose
  **WhatsApp** / **Share** buttons open the share sheet with both attached. The person
  chooses the chat and presses send — the app cannot and does not send by itself.
* **The roster is read, not assumed.** The number of retailers follows the app's own
  "Total Customers" figure (125, 300, 40…); *Retailers expected today* in Settings is now
  only the fallback. An id containing `SMR` is a retailer; one containing `SM`, or digits
  only, is a distributor and is never opened. A change of distributor is noticed and shown.
* **Ask her.** Her card on the app's screen answers a handful of questions (*Aaj ka haal? Kya
  seekha? Kyon ruki? Kitne retailer? Reset se kya hoga?*). She is scripted, and says so
  when she does not understand.

### Where future error handling goes

`ErrorPlaybook` is an empty list on purpose. When a new problem is reported: (1) add an
entry there so the drama scene names it in plain words; (2) put the handling itself in
`SudhaAccessibilityService` beside the screen it concerns, with a self-test.

## Known limitations

* The look, the music and the cards have been compiled and their rules self-tested, but
  **not watched or listened to on a phone** before release.
* **The music depends on YouTube staying as it is.** It is found and streamed through
  NewPipe Extractor, which reads YouTube the way a browser does; this is not an official
  YouTube interface. When YouTube changes something, songs can stop being found until the
  library is updated (one line in `app/build.gradle.kts`, see `docs/THIRD-PARTY.md`). The
  app then says why under *Music while working*, stops trying for ten minutes at a time,
  and the bot carries on without music. Using YouTube this way may also be against
  YouTube's terms of service.
* **"Orders list is empty"** is recognised by its words (taken from a screen recording) and,
  once the message has faded, by the blank "New Order Milk" page lasting four seconds. The
  rule is self-tested; the fix itself has not been watched on the phone.
* **Typing on the floating panel** needs the panel's window to take the keyboard for a
  moment. That is standard Android, but some phones (Oppo, Vivo, Xiaomi) treat overlay
  windows in their own way; if the keyboard does not appear, that is the place to look
  (`FloatingPanelController.beginEditing`).
* A song that has not started after 25 s, or fails, is skipped; nothing about the run
  waits for it.
* Aakriti adapts to **renamed labels**. A redesign that changes the flow itself (a new
  step, a button without text, a different order of screens) is beyond her: she stops and
  reports. Her matching has been tested against invented renames, not against a real
  update of the Sudha app, because there has not been one yet.

* Screen recognition is text-based (`daily order`, `show retailers`, `customer details`,
  `new order milk`, `no order to copy from selected date`, `place order`, `payment`, …). If your build of the target
  app uses different wording, adjust the `looksLike*` methods in
  `SudhaAccessibilityService`. The bot pauses instead of guessing, so a wording mismatch
  is a pause — never a wrong order.
* The bot never decides whether a copy worked by reading quantities. It uses the app's own
  signal: **no popup after the calendar's OK means the order loaded**. Quantities are
  deliberately ignored because the item name `FCM 1000 ML` was once parsed as a total,
  which made an empty form look filled. "Did anything appear?" is answered by comparing
  the form's text before and after the copy.
* Automating a third-party app may violate its terms of service. Use it on your own
  account, at your own risk, and keep an eye on the first few runs.
