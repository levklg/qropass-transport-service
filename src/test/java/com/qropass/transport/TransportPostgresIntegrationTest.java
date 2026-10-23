package com.qropass.transport;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.flywaydb.core.Flyway;
import static org.junit.jupiter.api.Assertions.*;

/** Интеграционные проверки ограничений PostgreSQL на чистой БД. */
class TransportPostgresIntegrationTest {
  /** Создаёт изолированную PostgreSQL и проверяет миграцию и защитные ограничения. */
  @Test void migrationAndDraftOnlyGuards() throws Exception {
    try(PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")) {
      db.start();
      Flyway flyway=Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load();
      flyway.migrate();
      try(Connection c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
        UUID id=UUID.randomUUID();
        try(PreparedStatement p=c.prepareStatement("INSERT INTO legal_entity(id,legal_name) VALUES (?,?)")) {
          p.setObject(1,id);p.setString(2,"Тестовая компания");assertEquals(1,p.executeUpdate());
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO route_pattern_version(id,pattern_id,legal_entity_id,version_number,status) VALUES (?,?,?,?,?)")) {
          p.setObject(1,UUID.randomUUID());p.setObject(2,UUID.randomUUID());p.setObject(3,id);p.setInt(4,1);p.setString(5,"PUBLISHED");
          assertThrows(SQLException.class,p::executeUpdate);
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO transport_version_activation(id,pattern_version_id,sale_status) VALUES (?,?,?)")) {
          p.setObject(1,UUID.randomUUID());p.setObject(2,UUID.randomUUID());p.setString(3,"AVAILABLE_FOR_SALE");
          assertThrows(SQLException.class,p::executeUpdate);
        }
      }
    }
  }
}
