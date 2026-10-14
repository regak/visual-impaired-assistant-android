# Swahili ASR Training Dataset Phonetic Balance Analysis

Direct follow-up to "what datasets were the baseline models trained on" --
we identified that none of the three original research papers' training
corpora (Omnilingual ASR corpus, MMS-lab, Whisper's internet-scrape) are
practically explorable for a Swahili-only slice without either (a) the full
multi-TB download, or (b) a gated/license-walled dataset (Mozilla Common
Voice 17). Instead we accessed and analyzed a real, directly-downloadable
Swahili-only ASR research corpus: **`AfriSpeech/african-speech-public_v1`**
(config `swahili_swa` = standard Swahili, Tanzania, part of a 142-language,
2,267.9-hour African speech corpus).

## Dataset accessed
- **Source**: `AfriSpeech/african-speech-public_v1` on HuggingFace, config
  `swahili_swa` -- 13,101 clips, 38.73 hours total (train+val+test).
- **Downloaded for analysis**: test (617 clips) + validation (703 clips)
  splits = **1,320 transcripts, 3.86 hours**, no audio download needed for
  the text-level phonetic analysis.
- **Underlying audio source**: transcripts and filenames indicate recordings
  are from **Watch Tower Bible and Tract Society (Jehovah's Witnesses /
  JW.org) Swahili broadcast material** -- "Mnara wa Mlinzi" (Watchtower
  magazine) recordings, not general-domain speech.

## Key finding 1: significant domain/topical skew toward religious content
| Term | Occurrences | % of all word tokens |
|---|---|---|
| Yehova (Jehovah) | 301 | 1.56% |
| Yesu (Jesus) | 114 | 0.59% |
| Biblia (Bible) | 96 | 0.50% |
| Mungu (God) | 92 | 0.48% |
| Kristo (Christ) | 66 | 0.34% |
| Roho (Spirit) | 46 | 0.24% |
| Ufalme (Kingdom) | 16 | 0.08% |
| Ibada (Worship) | 13 | 0.07% |
| Imani (Faith) | 9 | 0.05% |

**"Yehova" alone is the 5th most frequent word in the entire corpus**
(ahead of "ni", "la", "kuwa", "watu" -- ordinary function/content words),
at 1.56% of all tokens. Combined, these 9 religious terms make up **3.9%
of the entire corpus's word tokens**. A model trained/evaluated purely on
this slice would see this vocabulary far more often than its real-world
frequency in general Swahili speech, and conversely would under-represent
everyday domains (commerce, health, casual conversation, numbers/phone
numbers -- directly relevant to this app's "Simu"/"Ujumbe" voice features).

## Key finding 2: a core Swahili phoneme is entirely absent from this corpus's orthography
Checked for `ng'` -- the velar nasal phoneme written with a distinguishing
apostrophe (contrasts "ng'ombe" [cow] vs. "ngoma" [drum], or "ng'aa" [shine]
vs. "nga"). Result: **the apostrophe-marked `ng'` spelling occurs zero times**
across 1,320 transcripts (140k characters). All 28 apostrophe-like characters
found in the corpus are punctuation (closing single-quotes around quoted
speech), not phonetic markers. Checked known `ng'`-words directly
(`ng'ombe`, `ng'ara`, `ng'amua`, `ng'ang'ania`, `ng'oa`) with both standard
and curly-apostrophe variants -- all zero hits.

This doesn't necessarily mean the *sound* never occurs in the audio (the
transcribers may have simply dropped the apostrophe, a known inconsistency
in Swahili orthography even among native writers) -- but it does mean **any
model trained on this corpus's text labels gets zero explicit supervision
signal for the written `ng'` distinction**, which could plausibly contribute
to the kind of digit/word confusion we saw in our own benchmark (e.g.
"mbili" vs "mbiri" merges, similar nasal-cluster confusions).

## Key finding 3: other nasal/digraph clusters are present but unevenly distributed
| Digraph | Count (in 140k chars) |
|---|---|
| ng | 972 |
| mb | 893 |
| sh | 858 |
| ny | 695 |
| nd | 672 |
| ch | 503 |
| nz | 331 |
| dh | 203 |
| th | 150 |
| nj | 143 |
| mp | 62 |
| nt | 23 |
| gh | 21 |
| mv | 7 |
| nk | 1 |

`gh`, `mv`, `nk` are each under 25 occurrences total across the whole
sample -- any model relying heavily on this corpus alone for fine-tuning
would see very little signal for these sounds, even though they're
legitimate, non-rare parts of standard Swahili (e.g. "ghali" = expensive,
"mvua" = rain, "nkuu" forms).

## Key finding 4: vocabulary breadth is narrow for a "general Swahili" claim
- 3,577 unique words across 19,297 word tokens (lexical diversity ratio
  ~0.185 -- quite repetitive, consistent with scripted/liturgical content
  rather than spontaneous diverse speech)
- Mean word length 5.73 characters, median 6 -- Swahili's long morphologically
  complex words are well represented structurally, but the actual word
  *identities* repeating heavily skew toward this corpus's narrow topic area

## Letter frequency (for reference, not itself a red flag)
a (20.8%), i (11.1%), u (7.7%), n (7.6%), k (6.1%), e (5.1%), w (4.6%),
m (4.6%), o (4.3%) -- this vowel-heavy, nasal-consonant-heavy profile matches
expected Swahili phonology broadly; no major anomaly here, the issue is
specific sounds/vocabulary coverage, not the overall letter distribution.

## Conclusion & relevance to this project
This single publicly accessible Swahili corpus is **not representative of
general spoken Swahili** -- it's dominated by religious/liturgical
vocabulary and completely missing the written `ng'` phonetic distinction.
If any future fine-tuning or correction-model work for this app draws on
this specific corpus, expect:
- Inflated performance on religious vocabulary, understated performance on
  everyday domains (numbers, names, commerce -- directly relevant to
  Simu/Ujumbe voice features)
- No explicit training signal for the `ng'`-marked phoneme distinction
- A real risk of overfitting to a narrow ~3,577-word vocabulary if used as
  a primary fine-tuning set rather than one ingredient among several

**Caveat on scope**: this analysis covers one specific, practically
accessible Swahili dataset (`AfriSpeech/african-speech-public_v1`), not the
three original research papers' actual training corpora (Omnilingual ASR's
facebook/omnilingual-asr-corpus doesn't include a labeled "swa"/Swahili
subset at all -- confirmed empirically, it covers 348 *other*
under-resourced languages; MMS-lab and Whisper's training sets are not
practically explorable as isolated Swahili-only slices without full
multi-TB downloads or gated access). This is the closest practical
substitute: a real, downloadable, research-grade Swahili ASR dataset,
analyzed directly rather than guessed at.

## Data
`/opt/data/cache/scratch/correction_models/dict_data/afrispeech_swa_text.csv`
(1,320 rows, transcript text + duration + split, local only -- not committed,
contains only text, no audio).
Scripts: `phonetic_balance_check.py`, `phonetic_balance_check2.py`,
`domain_skew_check.py` (local scratch, not committed).
