-- HTA202: disposable PostgreSQL 15 ONLY. Never apply to production.
-- The pre-existing V1/V2 structure exists exclusively in an ephemeral Testcontainers DB.
CREATE SCHEMA hta202;
CREATE ROLE hta202_owner NOLOGIN NOINHERIT;
CREATE ROLE hta202_writer NOLOGIN NOINHERIT;
CREATE ROLE hta202_reader NOLOGIN NOINHERIT;
CREATE ROLE hta202_runtime NOLOGIN NOINHERIT;
ALTER SCHEMA hta202 OWNER TO hta202_owner;
REVOKE ALL ON SCHEMA hta202 FROM PUBLIC;
GRANT USAGE ON SCHEMA hta202 TO hta202_writer, hta202_reader;
CREATE TABLE hta202.publication_evidence (
 evidence_id uuid PRIMARY KEY,
 route_pattern_version_id uuid NOT NULL UNIQUE REFERENCES public.route_pattern_version(id) ON DELETE RESTRICT,
 content_fingerprint varchar(71) NOT NULL CHECK (content_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
 publication_event_id uuid NOT NULL UNIQUE,
 publication_recorded_at timestamptz NOT NULL,
 publication_policy_version varchar(80) NOT NULL CHECK (length(btrim(publication_policy_version)) > 0),
 evidence_schema_version integer NOT NULL CHECK (evidence_schema_version = 1),
 evidence_digest varchar(71) NOT NULL CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$')
);
ALTER TABLE hta202.publication_evidence OWNER TO hta202_owner;
REVOKE ALL ON hta202.publication_evidence FROM PUBLIC;
GRANT SELECT ON hta202.publication_evidence TO hta202_reader;
-- The database records an event time, NOT a transaction-commit time.
-- Use a fixed canonical UTF-8 payload contract to prevent unsigned metadata substitution.
CREATE FUNCTION hta202.digest_evidence(
 v uuid, f text, event_id uuid, recorded timestamptz, policy text
) RETURNS text LANGUAGE sql IMMUTABLE STRICT SET search_path = pg_catalog AS $$
 SELECT 'sha256:' || encode(sha256(convert_to(
   'HTA202-EVIDENCE-V1|' || v::text || '|' || f || '|' || event_id::text || '|' ||
   to_char(recorded AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US') || 'Z|' || policy || '|1', 'UTF8')),'hex');
$$;
ALTER FUNCTION hta202.digest_evidence(uuid,text,uuid,timestamptz,text) OWNER TO hta202_owner;
REVOKE ALL ON FUNCTION hta202.digest_evidence(uuid,text,uuid,timestamptz,text) FROM PUBLIC;

-- Last line of defence: block UPDATE/DELETE, including changes to historical evidence by writer.
CREATE FUNCTION hta202.reject_evidence_mutation() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, hta202 AS $$
BEGIN
 RAISE EXCEPTION 'Historical evidence immutable' USING ERRCODE='23514';
END $$;
ALTER FUNCTION hta202.reject_evidence_mutation() OWNER TO hta202_owner;
REVOKE ALL ON FUNCTION hta202.reject_evidence_mutation() FROM PUBLIC;
CREATE TRIGGER hta202_immutable BEFORE UPDATE OR DELETE ON hta202.publication_evidence
 FOR EACH ROW EXECUTE FUNCTION hta202.reject_evidence_mutation();

-- Deferred integrity check checks the actual final state at transaction end,
-- including direct SQL UPDATE that bypasses the dedicated writer function.
CREATE FUNCTION hta202.require_evidence_at_commit() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, hta202 AS $$
DECLARE n integer;
BEGIN
 IF NEW.status = 'PUBLISHED' AND OLD.status IS DISTINCT FROM 'PUBLISHED' THEN
   SELECT count(*) INTO n FROM hta202.publication_evidence e
    WHERE e.route_pattern_version_id = NEW.id
      AND e.content_fingerprint = NEW.content_fingerprint
      AND e.evidence_digest = hta202.digest_evidence(
        e.route_pattern_version_id,e.content_fingerprint,e.publication_event_id,
        e.publication_recorded_at,e.publication_policy_version);
   IF n <> 1 THEN
      RAISE EXCEPTION 'Published version lacks immutable evidence' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NULL;
END $$;
ALTER FUNCTION hta202.require_evidence_at_commit() OWNER TO hta202_owner;
REVOKE ALL ON FUNCTION hta202.require_evidence_at_commit() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER hta202_publication_guard
 AFTER UPDATE OF status ON public.route_pattern_version DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION hta202.require_evidence_at_commit();

-- Existing publication logic runs under a trusted owner in the isolated test database.
-- Writer receives EXECUTE only, not table INSERT or route_pattern_version UPDATE.
CREATE FUNCTION hta202.publish_test_only(v uuid, expected_fingerprint text)
 RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
 SET search_path = pg_catalog, public, hta202 AS $$
DECLARE rec record; ev_id uuid; event_id uuid; recorded timestamptz;
BEGIN
 IF v IS NULL OR expected_fingerprint IS NULL OR
    expected_fingerprint !~ '^sha256:[0-9a-f]{64}$' THEN
   RAISE EXCEPTION 'Invalid publication parameters' USING ERRCODE='22023';
 END IF;
 SELECT p.id,p.status,p.content_fingerprint INTO rec FROM public.route_pattern_version p
  WHERE p.id=v FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Unknown version' USING ERRCODE='23514'; END IF;
 IF rec.status='PUBLISHED' THEN
   SELECT evidence_id INTO ev_id FROM hta202.publication_evidence
    WHERE route_pattern_version_id=v AND content_fingerprint=expected_fingerprint;
   IF ev_id IS NULL THEN RAISE EXCEPTION 'Historical authority unproven' USING ERRCODE='23514'; END IF;
   RETURN ev_id;
 END IF;
 IF rec.status<>'DRAFT' OR public.transport_canonical_fingerprint(v)<>expected_fingerprint THEN
    RAISE EXCEPTION 'Fingerprint or version conflict' USING ERRCODE='23514';
 END IF;
 -- PostgreSQL V2 guard independently checks structural snapshot and publication rules.
 UPDATE public.route_pattern_version SET status='PUBLISHED',
  content_fingerprint=expected_fingerprint WHERE id=v;
 ev_id=gen_random_uuid(); event_id=gen_random_uuid(); recorded=clock_timestamp();
 INSERT INTO hta202.publication_evidence(
 evidence_id,route_pattern_version_id,content_fingerprint,publication_event_id,
 publication_recorded_at,publication_policy_version,evidence_schema_version,evidence_digest)
 VALUES (ev_id,v,expected_fingerprint,event_id,recorded,'HTA202-TEST-ONLY-v1',1,
 hta202.digest_evidence(v,expected_fingerprint,event_id,recorded,'HTA202-TEST-ONLY-v1'));
 RETURN ev_id;
END $;
ALTER FUNCTION hta202.publish_test_only(uuid,text) OWNER TO hta202_owner;
REVOKE ALL ON FUNCTION hta202.publish_test_only(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hta202.publish_test_only(uuid,text) TO hta202_writer;

-- Owner can run narrowly scoped publication; must not get generalized access from writer.
GRANT SELECT, UPDATE ON public.route_pattern_version TO hta202_owner;
GRANT SELECT ON public.route_pattern,public.route_direction,public.stop_occurrence,
 public.fare_section,public.operator_assignment TO hta202_owner;
GRANT EXECUTE ON FUNCTION public.transport_canonical_fingerprint(uuid) TO hta202_owner;
GRANT USAGE ON SCHEMA public TO hta202_owner;
-- No table DML is granted to writer, reader, or runtime.
