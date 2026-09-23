import os, sys
import sherpa_onnx

model_dir, out_path = sys.argv[1], sys.argv[2]
d = model_dir

kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(
    model=os.path.join(d, 'model.int8.onnx'),
    voices=os.path.join(d, 'voices.bin'),
    tokens=os.path.join(d, 'tokens.txt'),
    lexicon=','.join([os.path.join(d, 'lexicon-us-en.txt'),
                      os.path.join(d, 'lexicon-gb-en.txt'),
                      os.path.join(d, 'lexicon-zh.txt')]),
    data_dir=os.path.join(d, 'espeak-ng-data'),
    dict_dir=os.path.join(d, 'dict'),
)
cfg = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro, num_threads=4, debug=False),
    rule_fsts=','.join([os.path.join(d, 'date-zh.fst'),
                        os.path.join(d, 'number-zh.fst'),
                        os.path.join(d, 'phone-zh.fst')]),
)
print('Loading Kokoro from', d)
tts = sherpa_onnx.OfflineTts(cfg)
audio = tts.generate('Hello from the offline voice engine of Librera reader.', sid=0, speed=1.0)
assert audio.samples and audio.sample_rate > 0, 'no audio generated'
if hasattr(audio, 'save_wav'):
    audio.save_wav(out_path)
elif hasattr(sherpa_onnx, 'write_wave'):
    sherpa_onnx.write_wave(out_path, audio.samples, audio.sample_rate)
else:
    import wave, struct
    w = wave.open(out_path, 'wb')
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(audio.sample_rate)
    w.writeframes(b''.join(struct.pack('<h', max(-32768, min(32767, int(s * 32767))))
                           for s in audio.samples))
    w.close()
print('generated %d samples @ %d Hz' % (len(audio.samples), audio.sample_rate))
print('PYTHON TTS TEST PASSED')
