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
  IF NEW.status='PUBLISHED' AND (NEW.content_fingerprint IS NULL OR NEW.content_fingerprint !~ '^sha256:[0-9a-f]{64}$') THEN
    RAISE EXCEPTION 'Публикация требует корректного fingerprint' USING ERRCODE='23514';
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
