# Paired Newton collision captures

## Pause/Resume interaction

The screen retains the original interaction: live streaming, Pause/Resume, Clear,
Zero both sensors, and the existing auto-trigger controls. There is no New run or
Ready workflow. Both devices must provide v2 High-G samples for paired snapshots.

Automatic pause follows the earlier screen's path:

1. Each BleViewModel checks its configured threshold on incoming High-G samples.
2. The shared coordinator records those callbacks. Once both crossings occur
   within three seconds, it schedules the configured delayed pause on both devices.
   There is no stationary-baseline or minimum-prehistory gate before thresholding.
3. Once both devices report paused, the coordinator freezes the current continuous
   windows, finds each displayed signal's largest absolute peak in recent history,
   aligns those peaks independently at zero, and zooms the plot to 10 ms.

The pause delay uses the phone's timer, as before; v2 sample timestamps determine
plot coordinates. This does not claim sample-exact capture endpoints, synchronized
clocks, simultaneous peaks, or experimental verification of equal/opposite forces.
The optional sample-time controller path tested by the pure fixtures is not the
Newton screen's user-selected pause workflow.

Manual Pause freezes the available continuous data immediately, even before the
optional controller's baseline/prehistory readiness conditions. Without an explicit
zero, the waveform uses zero offsets, as the earlier screen did. Zero both sensors
uses the latest 100 points per device while the strikers are still; those offsets
are retained on Resume and cannot be relearned from the impact itself.

Peak selection and plotting use the same signed planar magnitude and selected
collision-axis sign, including suppression of nearly perpendicular motion. The
search includes up to ten seconds of retained history, so the first device's
impact is not discarded solely because the second threshold was delivered later.
The 50,000-point live storage cap still limits history at high sample rates.

Resume resets old crossing selections and establishes fresh stream segments while
keeping v2 timing. Clear removes live history and continues if already streaming.
After zooming below one second, Resume restores the three-second live window.

## Interruption protection

Inconsistent timing frames (duplicate, backwards, or reordered samples), invalid frames,
or actual Bluetooth disconnects interrupt an unfinished pair and cancel delayed callbacks.
Following user testing at 7,680 Hz (commit `e5858c3`), in-segment missing samples/gaps do
not stop the live plot or interrupt the paired run; gap metrics are recorded in diagnostics,
while the plot stays live and armed. Segment restarts (e.g. from configuration changes or
FIFO flushes) and brief delivery stalls re-arm baseline/armed capture state without
disconnecting. Pausing after an interruption cannot turn it into a valid paired snapshot.
Known old queued segments cannot populate a resumed interval. Expired threshold crossings
are forgotten instead of matching a later collision.

An interruption shows a brief reason with Resume as the recovery action. No raw
protocol/timing debug footer is shown. The latest frozen pair remains in memory;
previous-capture recall is inside expanded Instructions & Setup. It retains points,
baselines, device/segment identities, axis, and the available paused interval.
A later gap, Clear, or buffer eviction cannot alter that saved pair. This is not
persistent recording or a multi-run browser; another completed pause replaces it.

## Verification

The controller regression suite covers early threshold callbacks without readiness
or prehistory, the three-second coincidence window, interruption/restart cancellation,
manual freezing, displayed-signal peak selection, full sample-time intervals for
its optional algorithm, stale streams, old queued segments, bounded history, and
saved-snapshot preservation.

Device/UI checks to repeat:

- Enter Newton mode with both devices connected, zero while still, then cross both
  configured thresholds. Confirm paired pause, peaks at zero, and 10 ms zoom.
- Resume, then Pause manually soon after a collision; alignment and zoom should also
  occur without a Ready step.
- Disconnect or interrupt one stream during a pending auto-pause. Confirm the pair
  is invalidated and an old callback cannot pause a resumed interval.

No firmware or packet-format change is needed.

## Bluetooth write failure handling

CCCD subscription and command writes share one callback-completed gate per device,
and configuration writes between the two devices are serialized. The app reports
Connected only after subscription succeeds. It never advances the command queue
just because 250 ms elapsed. When Android synchronously refuses a command write
start (returning false before accepting the write), `GattWriteGate` permits up to
three controlled retries before failing the session. Once accepted by the stack, a write
is never retried without completion, and gets up to ten seconds for its callback;
a failed or missing completion stops that connection once and displays a manual
reconnect message. A reconnect establishes a new callback/queue owner. This prevents
issuing overlapping writes or bombarding an ATT client while Android is busy.

Entering Newton mode no longer adds an extra automatic pause/resume cycle to the
preset configuration. Explicit Pause/Resume remains unchanged. Logcat's
ElectrophyBLE messages include the device and numeric Android write-start result,
so commands to the two devices can be distinguished during diagnosis.
