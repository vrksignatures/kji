# Speed, the skipped list and the see-through panel

What was added in this round, how each part works, and what none of it has been through yet:
**a phone**. Everything below was checked on a computer against a model of the Sudha app
(`tools/selftest`, `SpeedSelfTest`) and by building the APK. Nothing here has placed an order.

**Third round (November 2026):** the history now belongs to the calendar date and is wiped
at midnight (section 4); the folded panel is a bare see-through ring and the open one is
glass (section 5); the app and its helper are called Sutara; Auto-Dialogues was added to
the sound ([`HISTORY-AND-MUSIC.md`](HISTORY-AND-MUSIC.md)). Asked for again and still not
done, for the reasons already given: 1 to 10 ms polling and a 10 to 50 ms wait for the
error dialog (sections 2 and 2a), and the Sudha app on a virtual display (section 6).

The order flow itself is as it was. Each part is an addition beside it, switched off by its
own failure: if it has nothing to say, or throws, the bot does what it did before.

---

## 1. Scroll memory

The Sudha app reopens the customer list at the top after every retailer. The bot used to come
down again one checked scroll at a time, reading every screenful on the way. For the 100th
retailer that is about twenty screens read for nothing.

Now, when a retailer is opened, the number of scrolls it took to reach it is saved with it
(`RunHistory`: `scroll_id`, `scroll_count`, `scroll_at`, `scroll_day`; thrown away after 24
hours or at midnight like the rest of the history). Next time:

1. that many scrolls are sent one after the other **without reading the rows**;
2. the rows are read once. If the retailer handled last is there, the ordinary rule opens
   the one after it.
3. If it is not there:
   * only handled retailers in sight → the place is further down; the ordinary scan carries
     on downward from here;
   * otherwise **up once**, look; **down twice**, look; then **up one scroll at a time**,
     looking after each, until it is found or the list will go no further.
4. If that finds nothing, the list is handed to the ordinary scan (which finds the place the
   old way) and the memory is not used again until the next START.

Which retailer is opened is still decided by `RetailerSequence` from the rows on screen. The
memory only moves the list. A wrong count costs time; it cannot open the wrong retailer.

On the model list of 125 retailers the rows are read 269 times instead of 1,434 for the same
orders in the same sequence.

Details worth knowing:

* The pause between two counted scrolls starts at 350 ms. If the list is seen to have fallen
  short (scrolls ran into each other), the pause grows by 200 ms, up to 950 ms, and stays.
* Lists that only move when dragged are never replayed by count.
* A typed "Last done ID" is followed down the old way (every row has to be seen so that the
  rows above can be marked done); the memory takes over from the retailer after that.

Code: `ScrollMemory.java` (the plan, plain Java), `ListScroller.jump` (the only place the list
is scrolled up), `SudhaAccessibilityService.scrollMemorySteps`.

## 2. The wait after the calendar's OK

Before: after OK the bot waited a fixed 3.6 seconds for the "No order to copy from selected
date" dialog, and pressed PLACE ORDER if it had not come.

Now the screen is looked at **every 150 ms**:

* the dialog is there → handled at once (OK, previous day, up to T-5, as before);
* the form has been **rewritten** since the calendar closed, and reads the same on two looks
  in a row → the order is in; PLACE ORDER is pressed. In the model this is about half a
  second after OK;
* neither → the old wait applies in full, and then the old rule.

"Rewritten" is judged without reading any quantity, total or item name: the writing on the
form is compared as a whole with what was there at the first look after the calendar closed,
with dates and clock times left out (choosing a date fills the Date box whether or not there
is an order).

**Why not simply "no dialog within 200 ms means success"?** The dialog comes from the server.
On a slow connection it can take longer than 200 ms, and pressing PLACE ORDER on a form whose
answer has not arrived yet is the one mistake this bot must not make. The rule above is as
fast when the order arrives quickly and never faster than the answer.

Safety catch: if the form is ever seen rewritten and the error dialog comes after all, the
quick check has misjudged this app. It is then switched off for 24 hours (the old wait is
used) and a line is written to the log.

Code: `CopyOutcome.java` (plain Java), `SudhaAccessibilityService.copyOutcomeWaits`.

## 2a. Fast mode: no fixed wait between pages

Asked for: no `Thread.sleep` anywhere, everything driven by screen events, a look every 10 ms
where a loop is needed, and an error dialog waited for no longer than 10 to 50 ms.

