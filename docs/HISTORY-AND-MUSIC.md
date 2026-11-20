# Carrying on where the last run stopped, and the music

Two additions. Neither changes how an order is placed: the list decision is still
`RetailerSequence`, the "Already created" fallback is untouched, and the bot's own code
does not know the music exists.

## 1. The day's history (wiped when the date changes)

### What is stored

`SharedPreferences` file **`run_history`** (`RunHistory`):

| Key | Meaning |
| --- | --- |
| `last_done_id`, `last_done_at`, `last_done_day` | the retailer handled last, the time, the local day (`yyyy-MM-dd`) |
| `start_id`, `start_at`, `start_day` | a number typed in the panel's box and not yet reached |
| `before_reset_id`, `before_reset_day` | what `last_done_id` was when Reset was pressed; shown as a hint, never used by itself |
| `scroll_id`, `scroll_count`, `scroll_at`, `scroll_day` | how many scrolls down the list that retailer is (the scroll memory) |
| `history_day` | the calendar date all of the above belong to |

### When it expires

**The calendar date decides, and nothing else.** There is no 24-hour timer.

* `RunHistory.expire()` compares `history_day` with today's date (`yyyy-MM-dd`, the phone's
  own clock and time zone). If they differ, **the whole file is cleared** in one go: last
  done, the typed start, the scroll count, the Reset hint.
* Saved at 23:59, looked at 00:01: gone, two minutes later. Saved at 00:01, looked at 23:59
  the same date: kept, nearly a day later. (`StartPoint.fresh`, `StartPoint.dayTurned`.)
* It happens **at midnight**, not only at the next look: the service listens for Android's
  "the date has changed" broadcasts (midnight, the clock being set, the time zone being
  changed) and also sets its own timer for just after midnight
  (`StartPoint.untilMidnight`). `expire()` still runs before every read as well, so a phone
  that was switched off over midnight is covered too.
* A run that is going on across midnight is not interrupted; only what was remembered for
  the next Start is gone. The day's ledger is not touched by any of this.
* One hole was closed at the same time. The service stays alive overnight holding
  yesterday's ledger until the next Start makes a new one. A refresh of the panel in
  between could copy yesterday's "handled last" into today's history, and the next Start
  would then have begun below it. `RunHistory.note` now takes nothing from a ledger whose
  date is not today's.

The copy check's safety catch ("switched off for 24 hours when it has misjudged this app",
see `SPEED-AND-STEALTH.md` section 2) is a different thing, is not history, and is unchanged.

### What happens on Start

`RunHistory.atStart` (called from `startRun()`):

1. An order is in flight in the ledger → do nothing; the bot's own recovery handles it.
2. A number is in the box → `DailyLedger.setLastHandled("SMR2244")`.
3. Otherwise, only if today's ledger is empty, today's remembered id is used the same way.

From there the existing `RetailerSequence` does what it always did with "handled last":
scroll to that id and open the one after it.

### What happens while scrolling to it

`StartPoint.Seeker` watches the rows that come on screen. Rows above the start point are
**held in memory**. Only when the start point itself appears are they written to the ledger
as `DONE_BEFORE` (counted under *Skipped*; an existing record is never replaced).

If the start point never appears — a typing mistake, or the retailer is not on today's
list — the bot's own end-of-list rule restarts from the top, the seeker gives up, nothing
has been written, and every retailer is opened as before. **A wrong number costs time,
never an order.**

### The box on the panel

`panel_last_done` in `res/layout/floaty_layout.xml`, shown only while the bot is not
running. The panel's window normally refuses the keyboard so that the Sudha app keeps it;
it accepts it only while the box is being typed in (`beginEditing` / `endEditing` in
`FloatingPanelController`). Done, Back, a touch outside, Start, Reset and Hide all end the
typing and store the number. Start with something that is not a retailer number does not
start; the panel says "Numbers only, e.g. 2244".

