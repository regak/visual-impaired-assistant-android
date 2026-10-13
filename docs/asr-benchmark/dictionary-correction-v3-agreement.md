# Dictionary Correction v3: Noun-Verb Agreement Correction (Method D) — Empirical Test

Follow-up to `dictionary-correction-v2.md`'s "future work" item. Built and ran an
actual agreement-correction pipeline rather than feasibility-testing alone.

## What was built

1. **`noun_class_classifier.py`** — rule-based Swahili noun-class classifier,
   using prefix patterns (wa-, mi-, vi-, ma-, ji-, ki-) calibrated empirically
   against `apertium-swa`'s own tag scheme (verified via `hfst-lookup` on known
   word pairs — e.g. confirmed `mtu`/`watu` are both tagged `cl1` by apertium,
   distinguished only by `<sg>`/`<pl>`, not by separate class numbers per
   singular/plural as initially assumed). Deliberately abstains (returns `None`)
   on genuinely ambiguous cases: bare "m-" nouns (`mtu` vs `mti` — person vs.
   tree, no formal distinguishing prefix) and n-class nouns (same surface form
   singular and plural).
2. **`run_agreement_check.py`** — full pipeline: classify each noun (falling
   back to apertium's own noun lexicon via `hfst-lookup` when the prefix rules
   abstain), find the next verb within 2 tokens, analyze its agreement class via
   `automorf.hfst`, and if it mismatches the noun's class, **regenerate the
   correct verb form** via `autogen.hfst` (strip the wrong class/number tags,
   substitute the noun's class, re-generate the surface form).

## Calibration bug found and fixed
The first version of the classifier assumed apertium used separate class
numbers for singular/plural pairs (e.g. `wa-` → `cl2`), which is WRONG — it
caused a false-positive agreement flag even on the grammatically **correct**
sentence "Macho yangu yamefungwa". Fixed by empirically checking real
`hfst-lookup` output on calibration word pairs (mtu/watu, mti/miti,
kiti/viti, gari/magari) before trusting any assumed tag scheme.

## Unit test (hand-picked examples) — works correctly

| Input | Output | Flag |
|---|---|---|
| "Macho yangu imefungwa" (wrong) | "Macho yangu **yamefungwa**" | Flagged + corrected |
| "Macho yangu yamefungwa" (correct) | unchanged | No flag (correct) |
| "mtu amefika" (correct, resolved via lexicon fallback) | unchanged | No flag (correct) |

This confirms the mechanism itself works: it correctly fixes the exact
agreement-error example from the original review feedback, and correctly
leaves already-correct sentences untouched.

## Full 96-clip real-ASR-output test — null result (and why)

Ran the full pipeline on all 96 real ASR transcripts (30 Common Voice clips +
2 user clips × 3 engines). Result: **zero flags, zero corrections, WER
unchanged** (identical to baseline 0.6744).

Root cause investigated directly: 94 tokens across the 96 clips were
successfully classified as nouns, but **none** of them were followed by a verb
that `apertium-swa`'s own lexicon could analyze. Verified this isn't a pipeline
bug — the verb analyzer works correctly on clean verb forms (tested 10 known
verb conjugations, all correctly tagged). The real cause: our ASR transcripts
are themselves noisy/garbled (that's the whole point of testing correction
methods), so the word following a classified noun is rarely a clean dictionary
verb form the analyzer recognizes — it's often a misrecognized fragment, a
different part of speech, or simply too far from any inflected form in
`apertium-swa`'s lexicon.

**This mirrors the exact limitation already flagged for the noun side**
(`macho` missing from the lexicon) — `apertium-swa`'s overall lexical coverage,
both nouns and verbs, is too thin for real-world noisy ASR output, even though
the underlying agreement-checking *mechanism* is demonstrably correct on clean
text.

## Latency
Mean 1805ms/clip (full sentence scan, multiple `hfst-lookup`/`hfst-lookup`
subprocess calls per token) — slower than both dictionary correction variants,
consistent with the extra morphological analysis overhead.

## Conclusion
Method D is **mechanically validated but practically ineffective on this
dataset** given `apertium-swa`'s current lexical coverage. It is not a
production-ready correction method today. To make it useful would require:
- Expanding `apertium-swa`'s verb/noun lexicon substantially (the
  lexicon-curation effort already flagged as out of scope for a quick test), or
- Relaxing verb matching to use fuzzy/approximate matching against the
  lexicon (similar to the dictionary corrector's edit-distance approach)
  instead of requiring an exact dictionary form, which could recover signal
  from near-miss verb forms — a natural next experiment if this path is
  pursued further.

**Updated recommendation stands**: dictionary correction v2 (gated,
section C) remains the best-performing, production-ready method tested.
Method D is confirmed real and promising in principle, but needs more
foundational lexicon work before it can move the needle on actual WER.

## Data
`/opt/data/cache/scratch/correction_models/agreement_results.json` (96-clip
raw results, all with 0 flags), `noun_class_classifier.py`,
`run_agreement_check.py` (not committed to repo — local scratch scripts).
