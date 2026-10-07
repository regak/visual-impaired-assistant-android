
import json, os, re, string, time, sys
sys.path.insert(0, "/opt/data/cache/scratch/asr_bench_venv/lib/python3.13/site-packages")

CLIPS_DIR = "/opt/data/cache/scratch/asr_bench/clips"
MANIFEST = "/opt/data/cache/scratch/asr_bench/manifest.json"
OUT = "/opt/data/cache/scratch/asr_bench/results.json"

with open(MANIFEST) as f:
    manifest = json.load(f)

def normalize(s):
    s = s.lower().strip()
    s = s.translate(str.maketrans("", "", string.punctuation))
    s = re.sub(r"\s+", " ", s)
    return s

def wer(ref, hyp):
    r = normalize(ref).split()
    h = normalize(hyp).split()
    # levenshtein word-level
    n, m = len(r), len(h)
    dp = [[0]*(m+1) for _ in range(n+1)]
    for i in range(n+1): dp[i][0] = i
    for j in range(m+1): dp[0][j] = j
    for i in range(1, n+1):
        for j in range(1, m+1):
            if r[i-1] == h[j-1]:
                dp[i][j] = dp[i-1][j-1]
            else:
                dp[i][j] = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
    return dp[n][m] / max(1, n)

def cer(ref, hyp):
    r = normalize(ref).replace(" ", "")
    h = normalize(hyp).replace(" ", "")
    n, m = len(r), len(h)
    dp = [[0]*(m+1) for _ in range(n+1)]
    for i in range(n+1): dp[i][0] = i
    for j in range(m+1): dp[0][j] = j
    for i in range(1, n+1):
        for j in range(1, m+1):
            if r[i-1] == h[j-1]:
                dp[i][j] = dp[i-1][j-1]
            else:
                dp[i][j] = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
    return dp[n][m] / max(1, n)

import onnx_asr

print("Loading Omnilingual CTC 300M int8 (local, matches the app's current engine)...")
t0 = time.time()
omni = onnx_asr.load_model(
    "omnilingual-ctc",
    "/opt/data/cache/scratch/asr_bench/models/omni-ctc-onnx",
    quantization="int8",
)
print("  loaded in", round(time.time()-t0, 1), "s")

print("Loading Whisper tiny (sherpa-onnx)...")
t0 = time.time()
import sherpa_onnx
whisper_dir = "/opt/data/cache/scratch/asr_bench/models/sherpa-onnx-whisper-tiny"
whisper_recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=f"{whisper_dir}/tiny-encoder.int8.onnx",
    decoder=f"{whisper_dir}/tiny-decoder.int8.onnx",
    tokens=f"{whisper_dir}/tiny-tokens.txt",
    num_threads=1,
    decoding_method="greedy_search",
    language="sw",
    task="transcribe",
)
print("  loaded in", round(time.time()-t0, 1), "s")

print("Loading MMS-1B-all (Swahili adapter, int8)...")
t0 = time.time()
mms = onnx_asr.load_model(
    "wav2vec2-adapters",
    "/opt/data/cache/scratch/asr_bench/models/mms-1b-all-onnx",
    quantization="int8",
)
print("  loaded in", round(time.time()-t0, 1), "s")

import soundfile as sf
import numpy as np

def read_mono16k(path):
    data, sr = sf.read(path, dtype="float32")
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != 16000:
        # simple resample via linear interpolation (test clips are already close to 16k/48k)
        duration = len(data) / sr
        target_len = int(duration * 16000)
        x_old = np.linspace(0, duration, len(data))
        x_new = np.linspace(0, duration, target_len)
        data = np.interp(x_new, x_old, data).astype("float32")
        sr = 16000
    return data, sr

def transcribe_whisper(path):
    data, sr = read_mono16k(path)
    stream = whisper_recognizer.create_stream()
    stream.accept_waveform(sr, data)
    whisper_recognizer.decode_stream(stream)
    return stream.result.text

results = []
for i, item in enumerate(manifest):
    clip_path = os.path.join(CLIPS_DIR, item["clip"])
    ref = item["sentence"]
    row = {"clip": item["clip"], "reference": ref}

    t0 = time.time()
    try:
        row["omnilingual"] = omni.recognize(clip_path)
    except Exception as e:
        row["omnilingual"] = f"[ERROR: {e}]"
    row["omnilingual_t"] = round(time.time() - t0, 2)

    t0 = time.time()
    try:
        row["whisper_tiny"] = transcribe_whisper(clip_path)
    except Exception as e:
        row["whisper_tiny"] = f"[ERROR: {e}]"
    row["whisper_tiny_t"] = round(time.time() - t0, 2)

    t0 = time.time()
    try:
        row["mms"] = mms.recognize(clip_path, language="swh")
    except Exception as e:
        row["mms"] = f"[ERROR: {e}]"
    row["mms_t"] = round(time.time() - t0, 2)

    results.append(row)
    print(f"[{i+1}/{len(manifest)}] ref={ref!r}")
    print(f"    omni={row['omnilingual']!r} ({row['omnilingual_t']}s)")
    print(f"    whisper={row['whisper_tiny']!r} ({row['whisper_tiny_t']}s)")
    print(f"    mms={row['mms']!r} ({row['mms_t']}s)")

with open(OUT, "w") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)

# aggregate
for engine in ["omnilingual", "whisper_tiny", "mms"]:
    wers = [wer(r["reference"], r[engine]) for r in results if not str(r[engine]).startswith("[ERROR")]
    cers = [cer(r["reference"], r[engine]) for r in results if not str(r[engine]).startswith("[ERROR")]
    times = [r[f"{engine}_t"] for r in results]
    n_err = sum(1 for r in results if str(r[engine]).startswith("[ERROR"))
    print(f"\n=== {engine} ===")
    print(f"  N clips scored: {len(wers)} (errors: {n_err})")
    print(f"  Mean WER: {sum(wers)/len(wers):.4f}" if wers else "  no scoreable results")
    print(f"  Mean CER: {sum(cers)/len(cers):.4f}" if cers else "")
    print(f"  Mean inference time: {sum(times)/len(times):.2f}s")

print("\nWrote per-clip results to", OUT)
