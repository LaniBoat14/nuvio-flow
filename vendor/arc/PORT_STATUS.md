# Arc source import — integration in progress

Reference: supplied archive commit ccfd1d7b5ad681bde4615e5bae82a4e7d710a34d.
SOURCE.json hashes the unchanged imported files. GPL-3.0 license retained.

This branch is a preparation checkpoint, not a playable or published replacement.
The isolated policy check runs the original Arc policy and ConnectionsChoice tests.
It does not establish that the application port compiles or has passed playback tests.

Remaining integration: replace Flow data-source wiring and its adaptive engine;
port Arc settings persistence and memory/request governance; match ExoPlayer native
interfaces; connect Arc measurement providers and exact HUD renderer to Flow's
existing analytics button; verify player UI and update identity stay intact; run
application tests and signed APK checks before publication.

Runtime integration candidate (not yet validated or published):
- Arc media-source factory, socket/HTTP clients, range engine, automatic speed-up, host history and request ledger are now in app sources.
- Persisted Automatic speed-up and original buffer/network settings replace Flow network controls.
- Arc sampler, polling, sections/rows and renderer feed the existing analytics button.
- Flow's classic theme is selected. Missing thumbnail owners charge zero memory; their optional interfaces retain Arc semantics.
- Flow does not ship Arc's native FEL renderer: its native-FEL-only RPU detail rows are not queried. Platform audio monitoring uses the original Arc platform branch, without an IEC owner.
- Existing Flow low-RAM DV7 back-buffer adjustment has a compatibility method on Arc's load control.
- Original Arc tests are imported into the application test suite; signed CI must pass before release.
