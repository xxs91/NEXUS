# NEXUS Network Foundation Plan

## Baseline
- Android application ID: `com.nexus.app`
- minSdk: 24; targetSdk: 36; Java: 21
- Existing web UI and account/subscription flows must remain unchanged.
- Release signing must continue to use the existing configured keystore. Never generate or replace signing material.

## Scope and order

### Phase 1 — Native foundation (before VPS)
- Add a Capacitor bridge for read-only Android network-state diagnostics.
- Report only transport type, internet capability, validation status, and metered status.
- Keep diagnostics local; do not collect browsing history, payloads, credentials, private keys, or DNS query contents.
- Establish a typed connection/diagnostic state model before adding a tunnel engine.

### Phase 2 — VPN lifecycle
- Integrate a maintained Android-compatible tunnel engine only after confirming its supported artifact/version and license.
- Add Android VpnService permission flow, foreground lifecycle, explicit user consent, stop/cleanup, and revocation handling.
- Never create a dummy TUN or report "connected" until a real engine reports an established tunnel.

### Phase 3 — Security and recovery
- Validate server identity/certificates where the selected protocol supports it.
- Keep secrets out of logs and ordinary preferences; use Android Keystore-backed storage where applicable.
- Handle network changes, bounded retries/backoff, cancellation, and cleanup.
- Fail closed for invalid configuration, but restore ordinary device networking when the VPN is stopped or fails.

### Phase 4 — DNS and routing groundwork
- Define separate interfaces for DNS policy, route policy, and app selection.
- Do not activate routing rules until the tunnel engine is integrated and tested.
- Do not promise leak prevention until verified on-device.

### Phase 5 — Diagnostics and tests
- Distinguish network unavailable, server unreachable, handshake failure, tunnel setup failure, DNS failure, and data-plane failure.
- Use synthetic/local tests before VPS integration; real handshake and route tests require a real server.
- Test Wi-Fi, cellular, Wi-Fi-to-cellular handoff, airplane mode, process death, VPN revocation, and Android 14–16 foreground-service behavior.

## Explicit non-goals
- No claim of invisibility or guaranteed resistance to network-side blocking/device restrictions.
- No traffic camouflage designed to evade operator or regulatory detection.
- No weakening of TLS/certificate validation or acceptance of untrusted server identities.
- No changes to the existing splash animation, account system, subscription system, or signing identity.

## Acceptance criteria for Phase 1
- Existing app behavior is unchanged.
- Native diagnostics report the active network state accurately and handle no-active-network cases.
- Diagnostic output contains no sensitive user traffic data.
- CI builds the debug/release variants; release signature fingerprint remains the existing configured identity.