The line beside the box says what Start will do: *Start continues after SMR2244* ·
*Remembered SMR2244 (2 h ago)* · *Reset cleared it. Was SMR2244* · *Empty = from the top*.

## 2. Music while working

In one line: when orders go through, an upbeat 80s / 90s Hindi song plays; when something
fails, a sad one; **found on YouTube, streamed as sound only, nothing to set up**. Apart from
that the app makes no sound at all (§3).

### The rule (`MusicMood`, plain Java)

Fed the same values the panel is given on every update:

| Event | Music |
| --- | --- |
| the panel appears; Start is pressed | nothing (the first songs are looked up, quietly) |
| an order is placed | an upbeat song (one already playing carries on) |
| a retailer ends in an error | cross-fade to a sad song, until the next order |
| the run stops on an error | a sad song for 45 s, then quiet |
| pause, idle | fade out |
| the list is finished | fade out |

### Auto-Dialogues: the second way of working

Off by default. Switched with the **microphone button** on the panel's sound row, or with
*Auto-Dialogues* under *Music while working* in the app. While it is on there are **no
songs**:

| What happens | What is heard |
| --- | --- |
| an order is placed | a rousing Hindi film dialogue (`CHEER`) |
| a retailer ends in an error, or the run stops on one | a sad or dramatic one (`GLOOM`) |
| anything else | nothing |