**What was there.** No `Thread.sleep`: the app never had one (`TempoSelfTest` now fails the
build if one appears). The bot was already woken by every change on the Sudha app's screen.
What made it slow was what it did next: it waited until the screen had been quiet for 320 ms,
and then another 420 ms. So after every press it stood still for about three quarters of a
second after the next page had finished drawing.

**What fast mode does.** After each change on the screen the bot now *peeks* 60 ms later:
it reads the screen and presses nothing. When two peeks in a row read the same, and what
they read is a **different page** from the one the bot last acted on, the look that was
coming is taken there and then.

* A different page is another window (a dialog, another screen) or the same window with
  more than half its words changed (the next page, the list after a scroll).
* The same page with a small change on it - a ripple, a tick, a spinner, the copied rows
  arriving - is left to the old timing. The bot's retries there (the `+` button above all)
  count on the full beat they always had.
* Never sooner than 420 ms after the bot's last press; 740 ms after a look that pressed
  nothing; and not while a new window is still animating in (350 ms at the phone's normal
  animation speed, nothing when animations are off - the bot reads the setting).
* Only while the bot's own 320 ms pause is still running. The look taken early is the one
  that pause was about to book, in its place - not an extra one.
* **Not at all from just before PLACE ORDER until the order is on record.** The
  confirmation and the payment keep the timing they always had. Money is not hurried.
* The scroll memory (section 1) and the wait after the calendar's OK (section 2) keep their
  own time; fast mode stands aside for them.

What a look *does* is not touched: it is the same code, run sooner. Settings → **Fast mode**
switches it off; it is read at Start. Any fault inside it switches it off for the run.

**In the model** (`TempoSelfTest`; a made-up clock, not a phone):

| | before | fast mode |
| --- | --- | --- |
| page complete 250 ms after the press → next press | 990 ms | 420 to 430 ms |
| eight page changes up to PLACE ORDER, waiting in between | 7.9 s | 3.4 s |
| pages that come up in one piece, 486 timings | - | none later, 227 earlier |
| pages that stop half-written (a hard grid, 810 timings) | - | 115 later, by at most 0.55 s |

The last line is the cost. A page that stops half-written for a moment may now be looked at,
found not ready, and caught complete a little later than before. Such a look also counts as
one look of whatever wait is running ("give up after 12 looks").

**Not done, and why.**

* *A look every 10 ms.* Reading the screen is a question put to the Sudha app, answered by
  the thread that draws it; one answer takes longer than 10 ms. Asking a hundred times a
  second would slow the app down, which is the opposite of what is wanted. Peeks are 60 ms
  apart, and only while the screen is changing; nothing is asked while it is still.
* *"No error dialog within 10 to 50 ms → PLACE ORDER."* The answer to Copy Order comes from
  Sudha's server. No server answers in 50 ms on a phone connection, so at 50 ms there is
  never a dialog - with an order or without one. The rule would press PLACE ORDER on an
  empty or half-filled form for exactly the retailers that have no order on that date. It
  stays as in section 2: PLACE ORDER is pressed as soon as the copied rows are seen in the
  form, which is as fast as the server and never faster.
* *Faster than 420 ms after a press.* That is the bot's own guard against pressing twice.

What is left after this is the Sudha app's and the server's own time. Code: `Tempo.java`
(plain Java), the `fast mode` block in `SudhaAccessibilityService`.

## 3. The list of skipped retailers

`files/skipped-smr.txt` in the app's own folder, one line per retailer:

```
2026-11-18 14:05  SMR2235  EMPTY LIST   The app said: Orders list is empty. Retry?
2026-11-18 14:09  SMR2231  NO ORDER T-1..T-5   No order to copy for T-1 to T-5
2026-11-18 14:20  SMR2212  COPY PROBLEM   No dialog, but PLACE ORDER never became available
```

It is written after the day's ledger, never instead of it, and is not cleared by Reset or by
a new day. To read it: **app → Advanced → Skipped retailers** (newest first, with Share and
Clear), or ask Aakriti "skip list?". It is trimmed to its newest 2,000 lines when it grows large.

Unchanged and re-checked: T-1 to T-5 before a retailer is skipped; "Orders list is empty.
Retry?" → Back, next retailer, never YES; "Today Order already created" → Back, next retailer.

