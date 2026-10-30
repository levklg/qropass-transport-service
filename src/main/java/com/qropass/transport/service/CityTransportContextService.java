package com.qropass.transport.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Внутреннее доверенное чтение транспортных фактов для CITY-01.
 * Не определяет городскую категорию, тариф, продавца или разрешение продажи.
 * Вызывающая сторона обязана аутентифицироваться через отдельный утверждённый контракт.
 */
@Service
public class CityTransportContextService {
    private final JdbcTemplate jdbc;

    public CityTransportContextService(JdbcTemplate jdbc) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
    }

    /** Проверенный снимок движения, но не доказательство доступности CITY-01 к продаже. */
    public record Context(UUID legalEntityId, String inn, UUID routeId, UUID directionId,
                          UUID patternId, UUID versionId, String publicationStatus,
                          Instant effectiveFrom, Instant effectiveUntil, String contentFingerprint,
                          boolean cityCategoryConfirmed, boolean salePermitted) {}

    /**
     * Находит строго одну действующую организацию, назначение и опубликованную версию.
     * selector содержит только ИНН и UUID; неподтверждённая категория маршрута не выводится.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context resolveByInn(String inn,UUID routeId,UUID versionId,Instant at) {
        if(inn==null || !inn.matches("[0-9]{10}|[0-9]{12}") ||
           routeId==null || versionId==null || at==null)
            throw new IllegalArgumentException("Некорректный селектор транспортных фактов");

        List<UUID> legal=jdbc.query(
            "SELECT id FROM legal_entity WHERE inn=? AND status='ACTIVE'",
            (rs,n)->rs.getObject(1,UUID.class),inn);
        if(legal.size()!=1) throw new SecurityException("Нет однозначной действующей организации");
        UUID legalId=legal.get(0);

        Integer validAssignments=jdbc.queryForObject(
            "SELECT count(*) FROM operator_assignment WHERE legal_entity_id=? AND route_id=? "+
            "AND status='VERIFIED' AND role='CARRIER' "+
            "AND valid_from IS NOT NULL AND valid_from<=? "+
            "AND (valid_until IS NULL OR valid_until>?) "+
            "AND evidence_reference IS NOT NULL AND btrim(evidence_reference)<>''",
            Integer.class,legalId,routeId,at,at);
        if(validAssignments==null || validAssignments!=1)
            throw new SecurityException("Не подтверждено однозначное назначение перевозчика");

        List<Context> versions=jdbc.query(
            "SELECT d.id,p.id,v.id,v.status,v.effective_from,v.effective_until,v.content_fingerprint "+
            "FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id "+
            "JOIN route_direction d ON d.id=p.direction_id "+
            "JOIN route r ON r.id=d.route_id "+
            "WHERE v.id=? AND v.legal_entity_id=? AND r.id=? "+
            "AND v.status='PUBLISHED' AND v.effective_from IS NOT NULL "+
            "AND v.effective_from<=? AND (v.effective_until IS NULL OR v.effective_until>?) "+
            "AND v.content_fingerprint ~ '^sha256:[0-9a-f]{64}$'",
            (rs,n)->new Context(legalId,inn,routeId,rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),
                rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant(),
                rs.getString(7),false,false),
            versionId,legalId,routeId,at,at);
        if(versions.size()!=1) throw new SecurityException("Нет однозначной действующей опубликованной версии");
        return versions.get(0);
    }
}
