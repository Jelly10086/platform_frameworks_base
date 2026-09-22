/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 *               2022 Project Kaleidoscope
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uwuaosp.systemui.lyric;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.icu.text.Bidi;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

public class LyricTextView extends TextView {
    private boolean mStopped = true;
    private int mTextWidth;
    private int mScrollSpeed = 4;
    private int mOffset = 0;
    private String mText;
    private boolean mTextRtl;
    private List<LyricSource.Word> mWordTiming = Collections.emptyList();
    private long mWordPositionMs;

    private static final int START_SCROLL_DELAY = 500;
    private static final int INVALIDATE_DELAY = 10;

    private final Runnable mStartScrollRunnable = this::startScroll;
    private final Runnable mInvalidateRunnable = this::invalidate;

    public LyricTextView(Context context) {
        this(context, null);
    }

    public LyricTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0); // com.android.internal.R.attr.textViewStyle
    }

    public LyricTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public LyricTextView(Context context, AttributeSet attrs, int defStyleAttr,
                         int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mStartScrollRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        stopScroll();
        mWordTiming = Collections.emptyList();
        if (text != null) {
            mText = text.toString();
            mTextRtl = Bidi.getBaseDirection(mText) == Bidi.RTL;
            if (mTextRtl) {
                getPaint().setTextAlign(Paint.Align.RIGHT);
                mOffset = -1;
            } else {
                getPaint().setTextAlign(Paint.Align.LEFT);
                if (View.LAYOUT_DIRECTION_RTL == getLayoutDirection()) {
                    mOffset = -1;
                } else {
                    mOffset = 0;
                }
            }
            mTextWidth = (int) getPaint().measureText(mText);
            postInvalidate();
            postDelayed(mStartScrollRunnable, START_SCROLL_DELAY);
        } else {
            mText = null;
        }
    }

    @Override
    public void setTextColor(int color) {
        super.setTextColor(color);
        getPaint().setColor(color);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (canvas != null && mText != null && !mWordTiming.isEmpty()) {
            drawWordTimedText(canvas);
            return;
        }
        boolean viewRtl = View.LAYOUT_DIRECTION_RTL == getLayoutDirection();
        if (mTextRtl && mOffset == -1) {
            mOffset = getWidth();
        } else if (viewRtl && mOffset == -1) {
            mOffset = Math.max(0, getWidth() - mTextWidth);
        }
        if (canvas != null && mText != null) {
            float y = getHeight() / 2.0f + Math.abs(getPaint().ascent() + getPaint().descent()) / 2;
            canvas.drawText(mText, mOffset, y, getPaint());
        }
        if (!mStopped) {
            if (!mTextRtl) {
                if (getWidth() - mOffset + mScrollSpeed >= mTextWidth) {
                    mOffset = getWidth() > mTextWidth && !viewRtl ? 0 : getWidth() - mTextWidth;
                    stopScroll();
                } else {
                    mOffset -= mScrollSpeed;
                }
            } else {
                if (mOffset + mScrollSpeed >= mTextWidth) {
                    mOffset = Math.max(getWidth(), mTextWidth);
                    stopScroll();
                } else {
                    mOffset += mScrollSpeed;
                }
            }
            invalidateAfter(INVALIDATE_DELAY);
        }
    }

    public void setWordTiming(List<LyricSource.Word> words, long positionMs) {
        if (words == null || words.isEmpty()) {
            mWordTiming = Collections.emptyList();
            mWordPositionMs = 0;
            postInvalidate();
            return;
        }
        mWordTiming = words;
        mWordPositionMs = Math.max(0, positionMs);
        stopScroll();
        postInvalidate();
    }

    private void drawWordTimedText(Canvas canvas) {
        Paint paint = getPaint();
        boolean rtl = mTextRtl;
        float textWidth = paint.measureText(mText);
        float origin = rtl ? getWidth() : Math.max(0, getWidth() - textWidth);
        if (!rtl && View.LAYOUT_DIRECTION_RTL != getLayoutDirection()) {
            origin = 0;
        }
        float y = getHeight() / 2.0f + Math.abs(paint.ascent() + paint.descent()) / 2;
        int originalColor = paint.getColor();
        int originalAlpha = paint.getAlpha();
        Paint.Align originalAlign = paint.getTextAlign();

        paint.setAlpha((int) (originalAlpha * 0.45f));
        paint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText(mText, origin, y, paint);
        paint.setAlpha(originalAlpha);

        float highlightedWidth = 0;
        int textOffset = 0;
        for (LyricSource.Word word : mWordTiming) {
            if (word.text == null || word.text.isEmpty()) {
                continue;
            }
            int wordEnd = Math.min(mText.length(), textOffset + word.text.length());
            if (wordEnd <= textOffset) {
                continue;
            }
            String wordText = mText.substring(textOffset, wordEnd);
            float wordWidth = paint.measureText(wordText);
            if (mWordPositionMs >= word.endMs || word.endMs <= word.beginMs
                    && mWordPositionMs >= word.beginMs) {
                highlightedWidth += wordWidth;
            } else if (mWordPositionMs > word.beginMs && word.endMs > word.beginMs) {
                float progress = (float) (mWordPositionMs - word.beginMs)
                        / (float) (word.endMs - word.beginMs);
                highlightedWidth += wordWidth * Math.min(1f, Math.max(0f, progress));
                break;
            } else {
                break;
            }
            textOffset = wordEnd;
            if (textOffset >= mText.length()) {
                break;
            }
        }
        if (highlightedWidth > 0) {
            canvas.save();
            if (rtl) {
                canvas.clipRect(getWidth() - highlightedWidth, 0, getWidth(), getHeight());
            } else {
                canvas.clipRect(origin, 0, origin + highlightedWidth, getHeight());
            }
            canvas.drawText(mText, origin, y, paint);
            canvas.restore();
        }
        paint.setColor(originalColor);
        paint.setAlpha(originalAlpha);
        paint.setTextAlign(originalAlign);
    }

    private void invalidateAfter(long delay) {
        removeCallbacks(mInvalidateRunnable);
        postDelayed(mInvalidateRunnable, delay);
    }


    public void startScroll() {
        mStopped = false;
        postInvalidate();
    }

    public void stopScroll() {
        mStopped = true;
        removeCallbacks(mStartScrollRunnable);
        postInvalidate();
    }

    public void setScrollSpeed(int scrollSpeed) {
        this.mScrollSpeed = scrollSpeed;
    }
}
