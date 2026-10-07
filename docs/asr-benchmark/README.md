# ASR Engine Benchmark — Omnilingual CTC 300M vs Whisper tiny vs Meta MMS-1B-all

User request (verbatim): "Which AI ASR model are you using now for Swahili" →
"what about meta mms asr or whisper tiny how we can utilize them" → "Yes
please can you setup that benchmark accuracy test".

## Test set

30 real Swahili utterances randomly sampled from
`Veronica1NW/cv17_sw_kenyan_sample` (Hugging Face), a Kenyan-Swahili subset
of Common Voice 17. See `test-set-manifest.json` for the exact sentences,
clip filenames, sample rates and durations used.

## Engines compared

| Engine | Source | Size | Notes |
|---|---|---|---|
| Omnilingual CTC 300M (int8) | `OpenVoiceOS/omnilingual-asr-ctc-300m-onnx` | ~365 MB | **Current app engine** — multilingual, 1600+ languages, no language-specific head |
| Whisper tiny (int8) | `k2-fsa/sherpa-onnx` release asset | ~85 MB | Generic multilingual, smallest Whisper tier |
| Meta MMS-1B-all (Swahili adapter, int8) | `OpenVoiceOS/mms-1b-all-onnx` | ~1 GB shared base + ~8.7 MB Swahili adapter | 1198-language wav2vec2 base + per-language CTC adapter head |

All three run via the `onnx-asr` / `sherpa-onnx` Python libraries on CPU,
same raw audio clips, no cherry-picking.

## Results (30 clips, word error rate / character error rate / inference time)

| Engine | Mean WER | Mean CER | Avg inference time (1 CPU core) |
|---|---|---|---|
| **MMS-1B-all** | **0.327** | **0.098** | 3.07 s |
| Omnilingual CTC 300M (current) | 0.532 | 0.165 | 2.24 s |
| Whisper tiny | 1.159 | 0.569 | 0.71 s |

## Conclusions

- **Whisper tiny is unusable for Swahili** — WER > 1.0 (more errors than
  words), with visible hallucination/looping on several clips (e.g.
  `"mga mga mga mga mga..."`). Ruled out.
- **MMS-1B-all is meaningfully more accurate than the current Omnilingual
  engine** — ~38% lower WER, ~40% lower CER — at a real but acceptable cost:
  ~2.7x larger on-device model size, ~37% slower per utterance on this
  single-core test machine.
- Decision: switch the app's ASR engine from Omnilingual CTC 300M to
  MMS-1B-all (Swahili adapter), approved by the user.

## Reproducing

See `run_asr_benchmark.py` (the exact script used) and `run_log.txt` (the
full console output, every clip's three transcripts + timings). Per-clip
JSON output is in `results.json`.

Models and the Python benchmark harness are **not** committed here (large
binaries, Python-only tooling) — only this report + manifest + script +
results for reproducibility and audit.
