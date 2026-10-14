# Training Data Access Verification — Swahili ASR "Verified Exact" Entries

Follow-up to the Swahili ASR landscape spreadsheet/dashboard. For each entry previously marked "Verified exact" (actual Swahili training size confirmed, not just a published headline figure), we checked whether the underlying audio+transcription data itself is actually downloadable — not just described in a paper/model card.

## Summary

| Entry | Training Data Accessible? | Source Link |
|---|---|---|
| FLEURS (sw_ke) | **Yes** — fully open | https://huggingface.co/datasets/google/fleurs (config `sw_ke`) |
| whisper-small-swh-finetuned | **Yes** — reuses FLEURS sw_ke | https://huggingface.co/datasets/google/fleurs |
| w2v-bert-2.0-swahili-asr | **Yes** — fully open | https://huggingface.co/datasets/badrex/swahili-speech-400hr |
| AfriSpeech (swahili_swa) | **Yes** — fully open (downloaded ourselves earlier this session) | https://huggingface.co/datasets/AfriSpeech/african-speech-public_v1 |
| Continued Pretraining (CPT) study — Common Voice 16.0 Swahili | **No** — gated, requires HF account + license click-through | https://huggingface.co/datasets/mozilla-foundation/common_voice_16_0 |
| Whisper Tiny FT (AfricanLP study) — 5,520 samples | **No** — only the training notebook is public, raw dataset never released | https://github.com/av55cs/speech-to-text-fine-tuning-whisper-tiny-for-swahili-asr |

## Detail per entry

### FLEURS (sw_ke) — Accessible
Verified by direct HuggingFace file listing (`list_repo_files`):
- `data/sw_ke/train.tsv`, `data/sw_ke/test.tsv`, `data/sw_ke/dev.tsv` — transcriptions
- `data/sw_ke/audio/{train,test,dev}.tar.gz` — raw audio
- `parquet-data/sw_ke/{train,test,validation}-00000-of-00001.parquet` — combined audio+text, ready for `datasets.load_dataset`

No authentication or license gate. This is also the training source for `whisper-small-swh-finetuned`.

### w2v-bert-2.0-swahili-asr — Accessible
Model card links to the dataset repo `badrex/swahili-speech-400hr` (88 parquet shards, `train` split). We pulled shard 0 directly and confirmed real content — columns: `audio_id, voice_creator_id, audio, audio_duration, gender, age_group, category, subcategory, location, transcription, transcription_with_tags`. Sample transcription pulled:

> "Haya ni maporomoko ya maji yakiteremka kutoka kwenye miamba mikubwa. Mizizi ya miti inaonekana ikishuka kutoka kwenye miamba iliyo juu na maporomoko hayo na kuna mimea na miti iliyozunguka eneo hilo."

No authentication or license gate. Also includes domain/category/location metadata, which is unusually rich for a Swahili ASR corpus.

### AfriSpeech (swahili_swa) — Accessible
Already downloaded and analyzed directly in this session's phonetic-balance study (see `dataset-phonetic-balance-analysis.md`). 1,320 real transcripts pulled (test+validation splits).

### CPT study (Common Voice 16.0 Swahili) — NOT accessible without auth
Attempted `list_repo_files('mozilla-foundation/common_voice_16_0', repo_type='dataset')` unauthenticated — returned 401 Unauthorized / "Repository Not Found" (HuggingFace's standard gating response for license-gated datasets). Same gating behavior confirmed earlier this session for Common Voice 17. Would require a HuggingFace account, explicit license acceptance via the web UI, and an auth token to actually download.

### Whisper Tiny FT (AfricanLP study) — NOT accessible
The paper (ACL Anthology, `2025.africanlp-1.11`) describes the 5,520-sample dataset's statistics (avg. transcript length, token counts) but provides no dataset download link. The authors' own GitHub repo (`av55cs/speech-to-text-fine-tuning-whisper-tiny-for-swahili-asr`) contains only the training notebook (`whisper-ai-finetuning.ipynb`) — the README explicitly states the code "expects audio files in a specific directory structure" + "CSV file with columns for file paths and transcriptions," implying the raw data was used locally/privately and never published alongside the code.

## Net takeaway

Of the 6 "verified exact" entries, **4 have fully open, directly downloadable training transcriptions** (FLEURS, w2v-bert-2.0's 400hr set, AfriSpeech, and whisper-small-swh-finetuned via FLEURS) — these can be pulled and inspected the same way we already did for AfriSpeech's phonetic-balance analysis. **2 are not independently reproducible**: Common Voice 16.0 is gated behind HuggingFace's standard license click-through (technically accessible with an account, just not anonymously), and the AfricanLP study's actual dataset was simply never released — only their code was.
