# QRP-TRANSPORT-HTA-202 — TEST ONLY Slice 01 evidence

Baseline: `c1736eca3e8c6765b8316451d1dec9a5f7ffcfe2`.
Branch: `feature/QRP-TRANSPORT-HTA-202-slice-01`.
Mode: PostgreSQL 15 Testcontainers / TEST ONLY / FAIL-CLOSED. Do not run SQL against production.
No production Java, Flyway, protobuf, payment, QR, ticket, validator, gateway, or Core changes.

## Trust boundary

SQL is loaded only in ephemeral PostgreSQL 15 after existing V1/V2 migrations.
`hta202.publication_evidence` is owned by NOLOGIN `hta202_owner`.
`hta202_writer` has EXECUTE only on `hta202.publish_test_only`.
`hta202_reader` has read-only SELECT, never execute publication or digest.
`hta202_runtime` has no schema permissions.
PUBLIC has no execution on SECURITY DEFINER functions. Explicit trusted-schema `search_path` is used.
The test DB bootstrap/migration superuser is deliberately outside application least privilege; it must never be used in production.

`publication_recorded_at` is PostgreSQL `clock_timestamp()` captured in the publishing transaction.
This is **not** a COMMIT timestamp. HTA202 never returns final VERIFIED.
`content_fingerprint` uses the unchanged public `transport_canonical_fingerprint(uuid)` function from V2.
`evidence_digest` is for the test evidence metadata, distinct from Transport Fingerprint v1.

Publication evidence is inserted in the same transaction as DRAFT → PUBLISHED; a PostgreSQL DEFERRABLE constraint trigger on the version status transition verifies matching evidence before COMMIT. A failed transition rolls back both. Existing V2 published immutability remains untouched.
A unique constraint prevents duplicate evidence; repeated publish returns the existing evidence id. A wrong fingerprint is rejected.

## Known limitations (fail-closed)

- Historical versions without evidence remain HISTORICAL_AUTHORITY_UNPROVEN; no backfill.
- PostgreSQL owners/superusers can change grants/triggers and must be governed externally.
- `publication_recorded_at` does not prove commit-time visibility, including concurrent commit delays.
- Existing production publication logic is not integrated. Test-only DDL adds a guard in the disposable DB only.
- Operator Assignment, historical external mappings, Waybill and D05 positive verification are not implemented.
- The negative historical method returns HISTORICAL_AUTHORITY_UNPROVEN even for a valid test evidence record.
- PostgreSQL superuser can TRUNCATE test fixtures to reset; runtime and reader cannot.

## Execution

Run on Java 21 with Docker:
`mvn -B -ntp -Dtest=Hta202PublicationPostgresTest -DfailIfNoTests=true clean verify`.

Workflow `.github/workflows/hta202-test-only.yml` checks baseline compare, changed-file allowlist, source SHA, Docker, PostgreSQL 15 tests, Surefire XML counts (no errors/failures/skipped), and final production JAR isolation.

Do not treat results as Security acceptance until independent QRP-SEC-001 review on exact SHA.

## FIX206: temporary schema security boundary (TEST ONLY)

- `hta202.publish_test_only`: `SET search_path = pg_catalog, public, pg_temp`. The unchanged V2 `public.transport_canonical_fingerprint` and its V2 header/child triggers have unqualified relation names and inherit the publication function's search path. The explicit `pg_temp` LAST prevents caller-owned TEMP objects from implicitly shadowing trusted `public` tables.
- `hta202.require_evidence_at_commit` and `hta202.reject_evidence_mutation`: `SET search_path = pg_catalog, hta202, pg_temp`. `hta202.digest_evidence`: `SET search_path = pg_catalog, pg_temp`.
- `REVOKE CREATE ON SCHEMA public FROM PUBLIC` is applied only to disposable Testcontainers PostgreSQL. The test role is unable to CREATE in public; roles do not have privileged DML on historical evidence. TEMP privilege is explicitly **not revoked** from the isolated writer so adversarial tests can create temporary shadow tables and prove the path is safe. This policy relies on the existing V2 function not redefining its own search path; future V2 changes require fresh independent security review.
- A dedicated restricted LOGIN member of `hta202_writer` must successfully create temporary shadow tables matching genuine V2 relations; positive publication must still use genuine canonical data. A forged fingerprint must fail with the expected conflict and leave no evidence; tests reject a setup failure as proof of protection.
- `hta202_writer` is a **trusted host-controlled isolated test principal only**. Do not grant this role to external services or reuse the function as production publication API. Function EXECUTE is not proof of per-entity authorization: `SEC-HTA204-F02` remains open for future integration.
- FIX206 t19 uses the correct canonical fingerprint of a separate, structurally valid new DRAFT version and asserts the explicit HTA deferred error during COMMIT. The older test's mismatched UUID/fingerprint is not security evidence.
- No positive D05, historical trusted backfill, production authority or core integration is enabled.
