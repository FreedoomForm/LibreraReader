package com.foobnix.tts;

/**
 * Fired by the TTS service when a word starts being spoken in the
 * word-by-word mode. The reader view highlights that word on the page.
 */
public class MessageTTSWord {
    private final int page;
    private final int wordIndex;

    /**
     * @param page      0-based index of the book page being read
     * @param wordIndex flat index of the word in the page's reading-order word
     *                  list; -1 means "clear the highlight"
     */
    public MessageTTSWord(int page, int wordIndex) {
        this.page = page;
        this.wordIndex = wordIndex;
    }

    public int getPage() {
        return page;
    }

    public int getWordIndex() {
        return wordIndex;
    }
}
