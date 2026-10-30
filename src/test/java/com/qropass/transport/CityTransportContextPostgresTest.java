package com.qropass.transport;

import com.qropass.transport.service.CityTransportContextService;
import com.qropass.transport.service.TransportPublicationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** Независимые PostgreSQL 15 фикстуры; они не представляют реальный маршрут ЭГОНОМ-3. */
@SpringBootTest
@Testcontainers
class CityTransportContextPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",postgres::getJdbcUrl);
        p.add("spring.datasource.username",postgres::getUsername);
        p.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CityTransportContextService context;
    @Autowired TransportPublicationService publication;
    @BeforeEach void resetTestFixtures() {
        jdbc.execute("TRUNCATE TABLE legal_entity,route,stop CASCADE");
    }

    private record Fixture(UUID legal,UUID route,UUID version,UUID assignment) {}
    private Fixture seed(String legalStatus,String assignmentStatus,boolean publish,
                         Instant from,Instant until) {
        UUID legal=UUID.randomUUID(),route=UUID.randomUUID(),direction=UUID.randomUUID(),
            pattern=UUID.randomUUID(),version=UUID.randomUUID(),assignment=UUID.randomUUID(),
            stop1=UUID.randomUUID(),stop2=UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity(id,legal_name,inn,status) VALUES(?,'TEST ONLY ЭГОНОМ-3','4027059760',?)",
            legal,legalStatus);
        jdbc.update("INSERT INTO route(id,route_number,route_name) VALUES(?,'TEST-1','Тестовый маршрут')",route);
        jdbc.update("INSERT INTO route_direction(id,route_id,direction_code) VALUES(?,?,'OUT')",direction,route);
        jdbc.update("INSERT INTO route_pattern(id,direction_id,pattern_code) VALUES(?,?,'MAIN')",pattern,direction);
        jdbc.update("INSERT INTO operator_assignment(id,route_id,legal_entity_id,role,status,valid_from,evidence_reference) "+
            "VALUES(?,?,?,'CARRIER',?,?, 'TEST-FIXTURE')",
            assignment,route,legal,assignmentStatus,java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO route_pattern_version(id,pattern_id,legal_entity_id,version_number,effective_from,effective_until) "+
            "VALUES(?,?,?,1,?,?)",version,pattern,legal,
            java.sql.Timestamp.from(from),until==null?null:java.sql.Timestamp.from(until));
        jdbc.update("INSERT INTO stop(id,canonical_name) VALUES(?,'Тест A'),(?,'Тест B')",stop1,stop2);
        jdbc.update("INSERT INTO stop_occurrence(id,pattern_version_id,stop_id,sequence_number,pickup_allowed,dropoff_allowed) "+
            "VALUES(?,?,?,1,true,true),(?,?,?,2,true,true)",
            UUID.randomUUID(),version,stop1,UUID.randomUUID(),version,stop2);
        if(publish)publication.publish(version);
        return new Fixture(legal,route,version,assignment);
    }
    private final Instant now=Instant.parse("2026-10-30T12:00:00Z");
    @Test void publishedActiveFactsAreReadOnlyAndNeverCitySaleApproval() {
        Fixture f=seed("ACTIVE","VERIFIED",true,now.minusSeconds(3600),null);
        Integer before=jdbc.queryForObject("SELECT count(*) FROM route_pattern_version",Integer.class);
        var result=context.resolveByInn("4027059760",f.route,f.version,now);
        assertEquals(f.legal,result.legalEntityId());
        assertEquals(f.route,result.routeId());
        assertEquals(f.version,result.versionId());
        assertEquals("PUBLISHED",result.publicationStatus());
        assertTrue(result.contentFingerprint().startsWith("sha256:"));
        assertFalse(result.cityCategoryConfirmed());
        assertFalse(result.salePermitted());
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM route_pattern_version",Integer.class));
        assertEquals("NOT_FOR_SALE",jdbc.queryForObject(
            "SELECT sale_status FROM transport_version_activation WHERE pattern_version_id=?",
            String.class,f.version));
    }
    @Test void inactiveOrganizationAndForeignRouteDenied() {
        Fixture f=seed("INACTIVE","VERIFIED",true,now.minusSeconds(3600),null);
        assertThrows(SecurityException.class,()->context.resolveByInn("4027059760",f.route,f.version,now));
        assertThrows(SecurityException.class,()->context.resolveByInn("4027059760",UUID.randomUUID(),f.version,now));
    }
    @Test void unverifiedAssignmentAndDraftDenied() {
        Fixture f=seed("ACTIVE","PENDING",false,now.minusSeconds(3600),null);
        assertThrows(SecurityException.class,()->context.resolveByInn("4027059760",f.route,f.version,now));
        jdbc.update("UPDATE operator_assignment SET status='VERIFIED' WHERE id=?",f.assignment);
        assertThrows(SecurityException.class,()->context.resolveByInn("4027059760",f.route,f.version,now));
    }
    @Test void expiredPublishedVersionDenied() {
        Fixture f=seed("ACTIVE","VERIFIED",true,now.minusSeconds(7200),now.minusSeconds(3600));
        assertThrows(SecurityException.class,()->context.resolveByInn("4027059760",f.route,f.version,now));
    }
}
