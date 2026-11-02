package com.qropass.transport.e2e;

import com.qropass.transport.service.TransportPublicationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Создаёт исключительно TEST ONLY данные в предварительно проверенной локальной БД. */
final class PurchaseFactsE2EFixtureSeeder {
    private PurchaseFactsE2EFixtureSeeder() {}
    /** Возвращает вымышленные UUID тестовой организации, маршрута и версии. */
    static void seed(JdbcTemplate jdbc, TransportPublicationService publication) {
        UUID legal=UUID.randomUUID(),route=UUID.randomUUID(),direction=UUID.randomUUID(),
            pattern=UUID.randomUUID(),version=UUID.randomUUID(),stop1=UUID.randomUUID(),stop2=UUID.randomUUID();
        Instant from=Instant.now().minusSeconds(3600);
        jdbc.update("INSERT INTO legal_entity(id,legal_name,inn,status) VALUES(?,'TEST ONLY EGONOM-3','4027059760','ACTIVE')",legal);
        jdbc.update("INSERT INTO route(id,route_number,route_name) VALUES(?,'TEST-ONLY','Тестовый маршрут')",route);
        jdbc.update("INSERT INTO route_direction(id,route_id,direction_code) VALUES(?,?,'TEST')",direction,route);
        jdbc.update("INSERT INTO route_pattern(id,direction_id,pattern_code) VALUES(?,?,'TEST')",pattern,direction);
        jdbc.update("INSERT INTO operator_assignment(id,route_id,legal_entity_id,role,status,valid_from,evidence_reference) "+
            "VALUES(?,?,?,'CARRIER','VERIFIED',?,'TEST-ONLY-NOT-LEGAL-EVIDENCE')",
            UUID.randomUUID(),route,legal,java.sql.Timestamp.from(from));
        jdbc.update("INSERT INTO route_pattern_version(id,pattern_id,legal_entity_id,version_number,effective_from) "+
            "VALUES(?,?,?,1,?)",version,pattern,legal,java.sql.Timestamp.from(from));
        jdbc.update("INSERT INTO stop(id,canonical_name) VALUES(?,'TEST A'),(?,'TEST B')",stop1,stop2);
        jdbc.update("INSERT INTO stop_occurrence(id,pattern_version_id,stop_id,sequence_number,pickup_allowed,dropoff_allowed) "+
            "VALUES(?,?,?,1,true,true),(?,?,?,2,true,true)",
            UUID.randomUUID(),version,stop1,UUID.randomUUID(),version,stop2);
        publication.publish(version);
        System.out.println("TEST_ONLY_FIXTURE legalEntityId="+legal+" routeId="+route+
            " patternVersionId="+version+" legalEntityInn=4027059760"+
            " cityCategoryConfirmed=false salePermitted=false");
    }
}
