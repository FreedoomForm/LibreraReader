package com.foobnix.tts;

import android.graphics.RectF;

/**
 * Fired by the TTS service when a word starts being spoken in the
 * word-by-word mode. The reader view highlights that word on the page.
 */
public class MessageTTSWord {
    private final int page;
    private final int wordIndex;
    /**
     * Rectangle of the word in page coordinates, captured from the same word
     * boxes the TTS aligns against. Delivering the rectangle directly removes
     * any need for the reader view to re-read the page (which used to fail for
     * reflowed books, where the TTS service and the viewer paginate
     * differently). May be null - the index is then used as a fallback.
     */
    private final RectF rect;

    /**
     * @param page      0-based index of the book page being read
     * @param wordIndex flat index of the word in the page's reading-order word
     *                  list; -1 means "clear the highlight"
     */
    public MessageTTSWord(int page, int wordIndex) {
        this(page, wordIndex, null);
    }

    public MessageTTSWord(int page, int wordIndex, RectF rect) {
        this.page = page;
        this.wordIndex = wordIndex;
        this.rect = rect;
    }

    public int getPage() {
        return page;
    }

    public int getWordIndex() {
        return wordIndex;
    }

    public RectF getRect() {
        return rect;
    }
}
