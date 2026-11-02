# Arc runtime port — release candidate awaiting build validation

Reference archive commit: ccfd1d7b5ad681bde4615e5bae82a4e7d710a34d.
SOURCE.json hashes the preserved original sources and tests. GPL-3.0 license retained.

The app now uses Arc's media-source factory, HTTP/socket clients, range engine,
automatic speed-up policy, host history, request ledger and memory governance.
The existing analytics button opens Arc's sampled, sectioned live HUD. Arc's
buffer/network settings and Automatic speed-up preference are persisted.
The former Flow transport and custom HUD have been removed from production.

Integration boundaries:
- Flow uses the classic Nuvio HUD surface from Arc, without Arc's separate V2 UI.
- Flow has no thumbnail allocator; that absent owner contributes zero memory.
- Native-FEL-only RPU detail rows are not queried because Flow has no native FEL renderer.
- Audio diagnostics use Arc's platform monitor; Flow has no separate IEC audio owner.
- Flow's existing low-RAM DV7 back-buffer adjustment retains a compatibility method.

Original Arc contract tests are included in application CI. Policy-only validation
is insufficient: signed application compilation, selected runtime/network tests,
and APK identity/signature checks must pass before publishing an update.
