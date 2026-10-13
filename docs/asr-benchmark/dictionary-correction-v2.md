# Dictionary Correction v2: Confidence-Gating + Morphology Feasibility

Follow-up to `dictionary-correction-test.md`, testing two specific improvements
suggested in review feedback: (1) confidence-gating (abstain on ambiguous
corrections) and (2) feasibility of full morphological/noun-class agreement
checking via `apertium-swa`.

## 1. Confidence-gating (abstain on ties)

**Change**: when the nearest dictionary word to an unrecognized token is tied
with one or more other candidates at the same edit distance, abstain (leave the
original token unchanged) instead of picking one arbitrarily.

### Results

| Method | Mean Base WER | Mean Corrected WER | Delta | Improved | Worse | Unchanged |
|---|---|---|---|---|---|---|
| v1 (no gating), Hunspell dict, max_dist=1 | 0.6744 | 0.6659 | -0.0085 | 16 | 6 | 74 |
| **v2 (gated), Hunspell dict, max_dist=1** | **0.6744** | **0.6582** | **-0.0162** | **12** | **1** | **83** |

Gating nearly eliminated harmful corrections: the "worse" count dropped from 6 to
1 clips, and the improvement-to-regression ratio went from 2.7:1 to 12:1. 134
individual word-level abstentions occurred across the 96-clip set (ambiguous
tokens correctly left untouched rather than guessed).

The single remaining regression (`zitajiri` -> `kitajiri`) was a genuinely
**unique** (non-tied) nearest match that happened to be the wrong word -- not a
gating failure, just the inherent limit of edit-distance-only correction without
context.

### Cost
Gating requires scanning the full candidate-distance table (no early-exit on
first match), making it ~13x slower per item: **mean 629ms/clip, max 3.66s/clip**
in this unoptimized Python implementation, vs. near-instant for the ungated
version. Fast enough for non-realtime/background correction, but would need
optimization (trie-based lookup, bounded candidate generation, or a compiled
language) before use in a real-time on-device voice pipeline.

## 2. Morphological analyzer feasibility (apertium-swa)

Tested whether a real Swahili morphological analyzer -- needed to catch
noun-class agreement errors like "macho yangu **imefungwa**" (wrong) vs
"**yamefungwa**" (correct), which a flat dictionary cannot distinguish since both
are valid standalone words -- can run in this fully offline, no-root environment.

### Result: feasible, confirmed working
`apertium-swa`'s toolchain (`lttoolbox`, `hfst`, `lexd`, plus transitive
dependencies `libicu`, `libfst`, `libfoma`, `libreadline`) was extracted from
`.deb` packages via `apt-get download` + `dpkg -x` **without root/sudo**,
proving this is viable for a fully offline on-device deployment in principle.
The full morphological transducer (`swa.automorf.hfst`, `swa.autogen.hfst`)
compiled successfully from the `.lexc`/`.lexd`/`.twol` source files.

Verified with `hfst-lookup`:
```
echo "yamefungwa" | hfst-lookup swa.automorf.hfst
  -> kufungwa<vblex><perf><cl5><pl>
echo "imefungwa" | hfst-lookup swa.automorf.hfst
  -> kufungwa<vblex><perf><cl3><pl>
  -> kufungwa<vblex><perf><cl9><sg>
```
This confirms the analyzer does carry exactly the class/agreement information
(cl5/pl vs cl3/pl, cl9/sg) needed to catch this error class -- the two surface
forms are distinguishable by noun class, which a flat word dictionary cannot see.

### Limitation found
The noun lexicon itself did **not** recognize `macho` (a common, basic Swahili
word) when queried directly. Noun lexicon coverage in `apertium-swa` appears
incomplete for this kind of application. Full agreement-checking therefore is
**not plug-and-play** -- it would require either:
- substantially expanding the noun-class lexicon (manual curation or import from
  another source with class tags), or
- cross-referencing noun classes from our existing word dictionary/lexicon and
  building the agreement-checking logic on top, using apertium-swa's verb-side
  analysis (which worked correctly) paired with an external noun-class source.

This is a genuine, separate engineering effort (lexicon curation + agreement
logic), not a drop-in correction step like the two dictionary-based methods
already tested. Recommend treating it as a distinct, larger follow-up project if
pursued, not bundling it into the current correction pipeline.

## Updated recommendation
For a near-term shippable improvement: **dictionary correction with confidence
gating** (method 1 above) is the best result across every correction approach
tested so far (LLM, plain dictionary, gated dictionary) -- 12:1 improve/worsen
ratio, no hallucination risk, fully offline. The latency cost (629ms mean) needs
addressing before real-time use, but is tractable (the Python implementation is
unoptimized; a compiled/indexed lookup would be far faster).

The morphological/noun-class approach is confirmed technically feasible in this
offline environment but requires substantially more lexicon-building work before
it could be evaluated for actual WER impact -- flagged as a future direction, not
part of the current correction recommendation.

## Data
Raw results: `/opt/data/cache/scratch/correction_models/dict_gated_d1_scored.json`.
Compiled morphological transducers (not committed, local only):
`/opt/data/cache/scratch/correction_models/dict_data/apertium-swa/swa.automorf.hfst`,
`swa.autogen.hfst`. Google Sheet tab: "Dict v2 Gating+Morphology".
