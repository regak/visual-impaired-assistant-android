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

## Phase 1 — Model download + first real ASR round-trip ✅ (this pass, see commit)

1. [x] Ported `ModelDownloader` from `murmur-android/core/ModelDownloader.kt`.
   Fetches `model.int8.onnx` + `tokens.txt` + `silero_vad.onnx` into
   app-private storage (`context.filesDir/models/...`) on first run — not
   the upstream `.tar.bz2` directly; those two files were extracted once
   (via Python's `tarfile`, confirmed working: no `bzip2`/`tar xjf` needed)
   and re-uploaded as individual assets to THIS repo's own `models-v1`
   GitHub Release for download stability, same pattern as
   `murmur-android`'s `models-v1`. Verified via `gh release view --json
   assets` that all 3 assets exist on the release with byte-for-byte
   matching sizes (model.int8.onnx 365352120, tokens.txt 86423,
   silero_vad.onnx 643854). Progress UI (file name, index/count, MB done/
   total, percentage bar) shown via `MainActivity`'s `ScreenState.Downloading`
   before the chooser becomes reachable — Wi-Fi-only prompt NOT implemented
   (not blocking; noted as a gap).
2. [x] `AudioCapture`: `AudioRecord` at 16kHz mono, single-consumer
   `Channel`, ported from `murmur-android/core/AudioCapture.kt`.
3. [x] `SpeechSegmenter` (Silero VAD) ported from
   `murmur-android/core/SpeechSegmenter.kt` — trims silence, pads segment
   edges, bridges quiet-word gaps — wired into `DictationController`
   between capture and `OmnilingualAsrEngine.transcribe`.
4. [x] `OmnilingualAsrEngine`/`SpeechSegmenter` construction gated behind
   `ModelDownloader.isComplete()` in `MainActivity.ensureModelThenLoadEngine()`
   — never constructed before the download gate resolves.
5. [ ] Milestone NOT verified on a real device (no ADB/emulator in this
   build environment, per the android-app-building skill's standing
   limitation): `gradle compileDebugKotlin` and `gradle assembleDebug` both
   exit 0 and produce `app/build/outputs/apk/debug/app-debug.apk`
   (~40MB — small, confirming the model is NOT bundled), but an actual
   press-and-hold Swahili utterance -> transcript round-trip has only been
   verified by code review, not by running the app. Needs real-device
   sideload testing (see Phase 5 checklist) before calling this milestone
   fully done.

## Phase 2 — Voice-confirm UX flow (core/VoiceInputController) ✅ (this pass)

6. [x] Implemented `VoiceInputController.captureConfirmedUtterance`:
   speaks the prompt, captures + transcribes an utterance (own
   `AudioCapture`/`SpeechSegmenter` instances, independent of the Phase 1
   demo's `DictationController` so the two never share VAD state), TTS
   reads back "Ulisema: `<transcript>`. Sawa?", then captures a second
   short utterance for the yes/no response. Returns a
   `ConfirmResult` (`Confirmed`/`Rejected`/`NoResponse`) rather than a
   nullable String, so callers can distinguish an explicit "hapana" from
   silence/timeout and react differently (item 8). Not yet verified with
   real speech on a device — compiles against the real `AudioCapture`/
   `SpeechSegmenter`/`TranscriptionEngine` APIs (`gradle compileDebugKotlin`
   green) but needs the Phase 5 on-device checklist.
7. [x] Yes/no parsing: no second model — `matchesAny` does a lowercase
   substring match against fixed `AFFIRMATIVE_WORDS` ("ndiyo", "ndio",
   "sawa", "sahihi", "yes") / `NEGATIVE_WORDS` ("hapana", "siyo", "sio",
   "si sahihi", "no") sets.
8. [x] Silence/timeout (`withTimeoutOrNull` on the capture window) is
   `ConfirmResult.NoResponse`, distinct from `ConfirmResult.Rejected`
   (explicit "hapana" match) — callers (`SimuScreen`/`UjumbeScreen`) give
   different follow-up status text for each case.

## Phase 3 — Simu + Ujumbe real implementation ✅ (this pass)

9. [x] Runtime permission onboarding: all 8 dangerous permissions
   (`RECORD_AUDIO`, `CALL_PHONE`, `READ_CONTACTS`, `WRITE_CONTACTS`,
   `SEND_SMS`, `READ_SMS`, `RECEIVE_SMS`, `READ_PHONE_STATE`) requested
   together in one `RequestMultiplePermissions` launch right after the
   ASR engine finishes loading (`MainActivity.requestAllDangerousPermissionsIfNeeded`)
   — a single predictable moment rather than scattering a surprise dialog
   across each screen's first use. TTS-narrated rationale text per
   permission is NOT implemented (gap — only the system permission
   dialog's own text is shown); the very first grant still needs a
   sighted helper or TalkBack, as previously noted.
10. [x] `SimuRepository.searchContactsByVoicedName`: queries
    `ContactsContract.CommonDataKinds.Phone` for every contact+number,
    ranks by normalized Levenshtein similarity (0f-1f) against the
    lowercased spoken name, returns the top matches above a 0.4
    similarity floor. Phonetic (Soundex/Metaphone-style) matching was
    considered but not implemented — plain edit-distance only; revisit if
    real-device testing shows this is too strict/loose for common
    mis-transcriptions.
11. [x] `SimuRepository.placeCall`: `Intent.ACTION_CALL` with
    `FLAG_ACTIVITY_NEW_TASK`, called only after `SimuScreen`'s voice-confirm
    flow confirms the number/contact via TTS readback.
12. [ ] NOT implemented: incoming/outgoing call `BroadcastReceiver`s that
    announce caller name/number via TTS. Deferred — no code written this
    pass.
13. [x] `UjumbeRepository.recentMessages`/`sendSms`: `ContentResolver`
    query against `Telephony.Sms.CONTENT_URI` (newest-first, limited);
    `sendSms` uses `SmsManager.divideMessage`/`sendMultipartTextMessage`
    (handles bodies over the single-SMS length) and manually inserts a
    matching row into `content://sms/sent` (this app is not the default
    SMS app, so the provider doesn't do this automatically). Incoming-SMS
    `BroadcastReceiver` (`SMS_RECEIVED_ACTION`) that reads new messages
    aloud is **NOT implemented** — deferred, same as item 12.
14. [x] `SimuScreen`/`UjumbeScreen` wire `VoiceInputController` end to
    end: Simu captures+confirms a spoken name/number, resolves it against
    contacts, reads back the best fuzzy match ("Je, unamaanisha X? Sawa?")
    for a second confirm, then calls; falls back to dialing the confirmed
    text directly as a number if no contact match clears the similarity
    floor. Ujumbe captures+confirms a number, then a body, reads the full
    draft back once more, then sends. Multi-way "X or Y?" disambiguation
    from the original item 14 wording was simplified to single best-match
    confirm (no on-screen list selection exists for a non-sighted user to
    operate anyway, and everything here is already sequential voice
    confirm) — note this as a deliberate scope narrowing, not an
    oversight.

**Known gaps carried forward** (not done this pass, unchanged from
previous phases' deferred items): incoming call/SMS `BroadcastReceiver`s
(items 12-13's TTS-announce half), TTS-narrated permission rationale
(item 9), Wi-Fi-only download prompt (Phase 1 item 1), and all of Phase 5's
real-device verification checklist — this build environment has no ADB/
emulator, so everything above is verified by `gradle compileDebugKotlin`
+ `gradle assembleDebug` exiting 0 and producing a real APK, not by
running the app.

## Phase 4 — Gesture navigation: RESET to gestures-only (explicit user decision)

**Two rounds of the custom `core/GestureDetector.kt` (tap/long-press/
swipe built on raw `awaitEachGesture`/`awaitPointerEvent`) shipped real,
user-reported bugs on a real device**: gesture recognition silently
stopped working after the first swipe/tap on a given surface (root
cause: cancelling a coroutine mid-suspend inside Compose's low-level
pointer event dispatch corrupts that pointer-input node's state for the
next gesture cycle — confirmed by code review across two fix attempts,
never actually verified fixed on-device since this build environment has
no ADB/emulator). Separately, `SwahiliTts` had its own real bug (engine
not actually ready when the first prompt fired on a fast/warm launch).

**Per explicit user direction, this phase restarts from scratch with a
narrower scope**: gesture navigation ONLY — swipe between Simu and
Ujumbe pages, matching the original 2015 thesis's ViewPager-style menu
navigation. No TTS, no ASR/STT, no model download, no voice-confirm
loop, no permissions, no telephony/SMS logic. Those get layered back in
one feature at a time, each independently verified, once this is solid.

15. [x] `MainActivity.kt` rewritten from scratch as a plain
    [`HorizontalPager`](https://developer.android.com/develop/ui/compose/layouts/pager)
    with two pages (Simu, Ujumbe) — Compose's own official, battle-tested
    swipe mechanism (used by Google Photos, Play Store, etc. for this
    exact pattern), NOT the hand-rolled gesture detector. Chosen
    specifically because `HorizontalPager` has none of the custom
    coroutine-cancellation-inside-pointer-dispatch fragility that broke
    `core/GestureDetector.kt` twice. `androidx.compose.foundation:foundation`
    added to `app/build.gradle.kts` for it.
16. [x] Each page currently shows only a static Swahili title +
    subtitle (`Simu` / `Piga simu na anwani`, `Ujumbe` / `Tuma na soma
    ujumbe`) — intentionally minimal placeholder content, proving the
    swipe mechanism alone before any feature is reattached. A small dot
    page-indicator is shown as a **sighted-tester convenience only**, not
    relied on for non-sighted navigation.
17. [x] `core/GestureDetector.kt`, `tts/SwahiliTts.kt`, `asr/*`,
    `core/VoiceInputController.kt`, `core/AudioCapture.kt`,
    `core/SpeechSegmenter.kt`, `core/DictationController.kt`,
    `core/ModelDownloader.kt`, `telephony/SimuRepository.kt`,
    `sms/UjumbeRepository.kt`, `ui/simu/SimuScreen.kt`,
    `ui/ujumbe/UjumbeScreen.kt` are all **untouched and still compile**,
    just no longer referenced from `MainActivity` — nothing was deleted,
    this is a UI-layer-only reset so previous work can be re-attached
    feature-by-feature rather than rewritten from zero.
18. [ ] NOT done this pass, by explicit scope: re-wiring TTS, ASR/STT,
    model download, voice-confirm loop, Simu/Ujumbe real screens, and
    permissions back onto this pager. Next steps, one at a time, each
    independently verified before moving to the next:
    - Re-attach `SimuScreen`/`UjumbeScreen` as the pager's page content
      (currently just placeholder text) — still without TTS/ASR wired in,
      so the real UI structure gets pager-nav-tested in isolation first.
    - Re-attach `ModelDownloader`'s download-gate screen in front of the
      pager.
    - Re-attach `SwahiliTts` (fixing its init-race bug from this pass's
      history) for simple fixed prompts only, no ASR yet.
    - Re-attach ASR/VoiceInputController/voice-confirm loop last, since
      it's the most complex and highest-risk piece.
19. [ ] **Still not verified on a real device** (no ADB/emulator in this
    build environment, the same standing limitation noted in every
    phase): `gradle compileDebugKotlin` and `gradle assembleDebug` both
    exit 0 and produce a real APK, but whether `HorizontalPager` swipe
    actually feels right / repeatedly works on an actual phone has NOT
    been confirmed by anyone yet. This is the first thing to check before
    building anything else on top.

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
