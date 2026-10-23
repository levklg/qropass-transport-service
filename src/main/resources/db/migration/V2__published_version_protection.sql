-- T1.3: публикация в одной транзакции, запрет продаж сохраняется.
ALTER TABLE route_pattern_version DROP CONSTRAINT route_pattern_version_status_check;
ALTER TABLE route_pattern_version DROP CONSTRAINT route_pattern_version_content_fingerprint_check;
ALTER TABLE route_pattern_version
  ADD CONSTRAINT ck_transport_published_fingerprint CHECK (
    (status='DRAFT' AND content_fingerprint IS NULL)
    OR (status='PUBLISHED' AND content_fingerprint ~ '^sha256:[0-9a-f]{64}$')
  );

-- Все записи в дочерние таблицы сериализуются с публикацией блокировкой строки версии.
CREATE OR REPLACE FUNCTION guard_version_child_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v uuid; current_state text;
BEGIN
  IF TG_OP='DELETE' THEN v:=OLD.pattern_version_id;
  ELSE v:=NEW.pattern_version_id; END IF;
  SELECT status INTO current_state FROM route_pattern_version WHERE id=v FOR UPDATE;
  IF current_state IS NULL THEN
    RAISE EXCEPTION 'Версия для изменения не существует' USING ERRCODE='23503';
  END IF;
  IF current_state <> 'DRAFT' THEN
    RAISE EXCEPTION 'Опубликованное содержимое неизменяемо' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' AND NEW.pattern_version_id IS DISTINCT FROM OLD.pattern_version_id THEN
    RAISE EXCEPTION 'Перемещать содержимое между версиями запрещено' USING ERRCODE='23514';
  END IF;
  -- Ревизия охватывает также изменения в дочерних таблицах.
  UPDATE route_pattern_version SET lock_version=lock_version+1 WHERE id=v;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER occurrence_version_guard BEFORE INSERT OR UPDATE OR DELETE ON stop_occurrence
 FOR EACH ROW EXECUTE FUNCTION guard_version_child_write();
CREATE TRIGGER fare_section_version_guard BEFORE INSERT OR UPDATE OR DELETE ON fare_section
 FOR EACH ROW EXECUTE FUNCTION guard_version_child_write();


-- Независимая серверная верификация канонического байтового снимка.
-- uuid_send/int4send/int8send используют сетевой порядок байт PostgreSQL.
CREATE OR REPLACE FUNCTION transport_canonical_fingerprint(v_id uuid)
RETURNS text LANGUAGE plpgsql STABLE AS $
DECLARE
  payload bytea;
  legal uuid; route_uuid uuid; direction_uuid uuid; pattern_uuid uuid;
  r record; n integer;
BEGIN
  SELECT v.legal_entity_id,p.direction_id,d.route_id,v.pattern_id
    INTO legal,direction_uuid,route_uuid,pattern_uuid
  FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id
    JOIN route_direction d ON d.id=p.direction_id
  WHERE v.id=v_id;
  IF legal IS NULL OR route_uuid IS NULL OR direction_uuid IS NULL OR pattern_uuid IS NULL THEN
    RAISE EXCEPTION 'Отсутствует структурная идентичность версии' USING ERRCODE='23514';
  END IF;
  payload:=convert_to('QRP-TRANSPORT-FP','UTF8') || decode('01','hex')
    || uuid_send(legal) || uuid_send(route_uuid) || uuid_send(direction_uuid)
    || uuid_send(pattern_uuid) || uuid_send(v_id);
  SELECT count(*)::int INTO n FROM stop_occurrence WHERE pattern_version_id=v_id;
  payload:=payload || int4send(n);
  FOR r IN SELECT * FROM stop_occurrence WHERE pattern_version_id=v_id
    ORDER BY sequence_number ASC,id ASC LOOP
    payload:=payload || uuid_send(r.id) || uuid_send(r.stop_id)
      || int4send(r.sequence_number)
      || CASE WHEN r.pickup_allowed THEN decode('01','hex') ELSE decode('00','hex') END
      || CASE WHEN r.dropoff_allowed THEN decode('01','hex') ELSE decode('00','hex') END;
    IF r.distance_meters IS NULL THEN
      payload:=payload || decode('00','hex');
    ELSE
      payload:=payload || decode('01','hex') || int8send(r.distance_meters);
    END IF;
  END LOOP;
  SELECT count(*)::int INTO n FROM fare_section WHERE pattern_version_id=v_id;
  payload:=payload || int4send(n);
  FOR r IN SELECT * FROM fare_section WHERE pattern_version_id=v_id
    ORDER BY sequence_number ASC,id ASC LOOP
    payload:=payload || uuid_send(r.id) || uuid_send(r.start_occurrence_id)
      || uuid_send(r.end_occurrence_id) || int4send(r.sequence_number);
  END LOOP;
  RETURN 'sha256:' || encode(sha256(payload),'hex');
END $;

