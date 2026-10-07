# LLM-Based ASR Correction Test

## Question
Can an LLM/correction model clean up ASR transcripts to reduce WER/CER, before the
app speaks them back or acts on them?

## Method
Took all 96 ASR transcripts already produced by the 3-engine benchmark (30 Common
Voice Swahili clips x 3 engines + 2 real user clips x 3 engines) and ran each through
two candidate correction models as a post-processing pass, scoring WER/CER of the
corrected text against the same references used in the original benchmark.

### Candidates tested
1. **AfriTeva v2 Base** (`castorini/afriteva_v2_base`, 428M, T5 1.1 seq2seq) --
   chosen because published research on Hindi post-ASR correction found small
   seq2seq models (mT5/ByT5) outperform much larger decoder LLMs at this specific
   task.
2. **Swahili Gemma 1B** (`CraneAILabs/swahili-gemma-1b-GGUF`, Q4_K_M quant, Gemma-3
   1B decoder, instruction-tuned) -- chosen as the Swahili-specific decoder-LLM
   comparison point.

## Results

| Model | Mean Base WER | Mean Corrected WER | Delta |
|---|---|---|---|
| AfriTeva v2 Base | 0.6744 | 0.9758 | **+0.3014 (worse)** |
| Swahili Gemma 1B | 0.6744 | 0.9753 | **+0.3009 (worse)** |

Both candidates made transcripts **worse on average**, not better.

- **AfriTeva v2 Base** is a pretrain-only checkpoint with no instruction/generation
  fine-tuning. Fed a naive "sahihisha: <text>" prompt, it produced near-garbage
  output ("ni", single emoji characters, truncated fragments) on almost every item.
  Not a usable correction tool as-is.
- **Swahili Gemma 1B** is instruction-tuned and followed the correction prompt
  structurally, but consistently **hallucinated fluent, grammatically-correct
  Swahili sentences unrelated to the actual acoustic content** -- e.g. "ucangani uze
  mkuba ncenetaela ba taraje kupaneka" became "Uchungu wa mti wa mwaloni unazidi
  kukua" (a real, fluent, but entirely unrelated sentence). This is the exact
  failure mode predicted by the Hindi ASR-correction research: decoder LLMs
  "correct" toward plausible written language, not toward what was actually said.
  A handful of individual clips did improve (e.g. clip_029 omnilingual output went
  from WER 0.2 to 0.0, a perfect fix), but these wins were outnumbered 4:1 by
  clips that got meaningfully worse.

## Decision
**Do not add an LLM correction step to the ASR pipeline.** Neither tested model
is a net improvement; both would make the app's dictation/number-recognition less
accurate on average if shipped as-is.

## What would change this conclusion
A correction model specifically **fine-tuned** on paired (noisy-ASR-output ->
clean-reference) Swahili data, using a small seq2seq architecture (mT5/ByT5 class,
per the research), rather than an off-the-shelf pretrain-only or general-instruct
model. That would require building a training set and running a fine-tune job --
out of scope for this evaluation, which only tested off-the-shelf candidates.

## Data
Full per-clip results: Google Sheet "ASR Engine Benchmark - visual-impaired-assistant",
tabs "LLM Correction - Summary" and "LLM Correction - Detail".
Raw JSON: `/opt/data/cache/scratch/correction_models/{afriteva,gemma}_results_scored.json`
(local scratch, not committed -- see README for methodology/results only).
