import os, sys, zipfile

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
names = z.namelist()
total = sum(i.file_size for i in z.infolist())
print('APK entries: %d, uncompressed total: %.1f MB' % (len(names), total / 1048576.0))

def need(pred, msg, count_min=1):
    n = sum(1 for x in names if pred(x))
    assert n >= count_min, 'FAIL: %s (found %d, need >= %d)' % (msg, n, count_min)
    print('OK: %s (%d)' % (msg, n))

# --- AI TTS: Inflect-Nano-v2 (split VITS export + committed espeak lexicon)
need(lambda x: x == 'assets/inflect/duration.onnx', 'Inflect duration.onnx present')
need(lambda x: x == 'assets/inflect/decode.onnx', 'Inflect decode.onnx present')
need(lambda x: x == 'assets/inflect/lexicon-en.txt', 'espeak-en-us lexicon present')
dur = z.getinfo('assets/inflect/duration.onnx').file_size
dec = z.getinfo('assets/inflect/decode.onnx').file_size
assert 3e6 < dur < 6e6, 'duration.onnx size looks wrong: %d' % dur
assert 1e7 < dec < 2e7, 'decode.onnx size looks wrong: %d' % dec
lex = z.getinfo('assets/inflect/lexicon-en.txt').file_size
assert lex > 1000000, 'lexicon-en.txt looks truncated: %d' % lex

# --- DeepFilterNet3 removed in 7355: the models must NOT come back
# (they cost 8.4 MB of assets + 3 ORT sessions of RAM for no audible gain)
dfn3 = [x for x in names if x.startswith('assets/dfn3/')]
assert not dfn3, 'FAIL: stale DeepFilterNet3 models still packaged: %s' % dfn3
print('OK: no DeepFilterNet3 assets (removed in 7355)')

# --- native libs: only the ONNX Runtime AAR remains (sherpa was dropped)
need(lambda x: x.startswith('lib/arm64-v8a/'), 'arm64-v8a native libs', 1)
ort = [x for x in names if x.endswith('libonnxruntime.so') and 'arm64' in x]
assert ort, 'libonnxruntime.so missing for arm64-v8a (ORT AAR expected)'
assert not [x for x in names if 'libsherpa-onnx-jni' in x], 'stale sherpa JNI lib still packaged'
print('APK VERIFICATION PASSED')
