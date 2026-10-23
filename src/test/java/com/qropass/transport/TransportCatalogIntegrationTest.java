package com.qropass.transport;

import com.qropass.transport.catalog.TransportCatalogQueryService;
import com.qropass.transport.grpc.v1.*;
import com.qropass.transport.service.TransportPublicationService;
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

/** Чтение реальной PostgreSQL без публикации RPC в сеть. */
@Testcontainers
@SpringBootTest
class TransportCatalogIntegrationTest {
    /** Независимая тестовая PostgreSQL 15. */
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired TransportCatalogQueryService catalog;
    @Autowired TransportPublicationService publication;
    @Autowired JdbcTemplate jdbc;

    /** Независимая тестовая схема для проверки восьми транспортных UUID. */
    private record Fixture(UUID route,UUID direction,UUID pattern,UUID version,UUID legal,
                           UUID physicalStop,UUID first,UUID second,UUID section) {}
    private Fixture seed(boolean publish) {
        UUID route=UUID.randomUUID(),direction=UUID.randomUUID(),pattern=UUID.randomUUID(),
            version=UUID.randomUUID(),legal=UUID.randomUUID(),stop=UUID.randomUUID(),
            first=UUID.randomUUID(),second=UUID.randomUUID(),section=UUID.randomUUID();
        jdbc.update("INSERT INTO route(id,route_number,route_name) VALUES(?,'35','Тестовый маршрут')",route);
        jdbc.update("INSERT INTO route_direction(id,route_id,direction_code,display_name) VALUES(?,?,'OUT','Прямое')",
            direction,route);
        jdbc.update("INSERT INTO route_pattern(id,direction_id,pattern_code) VALUES(?,?,'MAIN')",pattern,direction);
        jdbc.update("INSERT INTO legal_entity(id,legal_name) VALUES(?,'Тестовая организация')",legal);
        jdbc.update("INSERT INTO operator_assignment(id,route_id,legal_entity_id) VALUES(?,?,?)",
            UUID.randomUUID(),route,legal);
        jdbc.update("INSERT INTO route_pattern_version(id,pattern_id,legal_entity_id,version_number,status)"+
            " VALUES(?,?,?,1,'DRAFT')",version,pattern,legal);
        jdbc.update("INSERT INTO stop(id,canonical_name) VALUES(?,'Повторная остановка')",stop);
        jdbc.update("INSERT INTO stop_occurrence(id,pattern_version_id,stop_id,sequence_number,pickup_allowed,dropoff_allowed,distance_meters)"+
            " VALUES(?,?,?,1,true,false,0)",first,version,stop);
        jdbc.update("INSERT INTO stop_occurrence(id,pattern_version_id,stop_id,sequence_number,pickup_allowed,dropoff_allowed,distance_meters)"+
            " VALUES(?,?,?,2,false,true,1500)",second,version,stop);
        jdbc.update("INSERT INTO fare_section(id,pattern_version_id,start_occurrence_id,end_occurrence_id,sequence_number)"+
            " VALUES(?,?,?,?,1)",section,version,first,second);
        if(publish) publication.publish(version);
        return new Fixture(route,direction,pattern,version,legal,stop,first,second,section);
    }
    /** Проверяются направление и фильтр юридического лица. */
    @Test void routesWithDirectionsAndLegalFilter() {
        Fixture f=seed(false);
        var result=catalog.listRoutes(ListRoutesRequest.newBuilder()
            .setLegalEntityId(f.legal().toString()).build());
        assertTrue(result.getRoutesList().stream().anyMatch(r->
            r.getRouteId().equals(f.route().toString()) &&
            r.getDirectionsList().stream().anyMatch(d->d.getDirectionId().equals(f.direction().toString()))));
        assertEquals(0,catalog.listRoutes(ListRoutesRequest.newBuilder()
            .setLegalEntityId(UUID.randomUUID().toString()).build()).getTotalCount());
    }
    /** Фильтрация вариантов по routeId/directionId и детерминированная выдача. */
    @Test void patternsKeepRouteAndDirectionIds() {
        Fixture f=seed(false);
        var result=catalog.listPatterns(ListRoutePatternsRequest.newBuilder()
            .setRouteId(f.route().toString()).setDirectionId(f.direction().toString()).build());
        assertEquals(1,result.getTotalCount());
        assertEquals(f.pattern().toString(),result.getPatterns(0).getPatternId());
        assertEquals(f.direction().toString(),result.getPatterns(0).getDirectionId());
        assertEquals(0,catalog.listPatterns(ListRoutePatternsRequest.newBuilder()
            .setDirectionId(UUID.randomUUID().toString()).build()).getTotalCount());
    }
    /** Пагинация корректна, включая пустую следующую страницу. */
    @Test void routePaginationAndInvalidParameters() {
        Fixture f=seed(false);
        var a=catalog.listRoutes(ListRoutesRequest.newBuilder().setLegalEntityId(f.legal().toString())
            .setPage(PageRequest.newBuilder().setPageSize(1)).build());
        var b=catalog.listRoutes(ListRoutesRequest.newBuilder().setLegalEntityId(f.legal().toString())
            .setPage(PageRequest.newBuilder().setPageSize(1).setPageNumber(1)).build());
        assertEquals(1,a.getRoutesCount());assertEquals(0,b.getRoutesCount());
        assertThrows(IllegalArgumentException.class,()->catalog.listRoutes(ListRoutesRequest.newBuilder()
            .setPage(PageRequest.newBuilder().setPageSize(101)).build()));
        assertThrows(IllegalArgumentException.class,()->catalog.listRoutes(ListRoutesRequest.newBuilder()
            .setLegalEntityId("invalid").build()));
    }
    /** Различение черновика, опубликованной версии и статуса продаж. */
    @Test void publicationStatusIndependentOfSaleStatus() {
        Fixture draft=seed(false),published=seed(true);
        var drafts=catalog.listVersions(ListRoutePatternVersionsRequest.newBuilder()
            .setPatternId(draft.pattern().toString()).setPublicationStatus("DRAFT").build());
        assertEquals(1,drafts.getTotalCount());
        assertEquals("DRAFT",drafts.getVersions(0).getPublicationStatus());
        assertEquals("",drafts.getVersions(0).getContentFingerprint());
        assertEquals("NOT_FOR_SALE",drafts.getVersions(0).getSaleStatus());
        var publishedRows=catalog.listVersions(ListRoutePatternVersionsRequest.newBuilder()
            .setPatternId(published.pattern().toString()).setPublicationStatus("PUBLISHED").build());
        assertEquals(1,publishedRows.getTotalCount());
        assertEquals("PUBLISHED",publishedRows.getVersions(0).getPublicationStatus());
        assertEquals("NOT_FOR_SALE",publishedRows.getVersions(0).getSaleStatus());
        assertTrue(publishedRows.getVersions(0).getContentFingerprint().startsWith("sha256:"));
    }
    /** Повторный физический Stop не схлопывает отдельные StopOccurrence. */
    @Test void orderedStopsAndOptionalDistances() {
        Fixture f=seed(false);
        var result=catalog.listStops(ListPatternStopsRequest.newBuilder()
            .setVersionId(f.version().toString()).build());
        assertEquals(2,result.getTotalCount());
        assertEquals(f.first().toString(),result.getStops(0).getOccurrenceId());
        assertEquals(f.second().toString(),result.getStops(1).getOccurrenceId());
        assertEquals(result.getStops(0).getStopId(),result.getStops(1).getStopId());
        assertEquals(1500,result.getStops(1).getConfirmedDistanceMeters());
        assertEquals(1,catalog.listStops(ListPatternStopsRequest.newBuilder()
            .setVersionId(f.version().toString())
            .setPage(PageRequest.newBuilder().setPageSize(1).setPageNumber(1)).build()).getStopsCount());
    }
    /** Снимок сохраняет UUID, FareSection и точное значение fingerprint. */
    @Test void publishedVersionSnapshotHasOriginalFingerprint() {
        Fixture f=seed(true);
        var result=catalog.getVersion(GetRoutePatternVersionRequest.newBuilder()
            .setVersionId(f.version().toString()).build());
        assertEquals(f.legal().toString(),result.getVersion().getLegalEntityId());
        assertEquals(f.route().toString(),result.getVersion().getRouteId());
        assertEquals(f.direction().toString(),result.getVersion().getDirectionId());
        assertEquals(2,result.getStopsCount());
        assertEquals(1,result.getFareSectionsCount());
        assertEquals(f.section().toString(),result.getFareSections(0).getSectionId());
        assertEquals(f.first().toString(),result.getFareSections(0).getStartOccurrenceId());
        assertEquals(f.second().toString(),result.getFareSections(0).getEndOccurrenceId());
        assertEquals(jdbc.queryForObject("select content_fingerprint from route_pattern_version where id=?",
            String.class,f.version()),result.getVersion().getContentFingerprint());
    }
    /** Отсутствующая версия не замещается фиктивным ответом. */
    @Test void nonexistentVersionRejected() {
        assertThrows(java.util.NoSuchElementException.class,()->catalog.getVersion(
            GetRoutePatternVersionRequest.newBuilder().setVersionId(UUID.randomUUID().toString()).build()));
    }
}
