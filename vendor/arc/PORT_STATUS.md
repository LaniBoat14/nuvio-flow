# Exact Arc runtime integration

Reference: supplied NuvioTV-Fork-main(1).zip, upstream commit ccfd1d7b5ad681bde4615e5bae82a4e7d710a34d.

The complete matching Arc source replaces the earlier partial adapters. This includes its player screens, classic/V2 HUD, startup/prewarm hooks, networking, buffer/memory governance, matching player AARs, Dolby Vision/FEL native components, IEC audio and thumbnail subsystem.

Flow retains its application ID com.jeffrey.nuvioflow.debug, existing release signing credentials, launcher icons, name and GitHub release feed. The runtime manifest records 638 exact reference hashes and CI rejects mismatches.

Release publication requires original Arc transport/player/memory/audio and updater tests, optimized APK assembly, native architecture/linkage validation, signature verification and package identity verification. TV playback validation remains user-run.

The supplied Arc revision is pinned. Previous automated official-Nuvio merges are paused because they would invalidate the requested exact Arc implementation. A later source update must revalidate the manifest and matching dependencies together.
