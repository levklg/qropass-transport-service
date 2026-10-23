-- Первичная независимая модель Transport; публикация до T1.3 запрещена.
CREATE TABLE legal_entity (
 id uuid PRIMARY KEY, legal_name varchar(255), inn varchar(32), ogrn varchar(32), status varchar(32)
);
CREATE TABLE route (
 id uuid PRIMARY KEY, route_number varchar(64), route_name varchar(255), status varchar(32)
);
CREATE TABLE route_direction (
 id uuid PRIMARY KEY, route_id uuid REFERENCES route(id) NOT NULL,
 direction_code varchar(64), display_name varchar(255),
 CONSTRAINT uq_direction_code UNIQUE(route_id,direction_code)
);
CREATE TABLE route_pattern (
 id uuid PRIMARY KEY, direction_id uuid NOT NULL REFERENCES route_direction(id),
 pattern_code varchar(64), display_name varchar(255),
 CONSTRAINT uq_pattern_code UNIQUE(direction_id,pattern_code)
);
CREATE TABLE route_pattern_version (
 id uuid PRIMARY KEY, pattern_id uuid NOT NULL REFERENCES route_pattern(id),
 legal_entity_id uuid NOT NULL REFERENCES legal_entity(id),
 version_number integer NOT NULL CHECK(version_number > 0),
 lock_version bigint NOT NULL DEFAULT 0 CHECK(lock_version >= 0),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status = 'DRAFT'),
 content_fingerprint varchar(80) NULL CHECK(content_fingerprint IS NULL),
 effective_from timestamptz, effective_until timestamptz,
 CHECK(effective_until IS NULL OR effective_from IS NULL OR effective_until > effective_from),
 UNIQUE(pattern_id,version_number)
);
CREATE TABLE stop (
 id uuid PRIMARY KEY, canonical_name varchar(255), status varchar(32),
 latitude numeric(11,8), longitude numeric(11,8),
 CHECK(latitude BETWEEN -90 AND 90), CHECK(longitude BETWEEN -180 AND 180)
);
CREATE TABLE stop_occurrence (
 id uuid PRIMARY KEY,
 pattern_version_id uuid NOT NULL REFERENCES route_pattern_version(id),
 stop_id uuid NOT NULL REFERENCES stop(id),
 sequence_number integer NOT NULL CHECK(sequence_number > 0),
 pickup_allowed boolean NOT NULL DEFAULT true,
 dropoff_allowed boolean NOT NULL DEFAULT true,
 distance_meters bigint NULL CHECK(distance_meters IS NULL OR distance_meters >= 0),
 UNIQUE(pattern_version_id,sequence_number),
 UNIQUE(pattern_version_id,id)
);
CREATE INDEX ix_stop_occurrence_stop ON stop_occurrence(stop_id);
CREATE TABLE fare_section (
 id uuid PRIMARY KEY,
 pattern_version_id uuid NOT NULL REFERENCES route_pattern_version(id),
 start_occurrence_id uuid NOT NULL,
 end_occurrence_id uuid NOT NULL,
 sequence_number integer NOT NULL CHECK(sequence_number > 0),
 CHECK(start_occurrence_id <> end_occurrence_id),
 FOREIGN KEY(pattern_version_id,start_occurrence_id)
    REFERENCES stop_occurrence(pattern_version_id,id),
 FOREIGN KEY(pattern_version_id,end_occurrence_id)
    REFERENCES stop_occurrence(pattern_version_id,id),
 UNIQUE(pattern_version_id,sequence_number)
);
CREATE TABLE operator_assignment (
 id uuid PRIMARY KEY,
 route_id uuid NOT NULL REFERENCES route(id),
 legal_entity_id uuid NOT NULL REFERENCES legal_entity(id),
 role varchar(64),
 status varchar(32),
 valid_from timestamptz, valid_until timestamptz, evidence_reference varchar(255),
 CHECK(valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from)
);
CREATE INDEX ix_assignment_route_entity ON operator_assignment(route_id,legal_entity_id);
CREATE TABLE external_identity_mapping (
 id uuid PRIMARY KEY,
 source_system varchar(100) NOT NULL,
 source_entity_type varchar(100) NOT NULL,
 source_local_id varchar(255) NOT NULL,
 target_entity_type varchar(100) NOT NULL,
 target_uuid uuid NOT NULL,
 status varchar(32),
 UNIQUE(source_system,source_entity_type,source_local_id)
);
CREATE TABLE transport_version_activation (
 id uuid PRIMARY KEY, pattern_version_id uuid NOT NULL UNIQUE REFERENCES route_pattern_version(id),
 sale_status varchar(32) NOT NULL DEFAULT 'NOT_FOR_SALE'
 CHECK(sale_status = 'NOT_FOR_SALE'),
 updated_at timestamptz
);
-- Черновые версии: опубликованные данные и продажа технически невозможны до следующей миграции.

-- Проверка порядка границ на уровне PostgreSQL даже при прямом SQL.
CREATE OR REPLACE FUNCTION check_fare_section_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a integer; b integer;
BEGIN
 SELECT sequence_number INTO a FROM stop_occurrence WHERE id=NEW.start_occurrence_id AND pattern_version_id=NEW.pattern_version_id;
 SELECT sequence_number INTO b FROM stop_occurrence WHERE id=NEW.end_occurrence_id AND pattern_version_id=NEW.pattern_version_id;
 IF a IS NULL OR b IS NULL OR a >= b THEN
    RAISE EXCEPTION 'Неверный порядок границ тарифного участка' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fare_section_order_guard BEFORE INSERT OR UPDATE ON fare_section
FOR EACH ROW EXECUTE FUNCTION check_fare_section_order();

-- Запрещено менять порядок вхождений при существующих тарифных участках.
CREATE OR REPLACE FUNCTION check_stop_occurrence_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.sequence_number <> OLD.sequence_number AND EXISTS (
   SELECT 1 FROM fare_section WHERE start_occurrence_id=OLD.id OR end_occurrence_id=OLD.id
 ) THEN
    RAISE EXCEPTION 'Нельзя изменить порядок остановки, входящей в тарифный участок' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stop_occurrence_order_guard BEFORE UPDATE ON stop_occurrence
FOR EACH ROW EXECUTE FUNCTION check_stop_occurrence_order();

-- Нельзя удалить вхождение, используемое тарифным участком: дополнительно обеспечивается внешними ключами.
