# Self-tests

Runs the app's real `CalendarDateSelector`, `CalendarGridFinder`, `DateRollbackPolicy`,
`ListScroller`, `RetailerSequence`, `AppNotices` and `FeedbackCues` on a plain JVM. No Android SDK, no
emulator, no third-party library. CI runs them before building the APK.

    bash tools/selftest/run.sh                      # all eight
    bash tools/selftest/run.sh ListScrollSelfTest   # one

## Calendar (`CalendarSelfTest`)

| Part | What runs | Against |
| --- | --- | --- |
| A | pixel reader | frames cut from the device's own recording (`fixtures/`) |
| B | pixel reader | painted calendars: 4/5/6-row months, three screen sizes, Sunday/Monday first |
| C | tree selector | a model of the stock date dialog, off-screen month page listed first and last |
| D | press → look → confirm | the same model: normal, click ignored, no day cells, month boundary, touches swallowed, day greyed out |
| E | reading T, then T-1 … T-5 | the same model opened on seven different dates (month, year and leap-day boundaries, dates years from the build machine's own): T is read from the selected day, the headline and the painted disc; the five dates are counted from it; T and later are refused; the calendar code contains no call that reads the clock |

Part A is the real screen. Parts B–D are a model built from the Android sources and the
measurements in `docs/REFERENCE-FLOW.md`; they show the logic is right for the dialog as
modelled, not how a particular phone reacts to a touch. Part D follows the same decision
order as `SudhaAccessibilityService.onCalendarLook()`, which itself needs Android to run.

* `stubs/` – minimal stand-ins for `android.graphics.Rect` and `AccessibilityNodeInfo`.
  Never packaged in the app.
* `src/StockDatePicker.java` – the dialog model. `src/Painter.java` – draws it as pixels.
* `fixtures/recording-day*.png` – the dialog card cut from the 720x1600 recording of
  18 Nov 2026, with the highlight on 18, 17, 16, 15 and 14; `recording-between.png` is a
  frame caught while the highlight was moving.

## Retailer list scrolling (`ListScrollSelfTest`)

| Part | What it shows |
| --- | --- |
| A | the previous scrolling code on the same models: where it stands still |
| B | every retailer is handled, in order, and the run ends — for 4 list behaviours × 6 list lengths × list staying put / reopening at the top × with and without a bottom bar × with and without the list running on after a swipe |
| C | a scrollable strip earlier on the screen and a tab pager are left alone; the bot panel lying on the list is avoided; the panel covering the whole list is reported, not treated as the end |
| D | the run ends at the very bottom; no drag exceeds 70% of the list height |

`src/RetailerListModel.java` is the model of the Customers screen. How the real list
answers a scroll request has not been observed, so that is a setting of the model
(scrolls / accepts and ignores / refuses / not marked scrollable) and every setting is run.

## Which retailer is next (`NextRetailerSelfTest`)

| Part | What it shows |
| --- | --- |
| A | the rule one screen at a time, including the case from the recording (`SMR2250` done, list back at the top: the old rule opens `SMR2255`, the new one scrolls and opens `SMR2249`) |
| B | that case as a whole run on the 125-retailer model: `SMR2249` down to the last, then one pass from the top for the five passed over |
| C | the same from eight starting points × 4 list behaviours × 6 list lengths × list reopening at the top / staying put × with and without the list running on after a swipe |
| D | the retailer handled last is no longer listed: one fresh pass from the top, then done |
| E | ids counting up, or in no order at all: only the order on screen is used |
| F | the wording of "Today Order already created" is recognised; the app's other messages are not mistaken for it |

`src/ListRunner.java` is the list loop shared with `ListScrollSelfTest`: a hand-written copy
of the order of decisions in `SudhaAccessibilityService.onRetailerList()`.

Not covered by any of this, because it needs the phone: that Android delivers the toast to
the service, and what the service does with it (`onAlreadyOrdered`, and the 15-second rule
in `onPendingScreen`). Those are type-checked and reviewed, not run.


## Cues, scenes and silence (`FeedbackSelfTest`)

| What it shows |
| --- |
| one retailer, start to finish: one cue (the order), every other step silent |
| runs of 1, 7 and 125 retailers: a cue per order, the ending once, never a stop |
| repainting the same state fifty times is silent; a panel that first appears in a finished or stopped state says nothing |
| a success screen looked at twice is one cue; after a ledger reset the first order is cued again |
| the words printed on the panel are no wider than the widest state name |
| `ErrorPlaybook` is empty |
| none of the run-logic classes names a feedback or music class, and none of them opens a network connection; in the whole app only `MusicEngine` and `YouTubeAudio` do |
| silent UI: no class uses `MediaPlayer`, `SoundPool`, `ToneGenerator`, `TextToSpeech`, a touch sound or a bundled clip; `res/raw` is empty; the panel and the setup screen switch touch sounds off; notifications have no sound and no vibration; there is no welcome |
| no maker's mark in any source or resource file |

Not covered, because it needs the phone: what the screens look like, and that the phone
really stays quiet.

The same test also checks that a run's start is marked exactly once, that
carrying on after a pause is not a new run, and that Aakriti's own classes obey the same
"no feedback class, no network" rule as the run logic.

## Aakriti's learning (`MoldingSelfTest`)

Runs the real `UiMolder`, `UiVocabulary`, `LearnedUi`, `ScreenLabels` and `AccessibilityTree`.

| What it shows |
| --- |
| with an empty notebook the bot's text comes back as *the same object*, and no label has a second name |
| on today's screens (home, list, details, history, form) every word is present and nothing is learned, from any state |
| 17 invented renames ("Copy Order" → "Repeat Order", "Customer Details" → "Retailer Profile", "Milk" → "Doodh", "Total Customers: 125" → "Total Parties: 125" …) are each learned, written down without their figures, and then found by the bot under the old name |
| a new name for PLACE ORDER is proposed, not used; it is still unused after a restart; it is used only after `allow`, and gone after `forget` |
| "Cancel Order", "Delete Order", "Pay Now", "Proceed", "Order Done" … are never taken for PLACE ORDER or Copy Order; "Order placed" is never taken for "Order created on" |
| the payment screen, the success screen, a login page, a crash dialog and a wholly redesigned home teach nothing; a redesign is reported as "NOT recognised" with every word missing |
| two candidates nearly as close as each other: neither is taken; a label that is not pressable is not a button |
| on a payment or success screen, and while the bot is paying, the text is returned exactly as printed even with a full notebook |
| the notebook survives save / load; a damaged file is an empty notebook; a known word cannot be given a second meaning |
| the code Reset runs (`resetLedger`, `resetRunCounters`, `DailyLedger`, `WorkflowStateMachine`) never mentions the notebook; only her own screen can forget everything |
| every word in `UiVocabulary` really occurs in the service's source, as do the four "could not find" stops she restarts after; the three hooks are fenced with `catch (Throwable)` |
| the end-of-run card says "Kaam complete hua / Aapka kaam complete hua."; no sound clip is in `res/raw`, and the voice and Veena players are gone |

## The roster (`RosterSelfTest`)

| What it shows |
| --- |
| the id rule: `SMR2196`, `smr1` are retailers; `SM65`, `SM41`, `65` are distributors; names such as "Asmita" are neither; nothing called a distributor is ever a row the bot may open |
| the expected count follows the app's figure for 125, 300, 40 and 999; "Total Customers: 2" on the collapsed list is ignored; with no figure at all the count is never below the ids seen |
| Ranjana Yadvendu (SM65) → Tuntun (SM41) is noticed and remembered |
| the bot's real list loop, with the setup figure left at 125, over lists of 40, 125, 126 and 300 whose header counts the retailers, counts the distributor too, or says "2": every retailer opened exactly once, the distributor never, at most one extra pass |

Not covered by either, because it needs the phone and a real update of the Sudha app: that
her reading of a changed screen is right in practice, the picture she takes, the
notification, and the hand-over to WhatsApp.

## Carrying on (`StartPointSelfTest`)

| What it shows |
| --- |
| typing: `2244`, `smr 2244`, `SMR-2244` all mean SMR2244; letters, `SM65`, seven digits mean nothing |
| the calendar date decides, not a count of hours: saved at 00:00 and looked at 23:59 the same date is kept; saved at 23:59 and looked at 00:01 is gone; a history from another date is wiped whole; one with no date yet (an older version's) is not |
| midnight is found from every time of day (Asia/Kolkata), to the second |
| a ledger left over from yesterday puts nothing into today's history; the service is told when the date changes and sets a timer for midnight as well; the ledger is not touched |
| pause after ten retailers and come back: over lists of 40, 125 and 300 the first retailer opened is the eleventh, none is opened twice, the list is walked once |
| "2244" typed after a Reset: the first retailer opened is SMR2243, the twelve rows above are recorded as done before, one pass |
| a number that is not in the list: one restart from the top, all 125 opened, nothing recorded as done |
| the bot's own code calls the history in exactly four fenced places |

The list here is a model of the service's loop (look at the rows, let the start point look
first, ask `RetailerSequence`, open or scroll), not the service itself.

## Music (`MusicSelfTest`)

| What it shows |
| --- |
| a run, frame by frame: nothing on the first frame or on START; upbeat on the first order, carried through later ones; sad on an error; upbeat again on the next order; an order and an error together count as the error |
| pause fades out once; the finished list fades out; a stopped run plays the sad song once |
| what is searched for: several different lines per mood, all 80s / 90s Hindi film music, the sad ones sad or tense, words and never an address |
| Auto-Dialogues, frame by frame: nothing on the first frame; a rousing dialogue for every order placed; a sad one for every error and once for each stop; nothing on pause; switching between songs and dialogues in the middle of a run |
| a dialogue being heard is not cut short by good news; bad news cuts a cheer; a song gives way |
| what a dialogue is: 4 to 75 seconds, "dialogue" in the title (English or Hindi letters), not a song, mimicry, reaction, collection, trailer; heard for at most 20 seconds |
| the player says one, at full loudness, once, never on repeat, and nothing follows it; off until switched on; the bot's own code knows nothing of it |
| what counts as a song: 100 s to 9 min, with a title; jukebox, nonstop, mashup, remix, karaoke, lofi, cover, "best of", full movie, trailer and live broadcasts do not |
| which stream is taken: the best at or under 140 kbps, otherwise the smallest; a known size before an unknown one |
| links: one YouTube song in eleven spellings (watch, `youtu.be`, Shorts, embed, live, YouTube Music, with extra parameters) is accepted and reduced to the same address; a channel, a playlist, a search page are refused with the way out; Spotify, Gaana, JioSaavn, Wynk, Apple / Amazon Music, Hungama, SoundCloud, Instagram and Facebook are refused with the reason; `http`, `ftp`, bare names are refused; direct `https` files and streams are accepted |
| wiring: the bot's own code does not mention the music; the player is ExoPlayer with the picture track switched off and nothing to draw on; only sound streams are asked for; a locked song is on repeat and survives a change of mood; the internet is asked off the main thread; no song address is in the source; the build has ExoPlayer, NewPipe Extractor, desugaring and JitPack |

Not covered, and not coverable here: **YouTube itself.** Whether the search answers, a
stream address is handed out and the song plays needs a phone and the internet, and can
change on any day YouTube changes. The cross-fade as heard and the keyboard on the floating
panel also need the phone.

## Speed, the skipped list, the ring and the glass (`SpeedSelfTest`)

| What it shows |
| --- |
| folded, the panel is a bare ring: no text, no picture, no fill, two hairlines no thicker than 1.5dp in see-through colours; the window has no background and no padding |
| a tap on the ring opens the dashboard, a drag moves it; opened at the edge of the screen it is moved in to be whole |
| open, it is glass: five steps from 8% to 66% see-through, 40% by default; the words are never thinned or recoloured, they get a pale edge; by arithmetic the print passes 4.5 to 1 over a black screen at the default step |
| every line shown on screen says Sutara, none says Aakriti or AutoBot; the package and the component names are unchanged |
| the scroll memory's plan: the counted scrolls first; found → nothing more; only handled rows in sight → on down; otherwise up once, down twice, then up one at a time, reading the rows after each; it stops at the top of the list and after a bounded search |
| a list of 125 walked with and without the memory: the same retailers in the same order, nobody twice, the rows read 269 times instead of 1,434; also lists of 1 to 40, a list inside a pager with a sideways strip above it |
| the count wrong by 1, 2, 3 or 6 either way, nine rows vanishing from or appearing in the list, the retailer handled last gone from the list: every retailer still opened exactly once, in order |
| lists that only move when dragged: nothing is sent by count and the drags are exactly those made before |
| START again later in the day: the next retailer is opened within a few looks |
| the wait for the copied order: the form rewritten and steady on two looks → go (about half a second); nothing changes → the old wait in full; the Date box changing, in ten ways of writing a date, is not an order; "Loading", a refusal written on the form, a form without PLACE ORDER, a form caught half-written are not an order |
| one line of the skipped file: date, time, retailer, why, what the app said; always one line |
| wiring: where each hook sits, that each is fenced, that the ordinary scan still only goes down, that the list is scrolled up in one place only, that the ledger is written before the file |
| the sound row on the dashboard: its words say nothing of the bot; Play / Pause; paused by hand it stays paused; Start folds the panel; the question box and the "Last done ID" box take the keyboard only while the bot is not running |

Not covered: the phone. How long one scroll of the real list takes, whether the real form's
writing changes the way the model's does when an order is copied, how the ring and the glass
look over a real screen and whether the words read well there all need the device.

## Fast mode (`TempoSelfTest`)

`Sim` in this test is a hand copy of how the service books its looks (the 900 ms timer, the
320 ms pause re-started by every change, the 420 ms gap), on a made-up clock, with fast
mode's peeks on top. `Tempo` is the app's own class.

| What it shows |
| --- |
| which screens are a different page: another window, or under half the words shared; a ripple, a tick, a spinner, the copied rows arriving, the list moved by one row are the same page |
| each floor on its own: the gap after a press, the slower rhythm after a look that pressed nothing, the window animation at the phone's speed (off, half, normal, three times), the bot's own pause as the ceiling |
| a press that opens a page: 990 ms before, 420 to 430 ms now; never before the page is there, never inside the gap after the press, two looks never closer than the gap |
| the same page changed a little, at 22 moments and three kinds: the same looks at the same times as before, to the millisecond; the `+` button keeps its full beat |
| waiting for the server, behind a spinner or a Loading box, answers from 0.3 to 12 s: never later than before |
| pages that come up in one piece: never later; pages that stop half-written: how often it is later, and by how much (printed) |
| a screen that never stops changing: handled as before, with a bounded number of peeks |
| wiring: the old timers and the double-press guard are untouched; the peek presses nothing; it stands aside for the copy check, the scroll memory and an order being placed or paid for; any fault switches it off; no `Thread.sleep` in the app |

Not covered: the phone. How long the Sudha app's pages really take, whether they come up in
one piece, and how its animations behave under an early press are not known here. The times
printed are the model's.

## "Orders list is empty" (`NextRetailerSelfTest`, section G)

| What it shows |
| --- |
| the words from the recording, "Orders list is empty. Retry?", with or without the `YES` button and the page title, in any case and spacing, are recognised; so are the singular and the form without "is" |
| a page with an order card, the order form, a payment page or a "please wait" box is never taken for it, whatever else is on it |
| the white "New Order Milk" page is recognised as blank; the same title with anything else on the page is not |
| in the service: the check runs before the screen dispatcher, is skipped while an order may be on its way, records `SKIPPED_NO_HISTORY`, presses Back, and presses neither `YES` nor `+` |

Not covered: the service acting on it on the phone.
