# Flow component-port review

**Status: local candidate. Not compiled, installed or published.**

Base: restored Flow 11 application (rollback commit 4fc2f5a).

## Preservation checks

- 1,521 application files match Flow 11 hashes.
- Player-screen content outside the analytics panel matches Flow 11.
- Stream-screen content outside the network prewarm call matches Flow 11.
- Settings content outside Network matches Flow 11.
- Icons, branding, navigation, manifests, updater, player libraries and other screens are unchanged.
- Flow disk cache keeps its independent setting.
- Candidate validation workflow tests and builds; it cannot publish.

## Validation remaining

Android compilation, unit suite, signed APK checks, and runtime UI inspection remain. This machine has no Android SDK or Gradle cache. The initial push was blocked by automatic approval review. A generated temporary profile file was removed from the local candidate.

## Compatibility boundaries

Arc classic overlay rendering and metric helpers are ported into Flow. Flow has no Arc FEL renderer, IEC owner or thumbnail allocator; adapters do not fabricate measurements for absent subsystems. Arc V2 screens and theme are excluded. Actual playback parity is unverified.

## Direct Arc source matches

- `app/src/main/java/com/nuvio/tv/core/logging/UrlRedaction.kt`
- `app/src/main/java/com/nuvio/tv/core/network/PlaybackSocketFactory.kt`
- `app/src/main/java/com/nuvio/tv/core/network/ServerTrust.kt`
- `app/src/main/java/com/nuvio/tv/core/performance/ThermalHeadroom.kt`
- `app/src/main/java/com/nuvio/tv/core/player/AllocatorStopTrim.kt`
- `app/src/main/java/com/nuvio/tv/core/player/BufferBudgetControl.kt`
- `app/src/main/java/com/nuvio/tv/core/player/BufferMemoryKeeper.kt`
- `app/src/main/java/com/nuvio/tv/core/player/BufferSizing.kt`
- `app/src/main/java/com/nuvio/tv/core/player/BufferTopUp.kt`
- `app/src/main/java/com/nuvio/tv/core/player/MemoryPressure.kt`
- `app/src/main/java/com/nuvio/tv/core/player/ReadAheadReserve.kt`
- `app/src/main/java/com/nuvio/tv/core/player/StartCushion.kt`
- `app/src/main/java/com/nuvio/tv/core/player/VodCacheSizing.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/PlaybackBufferReserve.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/PlaybackLine.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/PlaybackYield.kt`
- `app/src/main/java/com/nuvio/tv/core/util/TtffTrace.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/AutoSpeedupDataSource.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/AutoSpeedupPolicy.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/ExtraDownloadRefusal.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/FilmRate.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/HostSpeeds.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/LedgerDataSource.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/ParallelRangeDataSource.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/ParallelRangeProbeResponse.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/ParallelRangeRateGate.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PassthroughWaterLevelPacer.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackByteCounter.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackConnectProbe.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackConnectionEvents.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackCpuClockSampler.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackDroppedFrameSampler.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackDroppedFramesRow.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackLanes.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackNetworkFailure.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackPositionRow.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackPrewarmCallFactory.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackPrewarmCoordinator.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackPrewarmIdentity.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackPrewarmReader.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackSkippedFrameTally.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackStatsPolling.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackThermalSampler.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackTransferRateSampler.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackTransferSession.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackVideoPresentation.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerAudioBitrateMeter.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerAudioUnderrunCounter.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMemoryReporter.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPlaybackAnalyticsDiagnostics.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerBufferMemory.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/RequestMeter.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/SlowStartServer.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/StreamProviderLabel.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/BufferSettingsNumbers.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/ConnectionsChoice.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/MemoryBudget.kt`

## Integration and compatibility files

- `app/src/main/java/com/nuvio/tv/core/network/StreamSpeedTester.kt`
- `app/src/main/java/com/nuvio/tv/core/player/BitrateAwareLoadControl.kt`
- `app/src/main/java/com/nuvio/tv/core/player/DoviBridge.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/ConnectionLedger.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/MemoryGovernor.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/RateLimitedException.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/ThumbRateLimit.kt`
- `app/src/main/java/com/nuvio/tv/core/player/thumbnail/VideoPoolReader.kt`
- `app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/AudioRoutingSnapshot.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/AutoSpeedup.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/NuvioExoPlayerPerformanceHelper.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackAudioSinkMonitor.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackSpeedAwareAudioSink.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackStatsDv.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlaybackStatsOverlay.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaInfo.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerViewModel.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/iec/HbrPayload.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackBufferNetworkSettings.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreen.kt`

## Replaced Flow engine and telemetry files

- `app/src/main/java/androidx/media3/datasource/FlowHttpEngineDataSource.java`
- `app/src/main/java/com/nuvio/tv/core/network/adaptive/AdaptivePolicy.kt`
- `app/src/main/java/com/nuvio/tv/core/network/adaptive/HedgeBudget.kt`
- `app/src/main/java/com/nuvio/tv/core/network/adaptive/ModeComparison.kt`
- `app/src/main/java/com/nuvio/tv/core/network/adaptive/RangeWindow.kt`
- `app/src/main/java/com/nuvio/tv/core/network/adaptive/SessionRangeBudget.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/FlowAdaptiveSource.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/FlowAnalyticsOverlay.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/FlowMediaBitrate.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/FlowMediaReadDataSource.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/FlowMuxBitrate.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/FlowNativeTransport.kt`

## Follow-up source audit

Found and corrected a missing teardown integration: reset Arc byte/lane telemetry and detach its buffer-memory keeper on player release, using Arc's corresponding lifecycle calls. This is an additional backend-only integration file. Network UI rows persist into the settings consumed by PlayerMediaSourceFactory; the factory and range-download implementation match the Arc reference. Automatic speed-up is sampled from the progress loop. Analytics visibility is still controlled by Flow's existing icon.

Build validation remains blocked by the rejected public branch push. This audit is source-level; it does not establish successful Android compilation or runtime playback.
