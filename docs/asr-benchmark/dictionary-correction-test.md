# Dictionary-Based ASR Correction Test

## Question
Can a simple Swahili dictionary/lexicon lookup (edit-distance correction against a
known word list) reduce ASR WER/CER as a cheap, zero-hallucination-risk alternative
to LLM-based correction?

## Method
Same 96-transcript evaluation set as the LLM correction test (30 Common Voice clips
+ 2 real user clips, x 3 ASR engines each). For each word in an ASR hypothesis: if
it's already in the dictionary, leave it untouched; if not, replace it with the
nearest dictionary word within a bounded edit distance (else leave it untouched).
Words shorter than 3 characters are never touched (avoids mangling short function
words). This is structurally incapable of hallucinating unrelated text -- the
corrector can only ever output a real dictionary word or the original token.

### Dictionaries tested
1. **Flat wordlist** (37,929 words) -- merged from two plain Swahili wordlists
   (`michaelnjuguna/All-swahili-words-dictionary`, `knobs-dials/wordlists`).
2. **Hunspell-expanded** (71,528 words) -- the Elastic/Hunspell `sw_TZ` Swahili
   dictionary (67,900 base words + prefix-rule expansion for conjugated forms)
   merged with the two flat wordlists above. Hunspell dictionaries encode basic
   morphology (affix rules), giving much better coverage of real Swahili verb/noun
   forms than a flat list alone.

## Results

| Method | Mean Base WER | Mean Corrected WER | Delta | Improved | Worse | Unchanged |
|---|---|---|---|---|---|---|
| Flat wordlist, max edit dist 2 | 0.6744 | 0.6969 | +0.0225 | 14 | 22 | 60 |
| Flat wordlist, max edit dist 1 | 0.6744 | 0.6868 | +0.0124 | 12 | 14 | 70 |
| Hunspell-expanded, max edit dist 2 | 0.6744 | 0.6682 | -0.0062 | 19 | 10 | 67 |
| **Hunspell-expanded, max edit dist 1** | **0.6744** | **0.6659** | **-0.0085** | **16** | **6** | **74** |

**This is the first correction method tested (across both the LLM test and this
one) that reliably reduces WER rather than increasing it.** The best configuration
(Hunspell-expanded vocabulary, max edit distance 1) improved 16 clips and worsened
only 6 -- a 2.7:1 ratio, the inverse of the LLM correction results (which worsened
4-5x more clips than they improved).

### Why the flat wordlist failed but the Hunspell-expanded one worked
The flat wordlists don't encode Swahili's rich verb conjugation morphology, so many
*correctly transcribed* words (e.g. "kujiri") simply weren't present and got
incorrectly "corrected" toward an unrelated dictionary word ("kukiri"). The
Hunspell dictionary's affix rules close much of this gap, roughly doubling
effective vocabulary coverage and sharply reducing false-positive corrections of
already-correct text.

### Remaining weak spot
Foreign proper nouns are still mis-corrected toward unrelated Swahili words, same
failure class seen in the LLM test but far smaller in magnitude (6/96 regressions
here vs. 45-65/96 for the LLM candidates):
- "Jacob Zuma" -> "yakob"/"zuma" pulled toward nonsense substitutions
- "Je, ulienda utt leo?" -> "ulienda" incorrectly swapped to "ulienea" (real word,
  wrong meaning)

A named-entity / foreign-word exception list (skip correction for capitalized
tokens, or tokens matching a small known-foreign-word list) would likely close
most of this remaining gap cheaply.

## Comparison across all three correction approaches tested

| Approach | Mean Corrected WER vs 0.6744 baseline | Hallucination risk |
|---|---|---|
| AfriTeva v2 Base (untuned seq2seq) | 0.9758 (much worse) | High -- untuned, outputs near-garbage |
| Swahili Gemma 1B (decoder LLM) | 0.9753 (much worse) | High -- fluent but unrelated sentences |
| **Dictionary (Hunspell-expanded, dist=1)** | **0.6659 (better)** | **None by construction -- bounded to real dictionary words** |

## Recommendation
Dictionary-based correction with the Hunspell-expanded lexicon and max edit
distance 1 is the only correction method tested so far that is safe to consider
shipping. It is also the cheapest by far: no model inference, microsecond lookup
cost, no extra memory beyond a ~70k-word index, fits comfortably within the app's
offline/on-device/low-latency constraints. A production version should add a
named-entity exception list to close the proper-noun regression gap before
shipping.

## Data
Full per-clip results: Google Sheet "ASR Engine Benchmark - visual-impaired-assistant",
tabs "Dictionary Correction - Summary" and "Dictionary Correction - Detail".
Raw JSON + vocab files: `/opt/data/cache/scratch/correction_models/` (local scratch,
not committed -- see this file for methodology/results).
