import os, re, subprocess, sys, tempfile, zipfile

apk = sys.argv[1]
java_pkg_dir = sys.argv[2]
z = zipfile.ZipFile(apk)
names = z.namelist()
total = sum(i.file_size for i in z.infolist())
print('APK entries: %d, uncompressed total: %.1f MB' % (len(names), total / 1048576.0))

def need(pred, msg, count_min=1):
    n = sum(1 for x in names if pred(x))
    assert n >= count_min, 'FAIL: %s (found %d, need >= %d)' % (msg, n, count_min)
    print('OK: %s (%d)' % (msg, n))

need(lambda x: x == 'assets/kokoro/model.int8.onnx', 'model.int8.onnx present')
need(lambda x: x == 'assets/kokoro/voices.bin', 'voices.bin present')
need(lambda x: x == 'assets/kokoro/tokens.txt', 'tokens.txt present')
need(lambda x: x.startswith('assets/kokoro/lexicon-'), 'kokoro lexicons', 3)
need(lambda x: x.startswith('assets/kokoro/espeak-ng-data/'), 'espeak-ng-data files', 300)
need(lambda x: x.startswith('assets/kokoro/dict/'), 'zh dict files', 3)
need(lambda x: x.startswith('assets/kokoro/') and x.endswith('.fst'), 'zh rule fsts', 3)
need(lambda x: x.startswith('lib/arm64-v8a/'), 'arm64-v8a native libs', 2)

so = [x for x in names if x.endswith('libsherpa-onnx-jni.so') and 'arm64' in x]
assert so, 'libsherpa-onnx-jni.so missing for arm64-v8a'
tmp = tempfile.mkdtemp()
sp = os.path.join(tmp, 'libsherpa-onnx-jni.so')
open(sp, 'wb').write(z.read(so[0]))
syms = subprocess.check_output(['nm', '-D', sp]).decode('utf-8', 'ignore')

declared = set()
for root, _, files in os.walk(java_pkg_dir):
    for fn in files:
        if not fn.endswith('.java'):
            continue
        cls = fn[:-5]
        txt = open(os.path.join(root, fn), 'r', errors='ignore').read()
        for m in re.finditer(r'native\s+[\w<>\[\]]+\s+(\w+)\s*\(', txt):
            declared.add('Java_com_k2fsa_sherpa_onnx_%s_%s' % (cls, m.group(1)))

missing = sorted(s for s in declared if s not in syms)
for s in sorted(declared):
    print(('OK  ' if s in syms else 'MISS') + ' ' + s)
assert not missing, 'JNI symbol mismatch: %s' % missing
print('APK VERIFICATION PASSED')
