# Changelog

## 1.0.0

- Rebuilt TCP instrument client around explicit layers.
- Removed `readPermit`-based receiver gating.
- Removed `clearBuffers()` request association mechanism.
- Replaced `Condition` response waiting with `CompletableFuture`.
- Added explicit connection states.
- Added reconnect backoff with jitter.
- Added command idempotency classification.
- Added stateful line and length-field decoders.
- Added async response queue overflow policies.
- Added metrics snapshot.
- Added unit tests for fragmentation, sticky packets, checksum resync and request matching.
