# Visual Impaired Assistant (Android)

Modernized, voice-only rebuild of core ideas from Alexander Kivaisi's 2015
UCT master's thesis *"Enabling visually impaired people to use touch
screen phones"*. Kotlin, Jetpack Compose, min SDK 26, built headless with
Gradle 8.9 / AGP 8.7.0 / Kotlin 2.0.21 (no Android Studio).

## Scope (this rebuild)

Only two of the thesis's five original features: **Simu** (phone/dialer +
contacts) and **Ujumbe** (SMS). No music player, no calculator, no news
reader. **No manual text entry of any kind** — the thesis's Slide
swipe-based method and Braille chord entry are both dropped; every input
(dialing, contact lookup, SMS composition) is voice, read back via TTS for
confirmation before any call is placed or message sent.

## Engines

- **ASR**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) +
  Omnilingual ASR CTC (300M params, int8), covering Swahili and 1600+
  other languages with a single model — see `PLAN.md` for exact release
  URLs and rationale vs. the thesis's original PocketSphinx + custom
  Swahili acoustic/language model approach.
- **TTS**: Android's built-in `TextToSpeech`, Swahili locale (`sw-TZ`/
  `sw-KE`), with explicit handling for devices with no Swahili voice
  installed (see `PLAN.md`).

## Status

**Scaffold only** — this commit establishes a compiling Gradle project
skeleton (`gradle compileDebugKotlin` verified green) with the package
structure, `TranscriptionEngine` interface, a sherpa-onnx Omnilingual ASR
wrapper, and a Swahili TTS helper. No feature logic (telephony, SMS,
gesture nav, voice-confirm loop) is implemented yet — see `PLAN.md` for
the full phase breakdown a later pass should follow.

## Building

```bash
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:/path/to/gradle-8.9/bin:$PATH"
export ANDROID_HOME=/path/to/android-sdk
curl -fsSL -o app/libs/sherpa-onnx-1.13.8.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
gradle compileDebugKotlin
```

See `app/libs/README.md` for why the sherpa-onnx `.aar` isn't committed
or resolved via Maven.
