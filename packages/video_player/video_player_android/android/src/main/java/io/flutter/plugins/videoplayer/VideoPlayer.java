// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;

import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.Rational;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import io.flutter.view.TextureRegistry.SurfaceProducer;
import java.util.ArrayList;
import java.util.List;

/**
 * A class responsible for managing video playback using {@link ExoPlayer}.
 *
 * <p>It provides methods to control playback, adjust volume, and handle seeking.
 */
public abstract class VideoPlayer implements VideoPlayerInstanceApi {
  @NonNull protected final VideoPlayerCallbacks videoPlayerEvents;
  @Nullable protected final SurfaceProducer surfaceProducer;
  @Nullable private DisposeHandler disposeHandler;
  @NonNull protected ExoPlayer exoPlayer;
  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi @Nullable protected DefaultTrackSelector trackSelector;

  /** When the live window was last refreshed. See {@link #getLiveOffset()}. */
  private volatile long lastTimelineChangeRealtimeMs = C.TIME_UNSET;

  /**
   * Ceiling on the staleness correction in {@link #getLiveOffset()}, so a
   * timeline that stops refreshing entirely reports a stuck figure rather than
   * one that climbs forever.
   */
  private static final long MAX_TIMELINE_STALENESS_MS = 60_000;

  /** A closure-compatible signature since {@link java.util.function.Supplier} is API level 24. */
  public interface ExoPlayerProvider {
    /**
     * Returns a new {@link ExoPlayer}.
     *
     * @return new instance.
     */
    @NonNull
    ExoPlayer get();
  }

  /** A handler to run when dispose is called. */
  public interface DisposeHandler {
    void onDispose();
  }

  /**
   * Delegate that handles platform-level PiP entry, backed by the Activity.
   *
   * <p>Implemented by {@link VideoPlayerPlugin} once it is attached to an Activity.
   */
  public interface PipDelegate {
    /** Enters system picture-in-picture mode for the given player. */
    @RequiresApi(api = Build.VERSION_CODES.O)
    void enterPictureInPicture(
        @NonNull VideoPlayer player,
        @NonNull Rational aspectRatio,
        @Nullable Rect sourceRectHint);

    /**
     * Called when the Dart side calls {@code setAutomaticallyStartPictureInPicture}.
     *
     * <p>The plugin uses this to pre-register the active PiP player and, on Android 12+,
     * to set {@code autoEnterEnabled} on {@link android.app.PictureInPictureParams} so the
     * system enters PiP automatically on background — eliminating the async timing race
     * that occurred when relying solely on {@code onUserLeaveHint}.
     */
    void onAutoStartPipEnabledChanged(@NonNull VideoPlayer player, boolean enabled);

    /**
     * Called whenever the player's actual {@code isPlaying} state changes.
     *
     * <p>While this is the active PiP player and the Activity is in PiP mode,
     * the plugin refreshes the PiP play/pause action so its icon always
     * reflects the true playback state.
     */
    void onPlayingStateChanged(@NonNull VideoPlayer player, boolean isPlaying);
  }