CREATE OR REPLACE FUNCTION guard_version_header_write() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'Опубликованная версия не удаляется' USING ERRCODE='23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status='PUBLISHED' THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'Опубликованная версия неизменяема' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.status='PUBLISHED' THEN
    IF NEW.content_fingerprint IS NULL OR NEW.content_fingerprint !~ '^sha256:[0-9a-f]{64}
  IF NEW.pattern_id IS DISTINCT FROM OLD.pattern_id
     OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id THEN
    RAISE EXCEPTION 'Идентичность версии после создания не меняется' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER version_header_guard BEFORE UPDATE OR DELETE ON route_pattern_version
 FOR EACH ROW EXECUTE FUNCTION guard_version_header_write();

-- Защита идентичностей предков опубликованной версии.
CREATE OR REPLACE FUNCTION guard_published_catalog_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME='route_pattern' THEN
    IF EXISTS(SELECT 1 FROM route_pattern_version v WHERE v.pattern_id=OLD.id AND v.status='PUBLISHED') THEN
      IF TG_OP='DELETE' OR NEW.direction_id IS DISTINCT FROM OLD.direction_id THEN
        RAISE EXCEPTION 'Нельзя изменять принадлежность опубликованного варианта' USING ERRCODE='23514';
      END IF;
    END IF;
  ELSIF TG_TABLE_NAME='route_direction' THEN
    IF EXISTS(SELECT 1 FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id
      WHERE p.direction_id=OLD.id AND v.status='PUBLISHED') THEN
      IF TG_OP='DELETE' OR NEW.route_id IS DISTINCT FROM OLD.route_id THEN
        RAISE EXCEPTION 'Нельзя изменять маршрут опубликованного направления' USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER published_pattern_identity BEFORE UPDATE OR DELETE ON route_pattern
 FOR EACH ROW EXECUTE FUNCTION guard_published_catalog_identity();
CREATE TRIGGER published_direction_identity BEFORE UPDATE OR DELETE ON route_direction
 FOR EACH ROW EXECUTE FUNCTION guard_published_catalog_identity();

-- Не разрешать продажу при любом состоянии версии.
ALTER TABLE transport_version_activation DROP CONSTRAINT transport_version_activation_sale_status_check;
ALTER TABLE transport_version_activation ADD CONSTRAINT ck_t13_sales_disabled CHECK(sale_status='NOT_FOR_SALE');

       OR NEW.content_fingerprint IS DISTINCT FROM transport_canonical_fingerprint(NEW.id) THEN
      RAISE EXCEPTION 'Fingerprint не совпадает с каноническим снимком БД' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM stop_occurrence WHERE pattern_version_id=NEW.id)<2
       OR EXISTS (SELECT 1 FROM stop_occurrence WHERE pattern_version_id=NEW.id
                  AND (NOT pickup_allowed AND NOT dropoff_allowed))
       OR EXISTS (
         SELECT 1 FROM (
           SELECT sequence_number,row_number() OVER (ORDER BY sequence_number) AS expected
           FROM stop_occurrence WHERE pattern_version_id=NEW.id
         ) t WHERE sequence_number<>expected
       ) THEN
      RAISE EXCEPTION 'Структура версии не готова к публикации' USING ERRCODE='23514';
    END IF;
  END IF;
  IF NEW.pattern_id IS DISTINCT FROM OLD.pattern_id
     OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id THEN
    RAISE EXCEPTION 'Идентичность версии после создания не меняется' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER version_header_guard BEFORE UPDATE OR DELETE ON route_pattern_version
 FOR EACH ROW EXECUTE FUNCTION guard_version_header_write();

-- Защита идентичностей предков опубликованной версии.
CREATE OR REPLACE FUNCTION guard_published_catalog_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME='route_pattern' THEN
    IF EXISTS(SELECT 1 FROM route_pattern_version v WHERE v.pattern_id=OLD.id AND v.status='PUBLISHED') THEN
      IF TG_OP='DELETE' OR NEW.direction_id IS DISTINCT FROM OLD.direction_id THEN
        RAISE EXCEPTION 'Нельзя изменять принадлежность опубликованного варианта' USING ERRCODE='23514';
      END IF;
    END IF;
  ELSIF TG_TABLE_NAME='route_direction' THEN
    IF EXISTS(SELECT 1 FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id
      WHERE p.direction_id=OLD.id AND v.status='PUBLISHED') THEN
      IF TG_OP='DELETE' OR NEW.route_id IS DISTINCT FROM OLD.route_id THEN
        RAISE EXCEPTION 'Нельзя изменять маршрут опубликованного направления' USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER published_pattern_identity BEFORE UPDATE OR DELETE ON route_pattern
 FOR EACH ROW EXECUTE FUNCTION guard_published_catalog_identity();
CREATE TRIGGER published_direction_identity BEFORE UPDATE OR DELETE ON route_direction
 FOR EACH ROW EXECUTE FUNCTION guard_published_catalog_identity();

-- Не разрешать продажу при любом состоянии версии.
ALTER TABLE transport_version_activation DROP CONSTRAINT transport_version_activation_sale_status_check;
ALTER TABLE transport_version_activation ADD CONSTRAINT ck_t13_sales_disabled CHECK(sale_status='NOT_FOR_SALE');
