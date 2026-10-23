package com.qropass.transport;

import com.qropass.transport.entity.*;
import com.qropass.transport.model.*;
import com.qropass.transport.repository.*;
import com.qropass.transport.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Проверка атомарной публикации и запрета SQL-изменений на PostgreSQL. */
@SpringBootTest
@Testcontainers
class TransportPublicationIntegrationTest {
    /** Независимая PostgreSQL для публикации. */
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15");
    /** Параметры изолированного тестового контейнера. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired LegalEntityRepository entities;
    @Autowired RouteRepository routes;
    @Autowired RouteDirectionRepository directions;
    @Autowired RoutePatternRepository patterns;
    @Autowired OperatorAssignmentRepository assignments;
    @Autowired RoutePatternVersionRepository versions;
    @Autowired StopRepository stops;
    @Autowired StopOccurrenceRepository occurrences;
    @Autowired FareSectionRepository sections;
    @Autowired TransportVersionActivationRepository activations;
    @Autowired TransportDraftService drafts;
    @Autowired TransportPublicationService publisher;
    @Autowired JdbcTemplate jdbc;

    /** Создаёт юридически связанную цепочку идентичности. */
    private UUID draft() {
        LegalEntity entity=LegalEntity.create();entity.setLegalName("Тестовый перевозчик");entities.saveAndFlush(entity);
        Route route=Route.create();route.setRouteNumber(UUID.randomUUID().toString());routes.saveAndFlush(route);
        RouteDirection direction=RouteDirection.create();direction.setRoute(route);
        direction.setDirectionCode("forward");directions.saveAndFlush(direction);
        RoutePattern pattern=RoutePattern.create();pattern.setDirection(direction);pattern.setPatternCode("primary");
        patterns.saveAndFlush(pattern);
        OperatorAssignment assignment=OperatorAssignment.create();assignment.setRoute(route);
        assignment.setLegalEntity(entity);assignments.saveAndFlush(assignment);
        return drafts.createDraft(pattern.getId(),entity.getId(),1).getId();
    }
    /** Добавляет действительные две остановки для публикации. */
    private UUID completeDraft() {
        UUID id=draft();
        Stop first=Stop.create();first.setCanonicalName("А");stops.saveAndFlush(first);
        Stop last=Stop.create();last.setCanonicalName("Б");stops.saveAndFlush(last);
        drafts.addStop(id,first.getId(),1,true,false,0L);
        drafts.addStop(id,last.getId(),2,false,true,1500L);
        return id;
    }
    /** Повторная публикация возвращает сохранённый fingerprint без повторного создания. */
    @Test void publicationIdempotentAndNoSales() {
        UUID id=completeDraft();
        String first=publisher.publish(id);
        String second=publisher.publish(id);
        assertEquals(first,second);
        assertTrue(first.matches("sha256:[0-9a-f]{64}"));
        RoutePatternVersion version=versions.findById(id).orElseThrow();
        assertEquals(TransportVersionStatus.PUBLISHED,version.getStatus());
        assertEquals(first,version.getContentFingerprint());
        assertEquals(TransportSaleStatus.NOT_FOR_SALE,
            activations.findByPatternVersion_Id(id).orElseThrow().getSaleStatus());
    }
    /** Частично заполненный черновик не публикуется. */
    @Test void incompleteDraftRejected() {
        UUID id=draft();
        assertThrows(PublicationConflictException.class,()->publisher.publish(id));
        assertEquals(TransportVersionStatus.DRAFT,versions.findById(id).orElseThrow().getStatus());
    }
    /** Изменение опубликованных остановок и fingerprint запрещено прямым SQL. */
    @Test void publishedSqlMutationsRejected() {
        UUID id=completeDraft();
        String original=publisher.publish(id);
        UUID occurrence=occurrences.findByPatternVersion_Id(id).get(0).getId();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->jdbc.update("update stop_occurrence set distance_meters=123 where id=?",occurrence));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->jdbc.update("delete from stop_occurrence where id=?",occurrence));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->jdbc.update("update route_pattern_version set content_fingerprint=? where id=?",
                "sha256:"+"f".repeat(64),id));
        assertEquals(original,versions.findById(id).orElseThrow().getContentFingerprint());
    }
    /** Опубликованные остановочные вхождения нельзя редактировать через JPA. */
    @Test void publishedJpaMutationsRejected() {
        UUID id=completeDraft();publisher.publish(id);
        UUID stopId=occurrences.findByPatternVersion_Id(id).get(0).getId();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->{
            StopOccurrence occurrence=occurrences.findById(stopId).orElseThrow();
            occurrence.setDistanceMeters(123L);
            occurrences.saveAndFlush(occurrence);
        });
    }
    /** PostgreSQL пересчитывает hash самостоятельно и запрещает поддельную публикацию SQL. */
    @Test void counterfeitFingerprintRejected() {
        UUID id=completeDraft();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
          ()->jdbc.update("update route_pattern_version set status='PUBLISHED', content_fingerprint=? where id=?",
             "sha256:"+"0".repeat(64),id));
        assertEquals(TransportVersionStatus.DRAFT,versions.findById(id).orElseThrow().getStatus());
    }
    /** Java и PostgreSQL независимо вычисляют одинаковый канонический fingerprint. */
    @Test void sqlAndJavaFingerprintEqual() {
        UUID id=completeDraft();
        String published=publisher.publish(id);
        String sqlHash=jdbc.queryForObject("select transport_canonical_fingerprint(?)",String.class,id);
        assertEquals(published,sqlHash);
    }
    /** Параллельные публикации сериализуются и имеют один fingerprint. */
    @Test void parallelPublicationIdempotent() throws Exception {
        UUID id=completeDraft();
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(2);
            java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> action=()->{
                ready.countDown();
                if(!start.await(15,java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Истёк срок ожидания публикации");
                return publisher.publish(id);
            };
            var left=pool.submit(action);
            var right=pool.submit(action);
            assertTrue(ready.await(15,java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(left.get(20,java.util.concurrent.TimeUnit.SECONDS),
                         right.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1L,activations.findAll().stream()
                .filter(a->a.getPatternVersion().getId().equals(id)).count());
        } finally {
            pool.shutdownNow();
        }
    }
    /** Клиент, передающий старую ревизию черновика, получает определённый конфликт. */
    @Test void staleRevisionRejected() {
        UUID id=completeDraft();
        assertThrows(PublicationConflictException.class,()->publisher.publish(id,0L,null));
        assertEquals(TransportVersionStatus.DRAFT,versions.findById(id).orElseThrow().getStatus());
    }
}
