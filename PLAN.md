# Visual Impaired Assistant — Android rebuild — Plan

Modernized, voice-only rebuild of core ideas from Alexander Kivaisi's 2015
UCT master's thesis *"Enabling visually impaired people to use touch
screen phones"*. Scope for this rebuild (explicit user decision — see
repo README): **only Simu (phone/dialer+contacts) and Ujumbe (SMS)**. No
Muziki (music), no Kikokotoa (calculator), no Habari (news). **No manual
text entry at all** — no Slide swipe-method, no Braille chord entry.
Every input (dialing, contact name/number, SMS composition) is voice, with
mandatory TTS readback confirmation before any call/send action executes.

## Engine decisions

- **ASR: sherpa-onnx + Omnilingual ASR CTC (300M, int8)**. Covers Swahili
  (and 1600+ other languages) with one model — no custom Swahili acoustic/
  language model or JSGF grammar to train, unlike the thesis's PocketSphinx
  pipeline (19.1% WER on a 6hr broadcast-news corpus). Model release:
  `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`,
  from `https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models`.
  Docs: https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html
  `TranscriptionEngine` interface (ported from the sibling `murmur-android`
  project's pattern) keeps a future model swap a drop-in change.
- **TTS: Android's built-in `TextToSpeech` API**, Swahili locale (`sw-TZ`
  preferred, `sw-KE` fallback). `SwahiliTts` reports which tier was granted
  — **many Android builds ship with no Swahili voice installed at all**;
  this is a real, documented limitation of this approach, not a bug to
  "fix" in code. A later phase should surface this to the user in-app
  (e.g. prompt to install a Swahili TTS voice pack) rather than silently
  speaking English.

## Architecture

Based on `/opt/data/projects/murmur-android`'s module layout: single
`:app` module, Kotlin, Jetpack Compose, min SDK 26, target SDK 34, AGP
8.7.0 / Kotlin 2.0.21. Package root `ai.pivotstudio.via.android`:

- `asr/` — `TranscriptionEngine` interface, `OmnilingualAsrModel` (download
  location/URL constants), `OmnilingualAsrEngine` (sherpa-onnx wrapper).
- `tts/` — `SwahiliTts`.
- `core/` — `VoiceInputController` (voice-confirm loop seam; stub).
- `telephony/` — `SimuRepository` (contacts + outgoing call CRUD; stub).
- `sms/` — `UjumbeRepository` (SMS provider CRUD + send; stub).
- `ui/`, `ui/simu/`, `ui/ujumbe/` — `MainActivity` + two Compose screens.

## Phase 0 — Scaffold ✅ (this pass)

- [x] Project folder created, sibling to `murmur-android`.
- [x] Gradle/AGP/Kotlin/Compose config mirrored from `murmur-android`.
- [x] `sherpa-onnx-1.13.8.aar` vendored under `app/libs/` (not committed,
  see `app/libs/README.md`); confirmed via `javap` on its `classes.jar`
  that this AAR version already exposes
  `OfflineOmnilingualAsrCtcModelConfig` / `OfflineModelConfig.omnilingual`
  — no sherpa-onnx version bump needed.