* The rule is `MusicMood.nextLine`, plain Java, tested frame by frame like the song rule.
* The clips are **found on YouTube on the day**, exactly as the songs are: search words in
  `MusicMood` ("sunny deol dhai kilo ka haath dialogue", "bollywood sad dialogue short
  hindi" and so on), no address in the app. A result is taken when it is 4 to 75 seconds
  long, has "dialogue" in its title, and is not a song, a mimicry, a reaction, a collection
  or the like (`MusicMood.fitsLine`). Up to 25 are kept per kind and used in turn.
* A dialogue starts at full loudness (no fade-in over its first words), is heard once, and
  is faded out after 20 seconds at most. Nothing follows it.
* A dialogue that is being heard is not cut short by the next order; bad news does cut a
  cheer (`MusicMood.keepsLine`). With orders a few seconds apart, not every order gets one.
* Lock song does not apply to dialogues. Play on the sound row says one rousing dialogue.
* Switching it off brings the songs back from the next order.

**What cannot be promised.** Which clips YouTube answers with is YouTube's choice on the
day. A clip can carry a channel's jingle, background music, or be something else that
merely has "dialogue" in its title. The app cannot listen to a clip to judge it. Use *Try a
rousing one* and *Try a sad one* in the app before a run. None of this has been tried
against YouTube itself: the rule is tested, the fetching is not (see below).

### Where the songs come from

The app carries **no song and no song address**. It carries search words (`MusicMood.searches`):

| Mood | Searched for (one line per refill, in turn) |
| --- | --- |
| upbeat | "90s bollywood dance hits hindi songs", and five more of the kind (Kishore Kumar's 80s hits, Govinda, Bappi Lahiri disco, Udit Narayan and Alka Yagnik, 90s party songs) |
| sad | "90s bollywood sad songs kumar sanu", and five more (Kishore Kumar, dard bhare geet, 80s / 90s suspense and thriller songs, Mohammed Aziz, Lata Mangeshkar) |

`YouTubeAudio.search` asks YouTube Music for *songs* first and plain YouTube for *videos*
if that gives nothing. `MusicMood.fits` then keeps what is one song as recorded: 100 seconds
to 9 minutes, and no jukebox, nonstop, mashup, remix, karaoke, lofi, cover, full movie or
"best of". Up to 25 per mood are kept, shuffled, in the `songs` preferences (`SongBook`), and
looked up afresh after 20 hours — so each day sounds different. If the fresh look-up fails,
yesterday's list is kept.

### Sound only

`YouTubeAudio.resolve` asks for the **sound streams** of a song and nothing else; the app
never asks for a picture stream. Of those it takes the best one at or under 140 kbps
(`MusicMood.bestStream`) — about **1 MB a minute**. If a song is offered only as an HLS list
(sound and picture together), it is played with ExoPlayer's picture track switched off, and
there is no surface in the app a picture could be drawn on. Live broadcasts are refused.

### The player (`MusicEngine`, ExoPlayer)

Two `ExoPlayer`s (AndroidX Media3), so one song fades out while the next fades in (2.4 s).

* The internet is asked on one background thread; the bot is never kept waiting for a song.
* When a run starts, the first song of each mood is looked up ahead of time, and while one
  song plays the next is prepared, so a change of mood is heard at once.
* A song that has not started after 25 s, or breaks, is dropped and the next is tried (three
  in a row at most per change). Stream addresses expire; one that is refused is looked up
  again once.
* After six failures in a row (no internet, or YouTube has changed) the player **rests for
  ten minutes** instead of trying at every order, and says why under *Music while working*.
* It does not take the phone's audio focus, so a call or another app is not interrupted.
* It obeys the *Music while working* switch, and stops when the panel is hidden.

**Lock song** (the note on the panel): tap, and the song now playing stays — through orders
and errors, on repeat — until it is tapped again. Press and hold: the panel says which song
is playing.

### In the app: *Music while working*

The switch; a line saying what is playing, that songs are being found, or what went wrong;
**Try upbeat** and **Try sad** (half a minute each, to check the sound before a run);
**Stop**. Below, optional: **your own songs** for each mood, up to 30 — a YouTube link to
one song (watch, `youtu.be`, Shorts and YouTube Music links all work; a playlist or channel
is refused with the reason), a direct `https` link to a sound file or radio stream, or a
file on the phone. While a mood has own songs, only those are played for it. Spotify, Gaana,
JioSaavn, Wynk, Apple / Amazon Music links are refused: those services hand out no sound.

### What this depends on, plainly

* **YouTube has no official way to do this.** The look-up is done by NewPipe Extractor, the
  library behind the NewPipe app, which reads YouTube as a browser would. YouTube changes
  from time to time and the look-up then fails until the library is updated: one line,
  `NewPipeExtractor:v…` in `app/build.gradle.kts`, then a new build. Until then the bot
  works as always, without music.
* Playing YouTube's sound without its page may be against YouTube's terms of service, and
  the songs belong to their labels. The app stores no song; it streams, as a browser does.
* The library is **GPL-3.0-or-later**, which applies to the app as a whole when it is
  passed on; see [`THIRD-PARTY.md`](THIRD-PARTY.md).
* The APK is larger (the two libraries), and the app now uses the internet — for music and
  nothing else, which `FeedbackSelfTest` enforces.

## 3. Silent UI

Deleted, not switched off: the spoken welcome, the Veena, the film lines of the finale, the
clip files in `res/raw`, `VoicePlayer`, `VeenaPlayer`, and the two tools that built the
clips. In addition:

* buttons on the panel, the setup screen and the song lists make no touch sound
  (`MagicUi.silence`);
* notifications use a channel with no sound and no vibration (`aakriti_silent`);
* the two end-of-run cards are shown without sound; an own **picture** can still be set,
  an own sound no longer.

`FeedbackSelfTest` fails the build if any class reaches for `MediaPlayer`, `SoundPool`,
`ToneGenerator`, `TextToSpeech`, a touch sound or a bundled clip.

## Tests

`StartPointSelfTest` and `MusicSelfTest`; see `tools/selftest/README.md`. They cover the
rules: when the music changes, what is searched for, what counts as a song, which stream is
taken, which links are accepted, and how the pieces are wired. **They cannot reach YouTube
or play a sound.** The keyboard on the panel, the look-up, and the music itself have not
been tried on a phone.