  @Nullable private PipDelegate pipDelegate;
  @Nullable private Rect pipSourceRectHint;
  private boolean autoStartPipEnabled = false;

  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  // Error thrown for this-escape warning on JDK 21+ due to
  // https://bugs.openjdk.org/browse/JDK-8015831.
  // Keeping behavior as-is and addressing the warning could cause a regression:
  // https://github.com/flutter/packages/pull/10193
  @SuppressWarnings("this-escape")
  public VideoPlayer(
      @NonNull VideoPlayerCallbacks events,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options,
      @Nullable SurfaceProducer surfaceProducer,
      @NonNull ExoPlayerProvider exoPlayerProvider) {
    this.videoPlayerEvents = events;
    this.surfaceProducer = surfaceProducer;
    exoPlayer = exoPlayerProvider.get();

    // Try to get the track selector from the ExoPlayer if it was built with one
    if (exoPlayer.getTrackSelector() instanceof DefaultTrackSelector) {
      trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
    }

    exoPlayer.setMediaItem(mediaItem);
    exoPlayer.prepare();
    exoPlayer.addListener(createExoPlayerEventListener(exoPlayer, surfaceProducer));
    // Keep the system PiP play/pause action in sync with the actual playback
    // state. Without this the action icon only refreshes on PiP entry or on a
    // button tap, leaving it stale when the state changes for any other reason
    // (buffering finishing, stream ending, pause from elsewhere, etc.).
    exoPlayer.addListener(
        new Player.Listener() {
          @Override
          public void onIsPlayingChanged(boolean isPlaying) {
            if (pipDelegate != null) {
              pipDelegate.onPlayingStateChanged(VideoPlayer.this, isPlaying);
            }
          }

          @Override
          public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            // Marks the moment the live window was last refreshed, which is
            // the only moment its default position is up to date. See
            // getLiveOffset().
            lastTimelineChangeRealtimeMs = SystemClock.elapsedRealtime();
          }
        });
    setAudioAttributes(exoPlayer, options.mixWithOthers);
  }

  public void setDisposeHandler(@Nullable DisposeHandler handler) {
    disposeHandler = handler;
  }

  @NonNull
  protected abstract ExoPlayerEventListener createExoPlayerEventListener(
      @NonNull ExoPlayer exoPlayer, @Nullable SurfaceProducer surfaceProducer);

  private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
    exoPlayer.setAudioAttributes(
        new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        !isMixMode);
  }

  @Override
  public void play() {
    exoPlayer.play();
  }

  @Override
  public void pause() {
    exoPlayer.pause();
  }

  public boolean isPlaying() {
    return exoPlayer.isPlaying();
  }

  @Override
  public void setLooping(boolean looping) {
    exoPlayer.setRepeatMode(looping ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  @Override
  public void setVolume(double volume) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, volume));
    exoPlayer.setVolume(bracketedValue);
  }

  @Override
  public void setPlaybackSpeed(double speed) {
    // We do not need to consider pitch and skipSilence for now as we do not handle them and
    // therefore never diverge from the default values.
    final PlaybackParameters playbackParameters = new PlaybackParameters((float) speed);

    exoPlayer.setPlaybackParameters(playbackParameters);
  }

  @Override
  public long getCurrentPosition() {
    return exoPlayer.getCurrentPosition();
  }

  @Override
  public long getBufferedPosition() {
    return exoPlayer.getBufferedPosition();
  }

  @Override
  public long getLiveOffset() {
    Timeline timeline = exoPlayer.getCurrentTimeline();
    if (timeline.isEmpty()) {
      return -1;
    }

    Timeline.Window window =
        timeline.getWindow(exoPlayer.getCurrentMediaItemIndex(), new Timeline.Window());
    if (!window.isLive()) {
      return -1;
    }

    // The player's own figure is wall-clock anchored and needs no help, but it
    // is only available when the playlist carries an EXT-X-PROGRAM-DATE-TIME.
    // Rumble's live-hls-dvr playlists do not, so this is usually TIME_UNSET.
    long playerOffsetMs = exoPlayer.getCurrentLiveOffset();
    if (playerOffsetMs != C.TIME_UNSET) {
      return Math.max(0, playerOffsetMs);
    }

    // Without a date time, the gap between the window's default position (the
    // live edge) and the current position is the only self-consistent measure
    // available: both are relative to the start of the same window, so when
    // that window slides or re-anchors the two move together and the gap is
    // unaffected. That matters most across a pause, where the position can
    // re-anchor by far more than the paused time -- 47s after a 20s pause on
    // the stream measured -- which is why extrapolating from the change in
    // position instead reported minutes of lag that were not real.
    //
    // Its one defect is staleness. The default position is a step function,
    // advancing only when the playlist refreshes, while the position advances
    // continuously; so between refreshes the gap slides down about a second
    // per second and then jumps back up. That is the sawtooth behind the
    // flickering readout.
    //
    // The size of that error is known exactly -- it is the time since the last
    // refresh. Adding it back cancels the slide while playback keeps pace with
    // the broadcast, and leaves the figure growing correctly while playback is
    // paused, seeked back or stalled.
    long rawOffsetMs = window.getDefaultPositionMs() - exoPlayer.getCurrentPosition();
    long stalenessMs = 0;
    if (lastTimelineChangeRealtimeMs != C.TIME_UNSET) {
      stalenessMs = SystemClock.elapsedRealtime() - lastTimelineChangeRealtimeMs;
      stalenessMs = Math.max(0, Math.min(stalenessMs, MAX_TIMELINE_STALENESS_MS));
    }

    return Math.max(0, rawOffsetMs + stalenessMs);
  }

  @Override
  public long getDuration() {
    long duration = exoPlayer.getDuration();
    // The timeline is unknown until the media has been prepared, and for a
    // live stream this keeps growing as the window does.
    return duration == C.TIME_UNSET ? 0 : duration;
  }

  @Override
  public void seekTo(long position) {
    exoPlayer.seekTo(position);
  }

  @NonNull
  public ExoPlayer getExoPlayer() {
    return exoPlayer;
  }

  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @Override
  public @NonNull NativeAudioTrackData getAudioTracks() {
    List<ExoPlayerAudioTrackData> audioTracks = new ArrayList<>();

    // Get the current tracks from ExoPlayer
    Tracks tracks = exoPlayer.getCurrentTracks();

    // Iterate through all track groups
    for (int groupIndex = 0; groupIndex < tracks.getGroups().size(); groupIndex++) {
      Tracks.Group group = tracks.getGroups().get(groupIndex);

      // Only process audio tracks
      if (group.getType() == C.TRACK_TYPE_AUDIO) {
        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
          Format format = group.getTrackFormat(trackIndex);
          boolean isSelected = group.isTrackSelected(trackIndex);

          // Create audio track data with metadata
          ExoPlayerAudioTrackData audioTrack =
              new ExoPlayerAudioTrackData(
                  (long) groupIndex,
                  (long) trackIndex,
                  format.label,
                  format.language,
                  isSelected,
                  format.bitrate != Format.NO_VALUE ? (long) format.bitrate : null,
                  format.sampleRate != Format.NO_VALUE ? (long) format.sampleRate : null,
                  format.channelCount != Format.NO_VALUE ? (long) format.channelCount : null,
                  format.codecs != null ? format.codecs : null);

          audioTracks.add(audioTrack);
        }
      }
    }
    return new NativeAudioTrackData(audioTracks);
  }

  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @Override
  public void selectAudioTrack(long groupIndex, long trackIndex) {
    if (trackSelector == null) {
      throw new IllegalStateException("Cannot select audio track: track selector is null");
    }

    // Get current tracks
    Tracks tracks = exoPlayer.getCurrentTracks();

    if (groupIndex < 0 || groupIndex >= tracks.getGroups().size()) {
      throw new IllegalArgumentException(
          "Cannot select audio track: groupIndex "
              + groupIndex
              + " is out of bounds (available groups: "
              + tracks.getGroups().size()
              + ")");
    }

    Tracks.Group group = tracks.getGroups().get((int) groupIndex);

    // Verify it's an audio track
    if (group.getType() != C.TRACK_TYPE_AUDIO) {
      throw new IllegalArgumentException(
          "Cannot select audio track: group at index "
              + groupIndex
              + " is not an audio track (type: "
              + group.getType()
              + ")");
    }

    // Verify the track index is valid
    if (trackIndex < 0 || (int) trackIndex >= group.length) {
      throw new IllegalArgumentException(
          "Cannot select audio track: trackIndex "
              + trackIndex
              + " is out of bounds (available tracks in group: "
              + group.length
              + ")");
    }

    // Get the track group and create a selection override
    TrackGroup trackGroup = group.getMediaTrackGroup();
    TrackSelectionOverride override = new TrackSelectionOverride(trackGroup, (int) trackIndex);

    // Apply the track selection override
    trackSelector.setParameters(
        trackSelector.buildUponParameters().setOverrideForType(override).build());
  }

  /** Sets the delegate used to enter system PiP mode. Called by {@link VideoPlayerPlugin}. */
  public void setPipDelegate(@Nullable PipDelegate delegate) {
    this.pipDelegate = delegate;
  }

  public boolean isAutoStartPipEnabled() {
    return autoStartPipEnabled;
  }

  @Nullable
  public Rect getPipSourceRectHint() {
    return pipSourceRectHint;
  }

  @NonNull
  public Rational getVideoAspectRatio() {
    Format videoFormat = exoPlayer.getVideoFormat();
    if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
      int width = videoFormat.width;
      int height = videoFormat.height;
      int rotation = videoFormat.rotationDegrees;
      if (rotation == 90 || rotation == 270) {
        return new Rational(height, width);
      }
      return new Rational(width, height);
    }
    return new Rational(16, 9);
  }

  @Override
  public void startPictureInPicture() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || pipDelegate == null) {
      return;
    }
    pipDelegate.enterPictureInPicture(this, getVideoAspectRatio(), pipSourceRectHint);
  }

  @Override
  public void stopPictureInPicture() {
    // On Android the user dismisses PiP via system controls; this is intentionally a no-op.
  }

  @Override
  public void setAutomaticallyStartPictureInPicture(boolean enabled) {
    autoStartPipEnabled = enabled;
    if (pipDelegate != null) {
      pipDelegate.onAutoStartPipEnabledChanged(this, enabled);
    }
  }

  @Override
  public void setPictureInPictureSourceRectHint(
      double left, double top, double width, double height) {
    pipSourceRectHint =
        new Rect((int) left, (int) top, (int) (left + width), (int) (top + height));
  }

  /** Returns the callbacks instance so the plugin can fire PiP events. */
  @NonNull
  public VideoPlayerCallbacks getVideoPlayerCallbacks() {
    return videoPlayerEvents;
  }

  public void dispose() {
    pipDelegate = null;
    if (disposeHandler != null) {
      disposeHandler.onDispose();
    }
    exoPlayer.release();
  }
}