- [x] `TranscriptionEngine` interface + `OmnilingualAsrEngine` wrapper
  (compiles against the real AAR API; model files not yet downloaded/
  wired — `load()` throws until Phase 1's `ModelDownloader` exists).
- [x] `SwahiliTts` helper (Android TTS, sw-TZ/sw-KE, availability
  reporting).
- [x] `MainActivity` + `SimuScreen`/`UjumbeScreen` stub Compose screens,
  two-button chooser (placeholder for the real gesture-nav in Phase 4).
- [x] `SimuRepository`/`UjumbeRepository` stub objects (throw
  `error(...)` with a pointer to the phase that implements them) so later
  phases have a concrete seam to fill in rather than starting from
  nothing.
- [x] `gradle compileDebugKotlin` verified green (see repo commit).
- [x] New GitHub repo created, this scaffold pushed.

**Explicitly NOT done in this pass** (next agent's job, in order):

## Phase 1 — Model download + first real ASR round-trip

1. Port `ModelDownloader` from `murmur-android/core/ModelDownloader.kt`:
   fetch `OmnilingualAsrModel.DOWNLOAD_URL` (a `.tar.bz2`, not a `.zip` —
   extract with Python's `tarfile` module if `bzip2`/`tar xjf` isn't
   available in the target build environment) into app-private storage on
   first run. ~350MB+ one-time download for the int8 300M model — budget
   UI/UX for a progress indicator and Wi-Fi-only prompt.
2. `AudioCapture`: `AudioRecord` at 16kHz mono (mirror
   `murmur-android/core/AudioCapture.kt`'s single-consumer `Channel`
   pattern).
3. Silero VAD (sherpa-onnx ships `silero_vad.onnx` as a release asset too)
   to segment held-button speech before it reaches `OmnilingualAsrEngine`.
4. Gate `OmnilingualAsrEngine` construction behind
   `OmnilingualAsrModel.isDownloaded()` — **never** construct the
   `OfflineRecognizer` before confirming the files exist; a missing file
   crashes natively (no catchable exception, no stack trace, looks like
   "app opens then closes instantly" with zero diagnostics).
5. Milestone: press-and-hold in `MainActivity` → speak Swahili → see the
   transcript appear on screen (proves the model + wrapper actually work
   before any telephony/SMS logic is built on top).

## Phase 2 — Voice-confirm UX flow (core/VoiceInputController)

6. Implement `VoiceInputController.captureConfirmedUtterance`: capture →
   transcribe → `SwahiliTts.speakAndAwait("Ulisema: <transcript>. Sawa?")`
   (You said: `<transcript>`. Correct?) → listen for a short yes/no voice
   response ("ndiyo"/"hapana") → return confirmed text or null/retry.
   This is the single flow every action in the app (dial a number, say a
   contact name, compose an SMS body) reuses — build and test it once,
   standalone, before wiring Simu/Ujumbe around it.
7. Decide and implement the yes/no response parse: simplest viable
   version is just another ASR pass over a short utterance matched
   against a tiny fixed Swahili word list ("ndiyo", "hapana", "sawa",
   "hapana sawa", etc.) — no need for a second model.
8. Handle silence / no response timeout distinctly from an explicit "no".

## Phase 3 — Simu + Ujumbe real implementation

9. Runtime permission onboarding: `CALL_PHONE`, `READ_PHONE_STATE`,
   `READ_CONTACTS`, `WRITE_CONTACTS`, `SEND_SMS`, `READ_SMS`,
   `RECEIVE_SMS`, `RECORD_AUDIO` — all dangerous permissions, must be
   requested at runtime (Android 26+), with TTS-narrated rationale since
   the user cannot read an on-screen permission dialog unassisted (a
   sighted helper or TalkBack interop may be needed for the *first* grant
   only — note this as a known bootstrapping gap in a later PLAN.md
   update).
10. `SimuRepository.searchContactsByVoicedName`: `ContentResolver` query
    against `ContactsContract.Contacts`/`CommonDataKinds.Phone`, fuzzy-
    match the voiced name (exact match on a spoken name transcribed by
    ASR will rarely be perfect — budget a fuzzy/phonetic matching pass,
    e.g. Levenshtein over normalized Swahili name forms).
11. `SimuRepository.placeCall`: `Intent.ACTION_CALL` (needs `CALL_PHONE`)
    after TTS confirms the number/contact.
12. Incoming/outgoing call `BroadcastReceiver`s (`PhoneStateListener` or
    `TelephonyCallback` on API 31+, since `PhoneStateListener` is
    deprecated) that announce caller name/number via TTS instead of (or
    in addition to) the system dialer UI — matches the thesis's "call
    handling routed through the app's own UI" behavior.
13. `UjumbeRepository.recentMessages`/`sendSms`: `ContentResolver` CRUD
    against the SMS provider (`content://sms`) + `SmsManager.sendTextMessage`
    (needs `SEND_SMS`); incoming-SMS `BroadcastReceiver`
    (`SMS_RECEIVED_ACTION`) that reads new messages aloud via TTS.
14. Wire `VoiceInputController` into both: voice-dial a spoken number OR
    a spoken contact name (disambiguate multiple fuzzy matches via TTS
    "Did you mean X or Y?"); voice-compose an SMS body with TTS readback
    before send.

## Phase 4 — Gesture navigation

15. Custom tap/swipe gesture detector (`onTouchListener` + `CountDownTimer`
    for single/double/long tap + 4-direction swipe, per the thesis) to
    replace this scaffold's two-button chooser as the primary navigation
    — critical for actual non-sighted usability, since reading on-screen
    buttons isn't viable for this app's target users.
16. Map gestures: e.g. swipe right/left = switch between Simu/Ujumbe,
    double-tap = confirm/select, long-press = cancel/back, matching (or
    deliberately improving on) the thesis's original gesture vocabulary.

## Phase 5 — Hardening & on-device testing checklist

17. Foreground service + notification while actively recording (Android
    kills background mic access aggressively without one) — mirrors
    `murmur-android`'s `FloatingBubbleService` pattern if a similar
    "listening" indicator is wanted.
18. On-device testing checklist (no ADB/emulator available in this build
    environment — must be exercised on a real phone):
    - [ ] App installs and launches without crashing (sideload via `gh
      release create`, per the android-app-building skill).
    - [ ] TTS actually speaks Swahili on at least one real device; note
      which `LocaleAvailability` tier was granted.
    - [ ] First-run model download completes over Wi-Fi without timing
      out or corrupting the archive (verify file sizes/checksums after
      extraction).
    - [ ] A held-button Swahili utterance transcribes to roughly correct
      text (spot-check against a few known test phrases).
    - [ ] Voice-confirm loop correctly accepts "ndiyo" and rejects
      "hapana" in a noisy room, not just silence.
    - [ ] A real outgoing call places correctly after voice confirm.
    - [ ] A real SMS sends correctly after voice confirm.
    - [ ] Incoming call/SMS BroadcastReceivers fire and read aloud
      without blocking the system's own notification (don't suppress
      the OS-level ring/notification entirely — layer TTS on top).
    - [ ] Runtime permission flow is navigable by a non-sighted tester
      with TalkBack as a bootstrap aid for the very first grant.
19. Signing + distribution decision (sideload APK via GitHub Release vs.
    Play Store — Play Store review is notably stricter on
    `READ_SMS`/`READ_CALL_LOG`-adjacent permissions for non-default-
    dialer/SMS apps; sideload is almost certainly the realistic path for
    an accessibility research app like this one).

## Deferred / explicitly out of scope

- Muziki (music player), Kikokotoa (calculator), Habari (news) — dropped
  per this rebuild's scope decision; not revisited unless the user asks.
- Manual text entry of any kind (Slide swipe-method, Braille chord) —
  permanently out of scope for this rebuild; voice is the only input
  method, by design.
- A from-scratch Swahili acoustic/language model (the thesis's
  PocketSphinx + custom JSGF grammar approach) — superseded entirely by
  the Omnilingual ASR CTC model; do not resurrect this unless Omnilingual
  ASR proves unusably inaccurate for Swahili in real on-device testing.
