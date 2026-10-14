# Phonetic Balance Comparison — 3 Accessible Swahili Training/Eval Datasets

Direct follow-up to the earlier single-dataset phonetic-balance study (AfriSpeech only). This extends the same methodology to all 3 datasets confirmed independently downloadable in `training-data-access-verification.md`, to see whether the `ng'` phoneme gap and domain skew found in AfriSpeech are universal problems or specific to that one corpus.

## Method
Pulled real transcription text directly from each source (no paraphrasing):
- **AfriSpeech swahili_swa**: 1,320 transcripts (test+validation splits, previously downloaded)
- **FLEURS sw_ke**: 3,768 transcripts (all 3 splits: train+test+validation, newly pulled)
- **w2v-bert-2.0's swahili-speech-400hr**: 3,670 transcripts (5 of 88 train shards, representative sample, newly pulled)

Checked: digraph/phoneme-marker frequency (especially the `ng'` velar nasal, previously found completely absent in AfriSpeech), vocabulary diversity, and word-frequency skew as a domain-concentration proxy.

## Results

| Metric | AfriSpeech (religious) | FLEURS (read-speech) | w2v-bert-2.0 (multi-domain) |
|---|---|---|---|
| Transcripts analyzed | 1,320 | 3,768 | 3,670 |
| Total words | 19,297 | 75,454 | 138,648 |
| Unique words | 3,577 | 8,343 | 10,024 |
| Vocabulary ratio | 18.54% | 11.06% | 7.23% |
| **`ng'` phoneme marker** | **0 occurrences** | **11 occurrences** | **167 occurrences** |
| Top-10 word share of all tokens | 20.5% | 23.33% | 23.2% |

## Key findings

### 1. The `ng'` phoneme gap is NOT universal — it's specific to AfriSpeech
FLEURS and w2v-bert-2.0 both contain genuine `ng'` words, confirmed by context (not punctuation):
- FLEURS: `ng'ombe` (cow), `ng'ambo` (abroad/across), `mng'ao` (shine/gleam), `kung'aa` (to shine)
- w2v-bert-2.0: `ng'ombe`, `ning'inia`/`kuning'inia` (to dangle/hang), `ng'aa`/`zinang'aa` (shine)

w2v-bert-2.0's 400hr corpus has by far the richest `ng'` coverage (167 occurrences across only 3,670 transcripts vs. FLEURS's 11 across 3,768) — consistent with it being genuinely natural, varied speech rather than scripted sentences or liturgical text. **AfriSpeech's zero count is a real gap specific to that corpus**, not a general problem with Swahili ASR datasets.

### 2. Vocabulary diversity inversely tracks domain breadth
AfriSpeech has the *highest* vocabulary ratio (18.54%) despite having the fewest total words — but this is misleading: it reflects a small, repetitive religious vocabulary relative to its small size, not genuine lexical richness. w2v-bert-2.0 has the *lowest* ratio (7.23%) simply because it has far more total words (138,648) — common function words repeat more, which is expected and healthy for a larger corpus, not a quality problem.

### 3. Top-10 word concentration is similar across all three (~20-23%)
This is largely unavoidable — Swahili function words (na/ya/kwa/wa = "and/of/with/of") dominate any natural-language corpus. However, AfriSpeech's top-10 list includes **"yehova" (Jehovah)** at position 6 with 301 occurrences (1.56% of ALL words in the corpus) — no equivalent domain-specific word appears in either FLEURS's or w2v-bert-2.0's top-10, confirming the religious skew is distinctive to AfriSpeech, not a shared artifact.

### 4. Data-quality flag: FLEURS contains an English leak
Found `"king's"` in the `ng'` substring search — an English possessive apostrophe-s construction appearing in what should be pure Swahili transcription. This is a minor but real transcription-quality issue worth noting (likely a borrowed/code-switched term or transcription error in the source FLoRes-101 sentences).

## Updated verdict on dataset suitability

| Dataset | ng' phoneme coverage | Domain diversity | Vocabulary size | Best use |
|---|---|---|---|---|
| AfriSpeech | Poor (0 occurrences) | Poor (single religious genre) | Smallest (3,577 unique) | Not recommended as primary training source |
| FLEURS | Fair (11 occurrences) | Fair (read Wikipedia/news-style sentences) | Medium (8,343 unique) | Good for evaluation (that's its designed purpose), thin for training |
| **w2v-bert-2.0 (400hr)** | **Best (167 occurrences)** | **Best (5 real domains)** | **Largest (10,024 unique)** | **Best candidate of the 3 for either training or a richer eval set** |

This confirms and strengthens the earlier recommendation: of the datasets we can actually verify and download, `badrex/swahili-speech-400hr` (underlying w2v-bert-2.0-swahili-asr) is the most balanced and highest-quality Swahili transcription source available to us.
