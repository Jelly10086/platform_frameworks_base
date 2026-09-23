/*
 * Copyright (C) 2026 The uwuAOSP Project
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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Drawable.ConstantState;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.NotificationListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Displays lyrics for the currently playing MediaSession. */
public abstract class LyricViewController implements DarkIconDispatcher.DarkReceiver {
    public static final int LYRIC_POSITION_OVERLAY = 0;
    public static final int LYRIC_POSITION_CLOCK_RIGHT = 1;

    private static final int HIDE_LYRIC_DELAY = 1200;
    private static final long POSITION_UPDATE_INTERVAL_MS = 250;
    private static final long FETCH_RETRY_DELAY_MS = 15_000;
    private static final int MAX_FETCH_RETRIES = 2;

    private final Context mContext;
    private final LyricViewHolder mOverlayLyricViewHolder;
    private final LyricViewHolder mInlineLyricViewHolder;
    private final View mTintReferenceView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mLyricExecutor = Executors.newCachedThreadPool();
    private final MediaSessionManager mMediaSessionManager;
    private final ComponentName mNotificationListenerComponent;
    private final ContrastColorUtil mNotificationColorUtil;
    private final UserTracker mUserTracker;
    private static volatile LyricViewController sDebugController;

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsChangedListener =
            this::onActiveSessionsChanged;
    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateCurrentSession();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refreshActiveSessions();
        }

        @Override
        public void onSessionDestroyed() {
            cancelPendingFetch();
            detachCurrentController();
            mCurrentLyrics = null;
            mCurrentTrackKey = null;
            stopLyric();
            refreshActiveSessions();
        }
    };
    private final Runnable mPositionUpdateRunnable = () -> {
        updateDisplayedLyric();
        if (mDebugLyrics != null || (mEnabled && mCurrentMediaController != null
                && isPlaybackActive(mCurrentMediaController.getPlaybackState()))) {
            mHandler.postDelayed(mPositionUpdateRunnable, POSITION_UPDATE_INTERVAL_MS);
        }
    };
    private final Runnable mRetryFetchRunnable = () -> {
        if (!mEnabled || mCurrentMediaController == null) {
            return;
        }
        mCurrentTrackKey = null;
        updateCurrentSession();
    };
    private final Runnable mRestoreLyricRunnable = () -> {
        mTemporarilyHidden = false;
        showLyricView(true);
    };
    private final ContentObserver mLyricSourcesObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (mEnabled) {
                cancelPendingFetch();
                mCurrentTrackKey = null;
                updateCurrentSession();
            }
        }
    };
    private final UserTracker.Callback mUserChangedCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, Context userContext) {
            if (mDestroyed) {
                return;
            }
            cancelPendingFetch();
            detachCurrentController();
            mCurrentLyrics = null;
            mCurrentTrackKey = null;
            mRetryTrackKey = null;
            mFetchRetryCount = 0;
            stopLyric();
            registerLyricSourcesObserver();
            registerSessionListener();
            refreshActiveSessions();
        }
    };

    private MediaController mCurrentMediaController;
    private LyricSource.Lyrics mCurrentLyrics;
    private String mCurrentTrackKey;
    private String mRetryTrackKey;
    private Future<?> mPendingFetch;
    private long mFetchGeneration;
    private int mFetchRetryCount;
    private LyricSource.Lyrics mDebugLyrics;
    private long mDebugStartElapsedRealtime;
    private boolean mEnabled;
    private boolean mStarted;
    private boolean mShowOnClockRight;
    private boolean mShowTranslation;
    private volatile boolean mWordTimingEnabled = true;
    private boolean mHideIconOnClockRight;
    private boolean mTemporarilyHidden;
    private boolean mSessionListenerRegistered;
    private boolean mSourcesObserverRegistered;
    private boolean mDestroyed;

    private int mOverlayTintColor = DarkIconDispatcher.DEFAULT_ICON_TINT;
    private int mInlineTintColor = DarkIconDispatcher.DEFAULT_ICON_TINT;
    private CharSequence mCurrentLyricText;
    private CharSequence mCurrentTranslatedText;

    public LyricViewController(Context context, View statusBar, View tintReferenceView) {
        mContext = context;
        mTintReferenceView = tintReferenceView;
        mOverlayLyricViewHolder = createLyricViewHolder(
                statusBar, R.id.lyric_container, R.id.lyric_icon, R.id.lyric_text,
                R.id.lyric_translation, true);
        mInlineLyricViewHolder = createLyricViewHolder(
                statusBar, R.id.lyric_inline_container, R.id.lyric_inline_icon,
                R.id.lyric_inline_text, R.id.lyric_inline_translation, false);

        mNotificationColorUtil = ContrastColorUtil.getInstance(mContext);
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mNotificationListenerComponent = new ComponentName(mContext, NotificationListener.class);
        mUserTracker = Dependency.get(UserTracker.class);

        Animation animationIn = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_in);
        Animation animationOut = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_out);
        setUpAnimations(mOverlayLyricViewHolder, animationIn, animationOut);
        if (mInlineLyricViewHolder != null) {
            setUpAnimations(mInlineLyricViewHolder, animationIn, animationOut);
        }

        View.OnTouchListener touchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mOverlayLyricViewHolder.mLyricContainer.removeCallbacks(mRestoreLyricRunnable);
                mTemporarilyHidden = true;
                hideLyricView(true);
                mOverlayLyricViewHolder.mLyricContainer.postDelayed(
                        mRestoreLyricRunnable, HIDE_LYRIC_DELAY);
            }
            return false;
        };
        mOverlayLyricViewHolder.mLyricContainer.setOnTouchListener(touchListener);
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mLyricContainer.setOnTouchListener(touchListener);
        }

        hideInactiveLyricViewsImmediately();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        mUserTracker.addCallback(mUserChangedCallback, command -> mHandler.post(command));
        registerSessionListener();
        registerLyricSourcesObserver();
        sDebugController = this;
    }

    public void destroy() {
        mDestroyed = true;
        mOverlayLyricViewHolder.mLyricContainer.removeCallbacks(mRestoreLyricRunnable);
        mHandler.removeCallbacks(mPositionUpdateRunnable);
        mHandler.removeCallbacks(mRetryFetchRunnable);
        mUserTracker.removeCallback(mUserChangedCallback);
        unregisterSessionListener();
        if (mSourcesObserverRegistered) {
            mContext.getContentResolver().unregisterContentObserver(mLyricSourcesObserver);
            mSourcesObserverRegistered = false;
        }
        cancelPendingFetch();
        detachCurrentController();
        if (sDebugController == this) {
            sDebugController = null;
        }
        mLyricExecutor.shutdownNow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    public void setEnabled(boolean enabled) {
        boolean wasEnabled = mEnabled;
        mEnabled = enabled;
        if (!mEnabled) {
            cancelPendingFetch();
            detachCurrentController();
            mCurrentLyrics = null;
            mCurrentTrackKey = null;
            mRetryTrackKey = null;
            mFetchRetryCount = 0;
            mDebugLyrics = null;
            stopLyric();
        } else if (!wasEnabled) {
            refreshActiveSessions();
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setLyricPosition(int position) {
        boolean showOnClockRight =
                position == LYRIC_POSITION_CLOCK_RIGHT && mInlineLyricViewHolder != null;
        if (mShowOnClockRight == showOnClockRight) {
            return;
        }

        LyricViewHolder previousHolder = getActiveLyricViewHolder();
        mShowOnClockRight = showOnClockRight;
        syncHolderContent(previousHolder, getActiveLyricViewHolder());
        updateIconVisibility();
        hideInactiveLyricViewsImmediately();
        postApplyTextTint();
        if (mStarted) {
            onLyricPositionChanged();
        }
    }

    public void setHideIconOnClockRight(boolean hideIconOnClockRight) {
        if (mHideIconOnClockRight == hideIconOnClockRight) {
            return;
        }
        mHideIconOnClockRight = hideIconOnClockRight;
        updateIconVisibility();
        postApplyTextTint();
    }

    public void setShowTranslation(boolean showTranslation) {
        if (mShowTranslation == showTranslation) {
            return;
        }
        mShowTranslation = showTranslation;
        CharSequence translatedText = getVisibleTranslatedText();
        setSubtitle(mOverlayLyricViewHolder, translatedText);
        if (mInlineLyricViewHolder != null) {
            setSubtitle(mInlineLyricViewHolder, translatedText);
        }
    }

    public void setWordTimingEnabled(boolean wordTimingEnabled) {
        if (mWordTimingEnabled == wordTimingEnabled) {
            return;
        }
        mWordTimingEnabled = wordTimingEnabled;
        if (mEnabled) {
            cancelPendingFetch();
            mCurrentTrackKey = null;
            updateCurrentSession();
        }
    }

    protected void onLyricPositionChanged() {
    }

    protected final boolean isClockRightMode() {
        return mShowOnClockRight && mInlineLyricViewHolder != null;
    }

    protected final boolean shouldShowLyricNow() {
        return mStarted && !mTemporarilyHidden;
    }

    private void onActiveSessionsChanged(List<MediaController> controllers) {
        selectActiveSession(controllers);
    }

    private void refreshActiveSessions() {
        if (mDestroyed || !mEnabled || mMediaSessionManager == null) {
            return;
        }
        try {
            selectActiveSession(mMediaSessionManager.getActiveSessionsForUser(
                    mNotificationListenerComponent, mUserTracker.getUserHandle()));
        } catch (SecurityException e) {
            stopLyric();
        }
    }

    private void selectActiveSession(List<MediaController> controllers) {
        if (!mEnabled) {
            return;
        }
        MediaController activeController = null;
        int activeScore = Integer.MIN_VALUE;
        if (controllers != null) {
            for (MediaController controller : controllers) {
                int score = scoreSession(controller);
                if (score > activeScore) {
                    activeController = controller;
                    activeScore = score;
                }
            }
        }
        if (isSameSession(activeController, mCurrentMediaController)) {
            updateCurrentSession();
            return;
        }

        cancelPendingFetch();
        detachCurrentController();
        mCurrentMediaController = activeController;
        mCurrentLyrics = null;
        mCurrentTrackKey = null;
        if (mCurrentMediaController == null) {
            stopLyric();
            return;
        }

        mCurrentMediaController.registerCallback(mMediaCallback, mHandler);
        updateCurrentSession();
    }

    private int scoreSession(MediaController controller) {
        if (controller == null) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (isPlaybackActive(controller.getPlaybackState())) {
            score += 1_000;
        }
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) {
            return score;
        }
        if (!TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))) {
            score += 100;
        }
        if (!TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))) {
            score += 50;
        }
        if (!TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))) {
            score += 25;
        }
        if (!TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))) {
            score += 10;
        }
        return score;
    }

    private boolean isPlaybackActive(PlaybackState state) {
        if (state == null) {
            return false;
        }
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_CONNECTING:
                return true;
            default:
                return false;
        }
    }

    private boolean isSameSession(MediaController first, MediaController second) {
        return first != null && second != null && (first == second
                || first.getSessionToken().equals(second.getSessionToken()));
    }

    private void updateCurrentSession() {
        if (!mEnabled || mCurrentMediaController == null) {
            return;
        }
        PlaybackState state = mCurrentMediaController.getPlaybackState();
        if (!isPlaybackActive(state)) {
            mHandler.removeCallbacks(mPositionUpdateRunnable);
            stopLyric();
            return;
        }
        mHandler.removeCallbacks(mPositionUpdateRunnable);
        mHandler.post(mPositionUpdateRunnable);
        MediaMetadata metadata = mCurrentMediaController.getMetadata();
        String title = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title) && metadata != null) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        }
        if (TextUtils.isEmpty(title)) {
            cancelPendingFetch();
            mCurrentLyrics = null;
            mCurrentTrackKey = null;
            stopLyric();
            return;
        }
        String packageName = mCurrentMediaController.getPackageName();
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) {
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long durationMs = metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        String sourceSetting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.STATUS_BAR_LYRIC_SOURCES, mUserTracker.getUserId());
        LyricSource.Track track = new LyricSource.Track(
                packageName, mediaId, title, artist, album, durationMs);
        String trackKey = track.getKey() + "\u0000" + sourceSetting;
        if (TextUtils.equals(mCurrentTrackKey, trackKey)) {
            updateDisplayedLyric();
            return;
        }

        cancelPendingFetch();
        mHandler.removeCallbacks(mRetryFetchRunnable);
        if (!TextUtils.equals(trackKey, mRetryTrackKey)) {
            mRetryTrackKey = null;
            mFetchRetryCount = 0;
        }
        mCurrentTrackKey = trackKey;
        mCurrentLyrics = null;
        stopLyric();
        final MediaController requestedController = mCurrentMediaController;
        final LyricSource.Track requestedTrack = track;
        final String requestedSourceSetting = sourceSetting;
        final long fetchGeneration = mFetchGeneration;
        mPendingFetch = mLyricExecutor.submit(() -> {
            LyricSource.Lyrics lyrics = null;
            List<LyricSource> sources = LyricSourceFactory.create(requestedSourceSetting);
            if (mWordTimingEnabled) {
                for (LyricSource source : sources) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    LyricSource.Lyrics enhancedLyrics = source.fetchEnhanced(requestedTrack);
                    if (enhancedLyrics != null && enhancedLyrics.hasWordTiming()) {
                        lyrics = enhancedLyrics;
                        break;
                    }
                }
            }
            if (lyrics == null) {
                for (LyricSource source : sources) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    lyrics = source.fetch(requestedTrack);
                    if (lyrics != null) {
                        break;
                    }
                }
            }
            final LyricSource.Lyrics fetchedLyrics = lyrics;
            mHandler.post(() -> {
                if (fetchGeneration != mFetchGeneration
                        || requestedController != mCurrentMediaController
                        || !TextUtils.equals(trackKey, mCurrentTrackKey)) {
                    return;
                }
                mPendingFetch = null;
                mCurrentLyrics = fetchedLyrics;
                if (mCurrentLyrics == null) {
                    scheduleFetchRetry(trackKey);
                    return;
                }
                mRetryTrackKey = null;
                mFetchRetryCount = 0;
                setIconForAllHolders(resolveSessionIcon(requestedController));
                updateDisplayedLyric();
            });
        });
    }

    private void updateDisplayedLyric() {
        if (mDebugLyrics != null) {
            LyricSource.Cue cue = mDebugLyrics.getCueAt(
                    SystemClock.elapsedRealtime() - mDebugStartElapsedRealtime);
            if (cue == null) {
                setTextForAllHolders("", null, null, 0);
                return;
            }
            setTextForAllHolders(cue.text, cue.translatedText, cue.words,
                    SystemClock.elapsedRealtime() - mDebugStartElapsedRealtime);
            if (!mStarted) {
                startLyric();
            }
            return;
        }
        if (mCurrentLyrics == null || mCurrentMediaController == null) {
            return;
        }
        PlaybackState state = mCurrentMediaController.getPlaybackState();
        if (!isPlaybackActive(state)) {
            return;
        }
        LyricSource.Cue cue = mCurrentLyrics.getCueAt(getPlaybackPosition(state));
        if (cue == null) {
            setTextForAllHolders("", null, null, 0);
            if (mStarted) {
                stopLyric();
            }
            return;
        }
        setTextForAllHolders(cue.text, cue.translatedText, cue.words, getPlaybackPosition(state));
        if (!mStarted) {
            startLyric();
        }
    }

    private long getPlaybackPosition(PlaybackState state) {
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            position += (long) (elapsed * state.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    private Drawable resolveSessionIcon(MediaController controller) {
        try {
            return mContext.getPackageManager().getApplicationIcon(controller.getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void detachCurrentController() {
        if (mCurrentMediaController != null) {
            mCurrentMediaController.unregisterCallback(mMediaCallback);
            mCurrentMediaController = null;
        }
        mHandler.removeCallbacks(mPositionUpdateRunnable);
    }

    private void cancelPendingFetch() {
        mFetchGeneration++;
        if (mPendingFetch != null) {
            mPendingFetch.cancel(true);
            mPendingFetch = null;
        }
        mHandler.removeCallbacks(mRetryFetchRunnable);
    }

    private void scheduleFetchRetry(String trackKey) {
        if (mFetchRetryCount >= MAX_FETCH_RETRIES) {
            return;
        }
        mRetryTrackKey = trackKey;
        mFetchRetryCount++;
        mHandler.removeCallbacks(mRetryFetchRunnable);
        mHandler.postDelayed(mRetryFetchRunnable, FETCH_RETRY_DELAY_MS);
    }

    private void registerSessionListener() {
        unregisterSessionListener();
        if (mDestroyed || mMediaSessionManager == null) {
            return;
        }
        mMediaSessionManager.addOnActiveSessionsChangedListener(
                mNotificationListenerComponent, mUserTracker.getUserHandle(),
                command -> mHandler.post(command), mSessionsChangedListener);
        mSessionListenerRegistered = true;
    }

    private void unregisterSessionListener() {
        if (mMediaSessionManager != null && mSessionListenerRegistered) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(mSessionsChangedListener);
            mSessionListenerRegistered = false;
        }
    }

    private void registerLyricSourcesObserver() {
        if (mDestroyed) {
            return;
        }
        if (mSourcesObserverRegistered) {
            mContext.getContentResolver().unregisterContentObserver(mLyricSourcesObserver);
        }
        mContext.getContentResolver().registerContentObserverAsUser(
                Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_LYRIC_SOURCES), false,
                mLyricSourcesObserver, mUserTracker.getUserHandle());
        mSourcesObserverRegistered = true;
    }

    public void startLyric() {
        if (!mStarted) {
            mStarted = true;
            showLyricView(true);
            postApplyTextTint();
        }
    }

    public void stopLyric() {
        if (mStarted) {
            mStarted = false;
            mTemporarilyHidden = false;
            mOverlayLyricViewHolder.mLyricContainer.removeCallbacks(mRestoreLyricRunnable);
            hideLyricView(true);
        }
        mCurrentLyricText = null;
        mCurrentTranslatedText = null;
        setTextForAllHolders(null, null, null, 0);
    }

    public abstract void showLyricView(boolean animate);

    public abstract void hideLyricView(boolean animate);

    public boolean isLyricStarted() {
        return mStarted;
    }

    protected final View getLyricView() {
        return getActiveLyricViewHolder().mLyricContainer;
    }

    protected final View getOverlayLyricView() {
        return mOverlayLyricViewHolder.mLyricContainer;
    }

    protected final View getInlineLyricView() {
        return mInlineLyricViewHolder == null ? null : mInlineLyricViewHolder.mLyricContainer;
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> area, float darkIntensity, int tint) {
        int textTint = mTintReferenceView != null
                ? DarkIconDispatcher.getTint(area, mTintReferenceView, tint) : tint;
        mOverlayTintColor = textTint;
        mInlineTintColor = textTint;
        applyTextTint();
    }

    private LyricViewHolder createLyricViewHolder(View statusBar, int containerId, int iconId,
            int textId, int subtitleTextId, boolean required) {
        View lyricContainer = statusBar.findViewById(containerId);
        if (lyricContainer == null) {
            if (required) {
                throw new IllegalStateException("Missing lyric container: " + containerId);
            }
            return null;
        }
        TextSwitcher subtitleTextSwitcher = lyricContainer.findViewById(subtitleTextId);
        return new LyricViewHolder(lyricContainer, lyricContainer.requireViewById(iconId),
                lyricContainer.requireViewById(textId), subtitleTextSwitcher);
    }

    private void setUpAnimations(LyricViewHolder holder, Animation animationIn,
            Animation animationOut) {
        holder.mTextSwitcher.setInAnimation(animationIn);
        holder.mTextSwitcher.setOutAnimation(animationOut);
        holder.mIconSwitcher.setInAnimation(animationIn);
        holder.mIconSwitcher.setOutAnimation(animationOut);
        if (holder.mSubtitleTextSwitcher != null) {
            holder.mSubtitleTextSwitcher.setInAnimation(null);
            holder.mSubtitleTextSwitcher.setOutAnimation(null);
        }
    }

    private LyricViewHolder getActiveLyricViewHolder() {
        return isClockRightMode() ? mInlineLyricViewHolder : mOverlayLyricViewHolder;
    }

    private void hideInactiveLyricViewsImmediately() {
        if (mOverlayLyricViewHolder != getActiveLyricViewHolder()) {
            hideViewImmediately(mOverlayLyricViewHolder.mLyricContainer);
        }
        if (mInlineLyricViewHolder != null && mInlineLyricViewHolder != getActiveLyricViewHolder()) {
            hideViewImmediately(mInlineLyricViewHolder.mLyricContainer);
        }
    }

    private void hideViewImmediately(View view) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
    }

    private void syncHolderContent(LyricViewHolder from, LyricViewHolder to) {
        if (from == null || to == null || from == to) {
            return;
        }
        Drawable currentDrawable = ((ImageView) from.mIconSwitcher.getCurrentView()).getDrawable();
        if (currentDrawable != null) {
            to.mIconSwitcher.setImageDrawable(copyDrawable(currentDrawable));
        }
        CharSequence currentText = ((TextView) from.mTextSwitcher.getCurrentView()).getText();
        if (!TextUtils.isEmpty(currentText)) {
            to.mTextSwitcher.setCurrentText(currentText);
        }
        syncSubtitle(from, to);
        if (to.mSubtitleTextSwitcher != null) {
            setSubtitle(to, getVisibleTranslatedText());
        }
        updateIconTint(to, getTintColorForHolder(to));
    }

    private void setIconForAllHolders(Drawable icon) {
        if (icon == null) {
            return;
        }
        mOverlayLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        }
        updateIconTint();
        updateIconVisibility();
    }

    private void setTextForAllHolders(CharSequence text, CharSequence translatedText,
            List<LyricSource.Word> words, long positionMs) {
        boolean lyricChanged = !TextUtils.equals(mCurrentLyricText, text);
        boolean translationChanged = !TextUtils.equals(mCurrentTranslatedText, translatedText);
        mCurrentLyricText = text;
        mCurrentTranslatedText = translatedText;
        if (lyricChanged) {
            mOverlayLyricViewHolder.mTextSwitcher.setText(text);
            if (mInlineLyricViewHolder != null) {
                mInlineLyricViewHolder.mTextSwitcher.setText(text);
            }
        }
        if (translationChanged) {
            setSubtitle(mOverlayLyricViewHolder, getVisibleTranslatedText());
            if (mInlineLyricViewHolder != null) {
                setSubtitle(mInlineLyricViewHolder, getVisibleTranslatedText());
            }
        }
        setWordTimingForAllHolders(words, positionMs);
        postApplyTextTint();
    }

    private void setWordTimingForAllHolders(List<LyricSource.Word> words, long positionMs) {
        setWordTiming(mOverlayLyricViewHolder, words, positionMs);
        if (mInlineLyricViewHolder != null) {
            setWordTiming(mInlineLyricViewHolder, words, positionMs);
        }
    }

    private void setWordTiming(LyricViewHolder holder, List<LyricSource.Word> words,
            long positionMs) {
        setWordTiming(holder.mTextSwitcher.getCurrentView(), words, positionMs);
        setWordTiming(holder.mTextSwitcher.getNextView(), words, positionMs);
    }

    private void setWordTiming(View view, List<LyricSource.Word> words, long positionMs) {
        if (view instanceof LyricTextView) {
            ((LyricTextView) view).setWordTiming(words, positionMs);
        }
    }

    static boolean setDebugLyrics(LyricSource.Lyrics lyrics) {
        LyricViewController controller = sDebugController;
        if (controller == null) {
            return false;
        }
        controller.mHandler.post(() -> controller.applyDebugLyrics(lyrics));
        return true;
    }

    private void applyDebugLyrics(LyricSource.Lyrics lyrics) {
        mDebugLyrics = lyrics;
        if (lyrics == null) {
            mHandler.removeCallbacks(mPositionUpdateRunnable);
            stopLyric();
            if (mEnabled) {
                mCurrentTrackKey = null;
                updateCurrentSession();
            }
            return;
        }
        mDebugStartElapsedRealtime = SystemClock.elapsedRealtime();
        mHandler.removeCallbacks(mPositionUpdateRunnable);
        updateDisplayedLyric();
        mHandler.post(mPositionUpdateRunnable);
    }

    private CharSequence getVisibleTranslatedText() {
        return mShowTranslation ? mCurrentTranslatedText : null;
    }

    private void updateIconVisibility() {
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mIconSwitcher.setVisibility(
                    mHideIconOnClockRight && isClockRightMode() ? View.GONE : View.VISIBLE);
        }
    }

    private Drawable copyDrawable(Drawable drawable) {
        ConstantState constantState = drawable.getConstantState();
        return constantState != null ? constantState.newDrawable().mutate() : drawable;
    }

    private void updateIconTint() {
        updateIconTint(mOverlayLyricViewHolder, mOverlayTintColor);
        if (mInlineLyricViewHolder != null) {
            updateIconTint(mInlineLyricViewHolder, mInlineTintColor);
        }
    }

    private void updateIconTint(LyricViewHolder holder, int tintColor) {
        Drawable drawable = ((ImageView) holder.mIconSwitcher.getCurrentView()).getDrawable();
        if (drawable == null) {
            return;
        }
        boolean isGrayscale = mNotificationColorUtil.isGrayscaleIcon(drawable);
        ColorStateList tintList = ColorStateList.valueOf(tintColor);
        ImageView currentView = (ImageView) holder.mIconSwitcher.getCurrentView();
        ImageView nextView = (ImageView) holder.mIconSwitcher.getNextView();
        currentView.setImageTintList(isGrayscale ? tintList : null);
        nextView.setImageTintList(isGrayscale ? tintList : null);
    }

    private void updateTextTint(LyricViewHolder holder, int tintColor) {
        ((TextView) holder.mTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) holder.mTextSwitcher.getNextView()).setTextColor(tintColor);
        if (holder.mSubtitleTextSwitcher != null) {
            ((TextView) holder.mSubtitleTextSwitcher.getCurrentView()).setTextColor(tintColor);
            ((TextView) holder.mSubtitleTextSwitcher.getNextView()).setTextColor(tintColor);
        }
    }

    private void setSubtitle(LyricViewHolder holder, CharSequence translatedText) {
        if (holder.mSubtitleTextSwitcher == null) {
            return;
        }
        if (TextUtils.isEmpty(translatedText)) {
            holder.mSubtitleTextSwitcher.setCurrentText("");
            holder.mSubtitleTextSwitcher.setVisibility(View.GONE);
            return;
        }
        int tintColor = getTintColorForHolder(holder);
        ((TextView) holder.mSubtitleTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) holder.mSubtitleTextSwitcher.getNextView()).setTextColor(tintColor);
        holder.mSubtitleTextSwitcher.setVisibility(View.VISIBLE);
        holder.mSubtitleTextSwitcher.setText(translatedText);
    }

    private void syncSubtitle(LyricViewHolder from, LyricViewHolder to) {
        if (from.mSubtitleTextSwitcher == null || to.mSubtitleTextSwitcher == null) {
            return;
        }
        CharSequence currentText = ((TextView) from.mSubtitleTextSwitcher.getCurrentView()).getText();
        if (TextUtils.isEmpty(currentText)) {
            to.mSubtitleTextSwitcher.setCurrentText("");
            to.mSubtitleTextSwitcher.setVisibility(View.GONE);
            return;
        }
        to.mSubtitleTextSwitcher.setVisibility(View.VISIBLE);
        to.mSubtitleTextSwitcher.setCurrentText(currentText);
    }

    private void applyTextTint() {
        updateTextTint(mOverlayLyricViewHolder, mOverlayTintColor);
        if (mInlineLyricViewHolder != null) {
            updateTextTint(mInlineLyricViewHolder, mInlineTintColor);
        }
        updateIconTint();
    }

    private int getTintColorForHolder(LyricViewHolder holder) {
        return holder == mInlineLyricViewHolder ? mInlineTintColor : mOverlayTintColor;
    }

    private void postApplyTextTint() {
        mOverlayLyricViewHolder.mLyricContainer.post(this::applyTextTint);
    }

    private static final class LyricViewHolder {
        final View mLyricContainer;
        final ImageSwitcher mIconSwitcher;
        final TextSwitcher mTextSwitcher;
        final TextSwitcher mSubtitleTextSwitcher;

        LyricViewHolder(View lyricContainer, ImageSwitcher iconSwitcher,
                TextSwitcher textSwitcher, TextSwitcher subtitleTextSwitcher) {
            mLyricContainer = lyricContainer;
            mIconSwitcher = iconSwitcher;
            mTextSwitcher = textSwitcher;
            mSubtitleTextSwitcher = subtitleTextSwitcher;
        }
    }
}
