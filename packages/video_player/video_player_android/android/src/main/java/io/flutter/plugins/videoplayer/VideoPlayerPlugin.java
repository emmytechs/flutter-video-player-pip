// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.LongSparseArray;
import android.util.Rational;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.List;
import androidx.media3.common.util.UnstableApi;
import io.flutter.FlutterInjector;
import io.flutter.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugins.videoplayer.platformview.PlatformVideoViewFactory;
import io.flutter.plugins.videoplayer.platformview.PlatformViewVideoPlayer;
import io.flutter.plugins.videoplayer.texture.TextureVideoPlayer;
import io.flutter.view.TextureRegistry;

/** Android platform implementation of the VideoPlayerPlugin. */
public class VideoPlayerPlugin
    implements FlutterPlugin, AndroidVideoPlayerApi, ActivityAware, VideoPlayer.PipDelegate {
  private static final String TAG = "VideoPlayerPlugin";

  // PiP action broadcast constants
  private static final String ACTION_PIP_PLAY_PAUSE =
      "io.flutter.plugins.videoplayer.PIP_PLAY_PAUSE";
  private static final String EXTRA_PIP_ACTION = "pip_action";
  private static final int PIP_ACTION_PAUSE = 1;
  private static final int PIP_ACTION_PLAY = 2;
  private static final int PIP_REQUEST_CODE_PAUSE = 101;
  private static final int PIP_REQUEST_CODE_PLAY = 102;

  private final LongSparseArray<VideoPlayer> videoPlayers = new LongSparseArray<>();
  private FlutterState flutterState;
  private final VideoPlayerOptions sharedOptions = new VideoPlayerOptions();
  private long nextPlayerIdentifier = 1;

  @Nullable private Activity activity;
  @Nullable private ActivityPluginBinding activityPluginBinding;
  @Nullable private VideoPlayer activePipPlayer;
  @Nullable private BroadcastReceiver pipActionReceiver;

  /** Register this with the v2 embedding for the plugin to respond to lifecycle callbacks. */
  public VideoPlayerPlugin() {}

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    final FlutterInjector injector = FlutterInjector.instance();
    this.flutterState =
        new FlutterState(
            binding.getApplicationContext(),
            binding.getBinaryMessenger(),
            injector.flutterLoader()::getLookupKeyForAsset,
            injector.flutterLoader()::getLookupKeyForAsset,
            binding.getTextureRegistry());
    flutterState.startListening(this, binding.getBinaryMessenger());

    binding
        .getPlatformViewRegistry()
        .registerViewFactory(
            "plugins.flutter.dev/video_player_android",
            new PlatformVideoViewFactory(videoPlayers::get));
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    if (flutterState == null) {
      Log.wtf(TAG, "Detached from the engine before registering to it.");
    }
    flutterState.stopListening(binding.getBinaryMessenger());
    flutterState = null;
    onDestroy();
  }

  private void disposeAllPlayers() {
    for (int i = 0; i < videoPlayers.size(); i++) {
      videoPlayers.valueAt(i).dispose();
    }
    videoPlayers.clear();
  }

  public void onDestroy() {
    // The whole FlutterView is being destroyed. Here we release resources acquired for all
    // instances
    // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
    // be replaced with just asserting that videoPlayers.isEmpty().
    // https://github.com/flutter/flutter/issues/20989 tracks this.
    disposeAllPlayers();
  }

  @Override
  public void initialize() {
    disposeAllPlayers();
  }

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public long createForPlatformView(@NonNull CreationOptions options) {
    final VideoAsset videoAsset = videoAssetWithOptions(options);

    long id = nextPlayerIdentifier++;
    final String streamInstance = Long.toString(id);
    VideoPlayer videoPlayer =
        PlatformViewVideoPlayer.create(
            flutterState.applicationContext,
            VideoPlayerEventCallbacks.bindTo(flutterState.binaryMessenger, streamInstance),
            videoAsset,
            sharedOptions);

    registerPlayerInstance(videoPlayer, id);
    return id;
  }

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public @NonNull TexturePlayerIds createForTextureView(@NonNull CreationOptions options) {
    final VideoAsset videoAsset = videoAssetWithOptions(options);

    long id = nextPlayerIdentifier++;
    final String streamInstance = Long.toString(id);
    TextureRegistry.SurfaceProducer handle = flutterState.textureRegistry.createSurfaceProducer();
    VideoPlayer videoPlayer =
        TextureVideoPlayer.create(
            flutterState.applicationContext,
            VideoPlayerEventCallbacks.bindTo(flutterState.binaryMessenger, streamInstance),
            handle,
            videoAsset,
            sharedOptions);

    registerPlayerInstance(videoPlayer, id);
    return new TexturePlayerIds(id, handle.id());
  }

  private @NonNull VideoAsset videoAssetWithOptions(@NonNull CreationOptions options) {
    final @NonNull String uri = options.getUri();
    if (uri.startsWith("asset:")) {
      return VideoAsset.fromAssetUrl(uri);
    } else if (uri.startsWith("rtsp:")) {
      return VideoAsset.fromRtspUrl(uri);
    } else {
      VideoAsset.StreamingFormat streamingFormat = VideoAsset.StreamingFormat.UNKNOWN;
      PlatformVideoFormat formatHint = options.getFormatHint();
      if (formatHint != null) {
        switch (formatHint) {
          case SS:
            streamingFormat = VideoAsset.StreamingFormat.SMOOTH;
            break;
          case DASH:
            streamingFormat = VideoAsset.StreamingFormat.DYNAMIC_ADAPTIVE;
            break;
          case HLS:
            streamingFormat = VideoAsset.StreamingFormat.HTTP_LIVE;
            break;
        }
      }
      return VideoAsset.fromRemoteUrl(
          uri, streamingFormat, options.getHttpHeaders(), options.getUserAgent());
    }
  }

  private void registerPlayerInstance(VideoPlayer player, long id) {
    BinaryMessenger messenger = flutterState.binaryMessenger;
    final String channelSuffix = Long.toString(id);
    VideoPlayerInstanceApi.Companion.setUp(messenger, player, channelSuffix);
    player.setDisposeHandler(
        () -> VideoPlayerInstanceApi.Companion.setUp(messenger, null, channelSuffix));

    // Provide the Activity-backed PiP delegate if we are already attached.
    if (activity != null) {
      player.setPipDelegate(this);
    }

    videoPlayers.put(id, player);
  }

  @NonNull
  private VideoPlayer getPlayer(long playerId) {
    VideoPlayer player = videoPlayers.get(playerId);

    // Avoid a very ugly un-debuggable NPE that results in returning a null player.
    if (player == null) {
      String message = "No player found with playerId <" + playerId + ">";
      if (videoPlayers.size() == 0) {
        message += " and no active players created by the plugin.";
      }
      throw new IllegalStateException(message);
    }

    return player;
  }

  @Override
  public void dispose(long playerId) {
    VideoPlayer player = getPlayer(playerId);
    player.dispose();
    videoPlayers.remove(playerId);
  }

  @Override
  public void setMixWithOthers(boolean mixWithOthers) {
    sharedOptions.mixWithOthers = mixWithOthers;
  }

  @Override
  public @NonNull String getLookupKeyForAsset(@NonNull String asset, @Nullable String packageName) {
    return packageName == null
        ? flutterState.keyForAsset.get(asset)
        : flutterState.keyForAssetAndPackageName.get(asset, packageName);
  }

  @Override
  public boolean isPictureInPictureSupported() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
  }

  // ── ActivityAware ──────────────────────────────────────────────────────────

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activity = binding.getActivity();
    activityPluginBinding = binding;
    binding.addOnUserLeaveHintListener(this::onUserLeaveHint);
    updatePipDelegateForAllPlayers(this);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    detachActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    detachActivity();
  }

  private void detachActivity() {
    if (activityPluginBinding != null) {
      activityPluginBinding.removeOnUserLeaveHintListener(this::onUserLeaveHint);
      activityPluginBinding = null;
    }
    updatePipDelegateForAllPlayers(null);
    activity = null;
  }

  private void updatePipDelegateForAllPlayers(@Nullable VideoPlayer.PipDelegate delegate) {
    for (int i = 0; i < videoPlayers.size(); i++) {
      videoPlayers.valueAt(i).setPipDelegate(delegate);
    }
  }

  // ── VideoPlayer.PipDelegate ────────────────────────────────────────────────

  @Override
  @RequiresApi(api = Build.VERSION_CODES.O)
  public void enterPictureInPicture(
      @NonNull VideoPlayer player,
      @NonNull Rational aspectRatio,
      @Nullable Rect sourceRectHint) {
    if (activity == null) return;

    activePipPlayer = player;
    registerPipActionReceiver();

    PictureInPictureParams.Builder builder =
        new PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(buildPipActions(player.isPlaying()));

    if (sourceRectHint != null) {
      builder.setSourceRectHint(sourceRectHint);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setSeamlessResizeEnabled(true);
    }

    activity.enterPictureInPictureMode(builder.build());
  }

  /**
   * Called synchronously whenever Dart calls {@code setAutomaticallyStartPictureInPicture}.
   *
   * <p>On Android 12+ (API 31+): sets {@code autoEnterEnabled} on the Activity's PiP params so
   * the system enters PiP automatically when the user presses home — no {@code onUserLeaveHint}
   * timing dependency required.
   *
   * <p>On Android 8–11 (API 26–30): pre-registers the active player and broadcast receiver so
   * they are ready before {@code onUserLeaveHint} fires, eliminating the async race.
   */
  @Override
  public void onAutoStartPipEnabledChanged(@NonNull VideoPlayer player, boolean enabled) {
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

    if (enabled) {
      activePipPlayer = player;
      registerPipActionReceiver();

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
          activity.setPictureInPictureParams(
              new PictureInPictureParams.Builder()
                  .setAutoEnterEnabled(true)
                  .setAspectRatio(player.getVideoAspectRatio())
                  .setActions(buildPipActions(player.isPlaying()))
                  .setSeamlessResizeEnabled(true)
                  .build());
        } catch (Exception ignored) {
          // Defensive: some OEM ROMs throw on setPictureInPictureParams.
        }
      }
    } else {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
          activity.setPictureInPictureParams(
              new PictureInPictureParams.Builder()
                  .setAutoEnterEnabled(false)
                  .build());
        } catch (Exception ignored) {}
      }
      // Clean up only when we are NOT already inside a native PiP session.
      // If we are, onPictureInPictureModeChanged(false) will clean up instead.
      if (!activity.isInPictureInPictureMode()) {
        activePipPlayer = null;
        unregisterPipActionReceiver();
      }
    }
  }

  /**
   * Refreshes the PiP play/pause action button whenever the player's actual
   * playback state changes. Only acts while this is the active PiP player and
   * the Activity is inside the PiP window, to avoid redundant param churn.
   */
  @Override
  public void onPlayingStateChanged(@NonNull VideoPlayer player, boolean isPlaying) {
    if (activity == null
        || player != activePipPlayer
        || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        || !activity.isInPictureInPictureMode()) {
      return;
    }
    try {
      activity.setPictureInPictureParams(
          new PictureInPictureParams.Builder()
              .setActions(buildPipActions(isPlaying))
              .build());
    } catch (Exception ignored) {
      // Defensive: some OEM ROMs throw on setPictureInPictureParams.
    }
  }

  /**
   * Called by {@code MainActivity.onPictureInPictureModeChanged}.
   *
   * <p>Routes the system callback to the currently active PiP player so Flutter
   * receives the corresponding {@code startingPictureInPicture} / {@code stoppedPictureInPicture}
   * events.
   */
  public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
    if (activePipPlayer == null) return;
    if (isInPictureInPictureMode) {
      activePipPlayer.getVideoPlayerCallbacks().onPictureInPictureStarted();
    } else {
      activePipPlayer.getVideoPlayerCallbacks().onPictureInPictureStopped();
      activePipPlayer = null;
      unregisterPipActionReceiver();
    }
  }

  // ── PiP play/pause action helpers ─────────────────────────────────────────

  @RequiresApi(api = Build.VERSION_CODES.O)
  private void registerPipActionReceiver() {
    if (pipActionReceiver != null || activity == null) return;

    pipActionReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            if (!ACTION_PIP_PLAY_PAUSE.equals(intent.getAction())) return;
            // Only guard on the player — activity can be temporarily null
            // during the brief detach/reattach cycle on configuration change
            // (e.g. PiP window resize). Play/pause must still be delivered.
            if (activePipPlayer == null) return;

            int action = intent.getIntExtra(EXTRA_PIP_ACTION, 0);
            boolean nowPlaying;
            if (action == PIP_ACTION_PAUSE) {
              activePipPlayer.pause();
              nowPlaying = false;
            } else if (action == PIP_ACTION_PLAY) {
              activePipPlayer.play();
              nowPlaying = true;
            } else {
              return;
            }

            // Update the PiP window action button icon to reflect the new state.
            // Skip the param update if the activity reference was temporarily
            // cleared; the button will sync on the next param rebuild.
            Activity currentActivity = activity;
            if (currentActivity != null) {
              currentActivity.setPictureInPictureParams(
                  new PictureInPictureParams.Builder()
                      .setActions(buildPipActions(nowPlaying))
                      .build());
            }
          }
        };

    IntentFilter filter = new IntentFilter(ACTION_PIP_PLAY_PAUSE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      activity.registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      activity.registerReceiver(pipActionReceiver, filter);
    }
  }

  private void unregisterPipActionReceiver() {
    if (pipActionReceiver == null || activity == null) return;
    try {
      activity.unregisterReceiver(pipActionReceiver);
    } catch (Exception ignored) {
      // Receiver may have already been unregistered.
    }
    pipActionReceiver = null;
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  @NonNull
  private List<RemoteAction> buildPipActions(boolean isPlaying) {
    List<RemoteAction> actions = new ArrayList<>();
    if (activity == null) return actions;

    if (isPlaying) {
      Intent intent =
          new Intent(ACTION_PIP_PLAY_PAUSE).putExtra(EXTRA_PIP_ACTION, PIP_ACTION_PAUSE);
      // Make the broadcast explicit. From Android 14 (API 34) implicit intents
      // are not delivered to runtime-registered RECEIVER_NOT_EXPORTED receivers,
      // so without setPackage the PiP play/pause button silently does nothing.
      intent.setPackage(activity.getPackageName());
      PendingIntent pending =
          PendingIntent.getBroadcast(
              activity,
              PIP_REQUEST_CODE_PAUSE,
              intent,
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      actions.add(
          new RemoteAction(
              Icon.createWithResource(activity, android.R.drawable.ic_media_pause),
              "Pause",
              "Pause video",
              pending));
    } else {
      Intent intent =
          new Intent(ACTION_PIP_PLAY_PAUSE).putExtra(EXTRA_PIP_ACTION, PIP_ACTION_PLAY);
      // See note above: explicit intent required for delivery on Android 14+.
      intent.setPackage(activity.getPackageName());
      PendingIntent pending =
          PendingIntent.getBroadcast(
              activity,
              PIP_REQUEST_CODE_PLAY,
              intent,
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      actions.add(
          new RemoteAction(
              Icon.createWithResource(activity, android.R.drawable.ic_media_play),
              "Play",
              "Play video",
              pending));
    }

    return actions;
  }

  private void onUserLeaveHint() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null) return;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Android 12+: autoEnterEnabled is set in onAutoStartPipEnabledChanged.
      // The system enters PiP automatically — no manual call needed here.
      // However, we still update the PiP params with the current play state so
      // the correct play/pause button is shown in the PiP window.
      if (activePipPlayer != null) {
        try {
          activity.setPictureInPictureParams(
              new PictureInPictureParams.Builder()
                  .setAutoEnterEnabled(true)
                  .setAspectRatio(activePipPlayer.getVideoAspectRatio())
                  .setActions(buildPipActions(activePipPlayer.isPlaying()))
                  .setSeamlessResizeEnabled(true)
                  .build());
        } catch (Exception ignored) {}
      }
      return;
    }

    // Android 8–11: activePipPlayer is pre-registered in onAutoStartPipEnabledChanged,
    // so check it directly without an async flag race.
    if (activePipPlayer != null && activePipPlayer.isPlaying()) {
      enterPictureInPicture(
          activePipPlayer,
          activePipPlayer.getVideoAspectRatio(),
          activePipPlayer.getPipSourceRectHint());
    }
  }

  private interface KeyForAssetFn {
    String get(String asset);
  }

  private interface KeyForAssetAndPackageName {
    String get(String asset, String packageName);
  }

  private static final class FlutterState {
    final Context applicationContext;
    final BinaryMessenger binaryMessenger;
    final KeyForAssetFn keyForAsset;
    final KeyForAssetAndPackageName keyForAssetAndPackageName;
    final TextureRegistry textureRegistry;

    FlutterState(
        Context applicationContext,
        BinaryMessenger messenger,
        KeyForAssetFn keyForAsset,
        KeyForAssetAndPackageName keyForAssetAndPackageName,
        TextureRegistry textureRegistry) {
      this.applicationContext = applicationContext;
      this.binaryMessenger = messenger;
      this.keyForAsset = keyForAsset;
      this.keyForAssetAndPackageName = keyForAssetAndPackageName;
      this.textureRegistry = textureRegistry;
    }

    void startListening(VideoPlayerPlugin methodCallHandler, BinaryMessenger messenger) {
      AndroidVideoPlayerApi.Companion.setUp(messenger, methodCallHandler);
    }

    void stopListening(BinaryMessenger messenger) {
      AndroidVideoPlayerApi.Companion.setUp(messenger, null);
    }
  }
}
