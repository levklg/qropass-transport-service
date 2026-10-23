package com.qropass.transport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Проверка запрета публикации на действительной цепочке внешних ключей. */
class TransportPostgresIntegrationTest {
    /** Публикация запрещена CHECK-ограничением, а не отсутствующим FK. */
    @Test void migrationAndDraftOnlyGuards() throws Exception {
        try (PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15")) {
            postgres.start();
            Flyway flyway=Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                    .load();
            flyway.migrate();
            try (Connection connection=DriverManager.getConnection(
                    postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
                UUID entity=UUID.randomUUID();
                UUID route=UUID.randomUUID();
                UUID direction=UUID.randomUUID();
                UUID pattern=UUID.randomUUID();
                UUID version=UUID.randomUUID();
                insert(connection,"insert into legal_entity(id,legal_name) values (?, 'Перевозчик')",entity);
                insert(connection,"insert into route(id,route_number) values (?,'1')",route);
                insert(connection,"insert into route_direction(id,route_id,direction_code) values (?,?,'F')",direction,route);
                insert(connection,"insert into route_pattern(id,direction_id,pattern_code) values (?,?,'A')",pattern,direction);
                insert(connection,"insert into route_pattern_version(id,pattern_id,legal_entity_id,version_number,status) values (?,?,?,1,'DRAFT')",version,pattern,entity);
                try (PreparedStatement statement=connection.prepareStatement(
                        "update route_pattern_version set status='PUBLISHED' where id=?")) {
                    statement.setObject(1,version);
                    SQLException error=assertThrows(SQLException.class,statement::executeUpdate);
                    assertEquals("23514",error.getSQLState());
                }
                try (PreparedStatement statement=connection.prepareStatement(
                        "insert into transport_version_activation(id,pattern_version_id,sale_status) values (?,?,'AVAILABLE_FOR_SALE')")) {
                    statement.setObject(1,UUID.randomUUID());
                    statement.setObject(2,version);
                    SQLException error=assertThrows(SQLException.class,statement::executeUpdate);
                    assertEquals("23514",error.getSQLState());
                }
            }
        }
    }
    /** Сохраняет запись с UUID параметрами. */
    private static void insert(Connection connection,String sql,UUID... uuids) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement(sql)) {
            for(int i=0;i<uuids.length;i++) statement.setObject(i+1,uuids[i]);
            statement.executeUpdate();
        }
    }
}
