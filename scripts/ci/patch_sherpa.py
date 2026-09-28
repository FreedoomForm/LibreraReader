#!/usr/bin/env python3
"""Patches sherpa-onnx source so the KokoroMultiLangLexicon is actually used.

The stock gate in kokoro-multi-lang-lexicon.cc sends ALL text through the
espeak phonemizer whenever the model metadata carries a non-empty "voice"
(which the kokoro models do: voice=en-us). The Kokoro-7M-Distill was trained
on misaki phonemes, and espeak's stream mismatches it (diphthong tokens A/W/O
are never produced, durations come out up to ~37% short). The lexicon file
(lexicon-us-en.txt) IS misaki-derived and covers the vocabulary, so prefer it
word-by-word and fall back to espeak only for out-of-lexicon words.
"""
import sys


def main():
    root = sys.argv[1]
    p = root + "/sherpa-onnx/csrc/kokoro-multi-lang-lexicon.cc"
    s = open(p).read()
    if "LIBRERA_LEXICON_FIRST" in s:
        print("already patched")
        return
    old = """    if (!voice.empty()) {
      return ConvertTextToTokenIDsWithEspeak(text, voice);
    }"""
    new = """#ifdef LIBRERA_LEXICON_FIRST
    // LIBRERA patch: when a lexicon is loaded, prefer it (misaki-derived
    // pronunciations for the Kokoro distills) and fall back to espeak per
    // OOV word, exactly like the empty-voice path below.
    if (!voice.empty() && word2ids_.empty()) {
      return ConvertTextToTokenIDsWithEspeak(text, voice);
    }
#else
    if (!voice.empty()) {
      return ConvertTextToTokenIDsWithEspeak(text, voice);
    }
#endif  // LIBRERA_LEXICON_FIRST"""
    assert old in s, "lexicon gate not found - sherpa-onnx version changed?"
    s = s.replace(old, new, 1)
    open(p, "w").write(s)
    print("patched:", p)


if __name__ == "__main__":
    main()
