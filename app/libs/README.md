# Vendored native libraries

`sherpa-onnx-1.13.8.aar` is not committed (50MB, prebuilt binary). Download
it before building:

```bash
cd app/libs
curl -fsSL -o sherpa-onnx-1.13.8.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
```

This isn't published to Maven Central/Google's repo — k2-fsa ships it as a
GitHub release asset instead. Bundles `libonnxruntime.so` + sherpa-onnx's
own JNI/C++/C-API `.so` files for arm64-v8a, armeabi-v7a, x86, x86_64 — no
separate onnxruntime-android dependency needed.

Verified (this pass) via `javap` on `classes.jar` inside the AAR that this
version of the Kotlin bindings exposes
`com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig` and
`OfflineModelConfig.omnilingual`, i.e. the Omnilingual ASR CTC model family
(see PLAN.md / README.md) is already wired into this AAR's API — no newer
sherpa-onnx version is required for Phase 1.
