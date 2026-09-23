import sys, wave
w = wave.open(sys.argv[1])
f, r = w.getnframes(), w.getframerate()
d = f / float(r)
print('test.wav: %d frames @ %d Hz = %.2f s' % (f, r, d))
assert d > 1.0, 'generated audio too short: %.2fs' % d
print('TTS SMOKE TEST PASSED (%.2fs of audio)' % d)
