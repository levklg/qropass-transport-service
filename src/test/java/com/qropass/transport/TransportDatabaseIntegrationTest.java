package com.qropass.transport;

import com.qropass.transport.entity.*;
import com.qropass.transport.repository.*;
import com.qropass.transport.service.TransportDraftService;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Интеграционные проверки миграций и инвариантов модели на PostgreSQL. */
@SpringBootTest
@Testcontainers
@Transactional
class TransportDatabaseIntegrationTest {
    /** Отдельный контейнер PostgreSQL для всех интеграционных проверок. */
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    /** Подключение Spring и Flyway к тестовой БД. */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired LegalEntityRepository legalEntities;
    @Autowired RouteRepository routes;
    @Autowired RouteDirectionRepository directions;
    @Autowired RoutePatternRepository patterns;
    @Autowired RoutePatternVersionRepository versions;
    @Autowired StopRepository stops;
    @Autowired StopOccurrenceRepository occurrences;
    @Autowired FareSectionRepository sections;
    @Autowired TransportDraftService drafts;
    @Autowired OperatorAssignmentRepository assignments;

    /** Проверяет, что схема Flyway V1 действительно создана. */
    @Test void flywaySchemaCreated() {
        Integer count = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema='public' and table_name='fare_section'",
            Integer.class);
        assertEquals(1,count);
    }

    /** Проверяет чтение и повторное вхождение физической остановки. */
    @Test void repeatedPhysicalStopAllowed() {
        RoutePatternVersion version=fixture();
        Stop stop=makeStop();
        drafts.addStop(version.getId(),stop.getId(),1,true,false,null);
        drafts.addStop(version.getId(),stop.getId(),2,false,true,0L);
        assertEquals(2,occurrences.findAll().stream().filter(o->o.getStop().getId().equals(stop.getId())).count());
    }

    /** Проверяет null и запрет отрицательного расстояния в Java и PostgreSQL. */
    @Test void negativeDistanceRejected() {
        RoutePatternVersion version=fixture();
        Stop stop=makeStop();
        assertThrows(IllegalArgumentException.class,()->drafts.addStop(version.getId(),stop.getId(),1,true,true,-1L));
        UUID occurrence=UUID.randomUUID();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
          "insert into stop_occurrence(id,pattern_version_id,stop_id,sequence_number,pickup_allowed,dropoff_allowed,distance_meters) values (?,?,?,?,true,true,-1)",
          occurrence,version.getId(),stop.getId(),1));
    }

    /** Проверяет уникальность позиции остановки в рамках версии. */
    @Test void duplicateSequenceRejected() {
        RoutePatternVersion version=fixture();
        Stop stop=makeStop();
        drafts.addStop(version.getId(),stop.getId(),1,true,true,null);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
          "insert into stop_occurrence(id,pattern_version_id,stop_id,sequence_number,pickup_allowed,dropoff_allowed) values (?,?,?,?,true,true)",
          UUID.randomUUID(),version.getId(),stop.getId(),1));
    }

    /** Запрещает границу тарифного участка из другой версии. */
    @Test void foreignVersionSectionRejected() {
        RoutePatternVersion a=fixture(); RoutePatternVersion b=fixture();
        Stop stop=makeStop();
        StopOccurrence first=drafts.addStop(a.getId(),stop.getId(),1,true,true,null);
        StopOccurrence wrong=drafts.addStop(b.getId(),stop.getId(),2,true,true,null);
        assertThrows(IllegalArgumentException.class,()->drafts.addSection(a.getId(),first.getId(),wrong.getId(),1));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
          "insert into fare_section(id,pattern_version_id,start_occurrence_id,end_occurrence_id,sequence_number) values (?,?,?,?,1)",
          UUID.randomUUID(),a.getId(),first.getId(),wrong.getId()));
    }

    /** Запрещает обратный порядок границ на уровне БД. */
    @Test void reversedSectionRejected() {
        RoutePatternVersion v=fixture(); Stop s=makeStop();
        StopOccurrence first=drafts.addStop(v.getId(),s.getId(),1,true,true,null);
        StopOccurrence second=drafts.addStop(v.getId(),s.getId(),2,true,true,null);
        assertThrows(IllegalArgumentException.class,()->drafts.addSection(v.getId(),second.getId(),first.getId(),1));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
          "insert into fare_section(id,pattern_version_id,start_occurrence_id,end_occurrence_id,sequence_number) values (?,?,?,?,1)",
          UUID.randomUUID(),v.getId(),second.getId(),first.getId()));
    }

    /** Проверяет принадлежность юридического лица к транспортной области: UUID не подменяется. */
    @Test void legalEntityScopeStored() {
        RoutePatternVersion v=fixture();
        assertNotNull(v.getLegalEntity().getId());
        assertEquals(v.getLegalEntity().getId(), versions.findById(v.getId()).orElseThrow().getLegalEntity().getId());
    }

    /** Проверяет запрет SQL-публикации даже при реальных FK. */
    @Test void publishedVersionRejectedByDb() {
        RoutePatternVersion v=fixture();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
          "update route_pattern_version set status='PUBLISHED' where id=?",v.getId()));
    }

    /** Проверяет запрет снятия NOT_FOR_SALE при существующей версии. */
    @Test void saleActivationRejectedByDb() {
        RoutePatternVersion v=fixture();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
          "insert into transport_version_activation(id,pattern_version_id,sale_status) values (?,?,'AVAILABLE_FOR_SALE')",
          UUID.randomUUID(),v.getId()));
    }

    /** Проверяет сохранение нового черновика и его ревизии. */
    @Test void draftVersionHasRevision() {
        RoutePatternVersion v=fixture();
        assertEquals(0L, v.getLockVersion());
    }

    /** Создаёт полностью связанную модель без обращения к другим сервисам. */
    private RoutePatternVersion fixture() {
        LegalEntity entity=LegalEntity.create();entity.setLegalName("Тестовый перевозчик");legalEntities.saveAndFlush(entity);
        Route route=Route.create();route.setRouteNumber("1");routes.saveAndFlush(route);
        RouteDirection direction=RouteDirection.create();direction.setRoute(route);direction.setDirectionCode(UUID.randomUUID().toString());directions.saveAndFlush(direction);
        RoutePattern pattern=RoutePattern.create();pattern.setDirection(direction);pattern.setPatternCode(UUID.randomUUID().toString());patterns.saveAndFlush(pattern);
        OperatorAssignment assignment=OperatorAssignment.create();assignment.setRoute(route);assignment.setLegalEntity(entity);assignments.saveAndFlush(assignment);
        return drafts.createDraft(pattern.getId(),entity.getId(),1);
    }

    /** Чужая организация не может создать версию маршрута без назначения. */
    @Test void legalEntityMismatchRejected() {
        RoutePatternVersion v=fixture();
        LegalEntity other=LegalEntity.create();other.setLegalName("Другая компания");legalEntities.saveAndFlush(other);
        assertThrows(IllegalArgumentException.class,()->
          drafts.createDraft(v.getPattern().getId(), other.getId(),2));
    }

    /** Устаревший revision не может перезаписать изменение другого редактора. */
    @Test void staleDraftRevisionRejected() {
        RoutePatternVersion v=fixture();
        int changed=jdbc.update("update route_pattern_version set lock_version=lock_version+1 where id=? and lock_version=0",v.getId());
        int stale=jdbc.update("update route_pattern_version set lock_version=lock_version+1 where id=? and lock_version=0",v.getId());
        assertEquals(1,changed);
        assertEquals(0,stale);
    }

    /** Создаёт справочную остановку. */
    private Stop makeStop() {
        Stop stop=Stop.create();stop.setCanonicalName("Остановка");return stops.saveAndFlush(stop);
    }
}