## 4. History

The retailer handled last and its scroll count belong to **one calendar date**. START
carries on after it; typing a number in "Last done ID" on the panel (for example `1011`)
overrides it. When the date changes, at midnight, all of it is wiped at once, however few
hours have passed; there is no 24-hour timer. Details:
[`HISTORY-AND-MUSIC.md`](HISTORY-AND-MUSIC.md) section 1.

## 5. The panel

**Folded, it is a ring and nothing else.** No fill, no picture in the middle, no word: the
wallpaper or the app behind shows through it untouched. All that is drawn is two hairlines
a little apart, one dark and one light, so that it reads as a raised rim on a pale screen
and on a dark one (`res/drawable/mini_ring.xml`). The window has no background and no
padding in this state.

* Pressing **Start** folds it by itself.
* A **tap on the ring** opens the dashboard; a drag moves it. Opened at the edge of the
  screen, the dashboard is moved in just far enough to be whole, and the ring goes back to
  where it was when it is folded again.
* A tap on the dashboard's top strip folds it.
* The one hint of the bot: the hairline turns amber when the run has stopped and wants a
  look, and green when the whole list is done.

**Open, it is a sheet of white glass.** The app behind stays visible through it. By default
40% of what is behind shows through (the old panel let 2% through). *Floating panel: how
see-through* in the app moves that between 8% and 66% in five steps (`Glass.java`); it
takes effect the next time the panel is opened.

The words are **never thinned**: only the sheet behind them is see-through. Each word also
gets a pale edge, stronger the more see-through the sheet is, so that it can be read over a
dark or busy screen. Counted the way accessibility guidelines count it: over a white page
(the Sudha app) the print is as clear as on paper at every step; at the default step it
still passes the reading test (4.5 to 1) over a pure black screen without the pale edge.
At the two most see-through steps it does not, and there the pale edge does the work. That
is arithmetic, not a look at a phone: if it is hard to read on yours, move the slider left.

The dashboard holds everything: Start / Pause, Reset, the counts, the **sound row** (Play /
Pause, what is being heard, and the microphone button that switches between songs and
Auto-Dialogues), Lock song, the "Last done ID" box and the question box.

**Ask Sutara on the dashboard**: a box under the message line. She answers from the day's
figures, the last stop, her notebook and the skipped list. The box (like "Last done ID") is
there only while the bot is not running: a window that takes the keyboard takes the screen
away from the Sudha app, and the bot would lose its place. While it runs, the chat button
gives the one-line status as before. "Report bhejo" opens the app, where the report is shared
by WhatsApp or e-mail through the phone's share sheet. She is still a scripted assistant: she
does not send e-mail by herself and does not look at pictures.

**Her logo** is the fist (`res/drawable-nodpi/aakriti.webp`, source
`tools/art/src/aakriti_fist.webp`): on the open dashboard, in the chat, in notifications and
on the app icon. It is not on the folded ring.

**Her name is Sutara** on every screen, and so is the app's (see the README). The class and
file names still say Aakriti.

Music, the silent taps and the cross-fade between an upbeat and a sad song are as described
in [`HISTORY-AND-MUSIC.md`](HISTORY-AND-MUSIC.md).

## 6. Not done: running the Sudha app out of sight

Asked for: the Sudha app inside a virtual display or a one-pixel window, with the phone's
screen free for YouTube and calls. This round does **not** contain that, and no pretend
version of it was shipped. The reasons:

* **A virtual display.** An ordinary app may create one, but Android does not let it start
  *another company's* app on it. That needs system or ADB-level rights - a rooted phone, or
  Shizuku (wireless debugging, switched on again after every restart of the phone).
* **A tiny or invisible window.** An app in a window too small to see has buttons too small
  to press, and Android pauses apps that are not visible.
* **The phone's own pop-up / floating window** (Samsung pop-up view, Xiaomi floating window
  and the like) is the nearest thing that exists without root: the bot already looks for the
  Sudha app in every window, not only the front one. But two things the bot does go to
  whichever app is in front: the **Back** press after every retailer, and the taps it makes
  by touch (the orange "+"). With YouTube in front, Back would close YouTube. Making that
  work means changing how the bot leaves a screen - a change to the order flow, which has to
  be tried on the phone before it can be trusted.

What works today without any of this: a spare phone for the bot.
