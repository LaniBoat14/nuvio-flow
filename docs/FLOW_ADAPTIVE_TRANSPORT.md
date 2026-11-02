# Adaptive playback transport

The native adapter in `app/src/main/java/androidx/media3/datasource/FlowHttpEngineDataSource.java`
is derived from AndroidX Media3 tag `1.8.0`, library `datasource`, under Apache-2.0.
Its original copyright and license header are retained. Flow adds a public accessor
for the negotiated protocol returned by the native response callback. This avoids
private-field reflection and hidden Android APIs.

The public native response API does not expose the connected peer IP. The overlay
must report that absence. Independent TCP recovery requests report the actual
socket peer and protocol from OkHttp's connection callback; DNS results are never
presented as the connected peer.

Adaptive mode starts from a valid host profile or 3 x 16 MB, subject to current
heap/system memory. Slow-range recovery uses a separate fresh TCP connection.
Repeated recovery wins can select pooled independent HTTP/1.1 requests for that host.
Normal requests reuse their transport resources. The global range budget is six,
including capability probes, retries and hedges. Duplicate bytes do not inflate
controller throughput.

Mode comparison reuses real movie downloads and considers active-transfer medians.
It waits for the mode cooldown and at least 24 seconds of buffered media before
trying another mode. Comparisons are deferred when memory or playback headroom is
insufficient. This is deliberately not a promise to benchmark all five modes in
three seconds. Saved profiles expire after 24 hours.

Default adaptive load control requests 60-120 seconds ahead and a 12-second back
buffer, bounded by a device-aware byte budget capped at 300 MiB. Range reservations
reduce the player's budget. Time targets are not guarantees: 80 Mbps requires about
600 MB for 60 seconds of compressed media. Explicit custom buffer choices retain
priority, and DV7 low-memory safeguards remain active.

Software checks do not establish sustained CDN throughput or two-hour playback
stability on a physical TV. Compare Single, fixed 3 x 16 MB, and Automatic on the
same media before claiming a performance improvement.
