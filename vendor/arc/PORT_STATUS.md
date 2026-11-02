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
