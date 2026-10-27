# QRP-TRANSPORT-SEC-002 — Compatibility and closed-stage dependencies

Base: SEC-TRANSPORT-001 v1.2 / Core Ed25519DelegationTokens, 2026-10-27.

## TR-SEC-03 — JWS compatibility
Transport mirrors the Core issuer contract from
`levklg/qropass:src/main/java/com/qropass/platform/panel/security/Ed25519DelegationTokens.java`
and `panel_security.proto` at the inspected revision. Both use JWS Compact Serialization,
Ed25519 / EdDSA, exact header keys `alg,typ,kid`, issuer `qropass-core`,
audience `qropass-transport-catalog`, a fixed catalog-operation name,
nonnegative integer revision values, UUID list of up to 64, maximum TTL 60s,
and `cnf.x5t#S256` equal to SHA-256 of authenticated Gateway DER certificate.

Local tests produce Core-format signed claims and verify positive and negative
cryptographic vectors. The signing implementation in these tests is an
independent test implementation, **not an invocation of the actual Core issuer**.
A byte-identical Core-issuer test vector requires a separately coordinated,
fixed Ed25519 test key/kid, fixed Clock, deterministic input claims including
sid/jti, and expected Compact JWS output from Core. No production key is required
or permitted. Exact byte equality also requires Core's configured JSON
serialization order; cryptographic acceptance does not require matching
JSON field order.

## TR-SEC-04 — untrusted identity
`GatewayTlsPeer.Verified` has no public constructor. The only application
factory uses actual `ServerCall` SSL session peer attributes.
Mock TLS sessions are used solely in tests.
This does not replace production TLS trust-chain, SAN/service authorization,
certificate revocation or verified server listener wiring.

## TR-SEC-01/05/06 — still blocked
No production listener or real Transport-to-Core client is configured.
True mTLS Gateway->Transport and Transport->Core, public-key trust/rotation,
Core session revocation, certificate binding at two services, multi-user
concurrency and negative end-to-end tests require an approved isolated
interservice environment. The code intentionally fails closed without it.
