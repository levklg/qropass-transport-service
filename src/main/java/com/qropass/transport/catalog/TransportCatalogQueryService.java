package com.qropass.transport.catalog;

import com.qropass.transport.grpc.v1.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Транзакционный read-only адаптер каталога. Без прямого доступа внешних сервисов к БД. */
@Service
public class TransportCatalogQueryService {
    private final JdbcTemplate jdbc;
    /** Создаёт адаптер на локальной БД Transport. */
    public TransportCatalogQueryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    private static UUID uuid(String source,boolean required) {
        if(source==null || source.isBlank()) {
            if(required) throw new IllegalArgumentException("Обязательный UUID отсутствует");
            return null;
        }
        try { return UUID.fromString(source); }
        catch(IllegalArgumentException ex) { throw new IllegalArgumentException("Некорректный UUID",ex); }
    }
    private static int limit(PageRequest page) {
        int size=page.getPageSize();
        if(size<0 || size>100) throw new IllegalArgumentException("Размер страницы должен быть 0..100");
        return size==0?20:size;
    }
    private static long offset(PageRequest page,int size) {
        if(page.getPageNumber()<0) throw new IllegalArgumentException("Номер страницы отрицателен");
        return Math.multiplyExact((long)page.getPageNumber(),size);
    }
    private static String string(String value) { return value==null?"":value; }

    /** Маршруты и принадлежащие им направления, отсортированные по номеру и UUID. */
    @Transactional(readOnly=true)
    public ListRoutesResponse listRoutes(ListRoutesRequest request) {
        UUID legal=uuid(request.getLegalEntityId(),false);
        int size=limit(request.getPage());long skip=offset(request.getPage(),size);
        String where=" WHERE (?::uuid IS NULL OR EXISTS(SELECT 1 FROM operator_assignment a"+
            " WHERE a.route_id=r.id AND a.legal_entity_id=?::uuid))";
        String base=" FROM route r"+where;
        long count=jdbc.queryForObject("SELECT count(*)"+base,Long.class,legal,legal);
        var routes=jdbc.query("SELECT r.id,r.route_number,r.route_name"+base+
            " ORDER BY r.route_number NULLS LAST,r.id LIMIT ? OFFSET ?",
            (rs,n)->{
                UUID routeId=(UUID)rs.getObject(1);
                Route.Builder b=Route.newBuilder().setRouteId(routeId.toString())
                    .setNumber(string(rs.getString(2))).setName(string(rs.getString(3)));
                jdbc.query("SELECT id,direction_code,display_name FROM route_direction"+
                        " WHERE route_id=? ORDER BY direction_code NULLS LAST,id",
                    dr->{b.addDirections(RouteDirection.newBuilder()
                        .setDirectionId(((UUID)dr.getObject(1)).toString())
                        .setRouteId(routeId.toString()).setCode(string(dr.getString(2)))
                        .setDisplayName(string(dr.getString(3))).build());},routeId);
                return b.build();
            },legal,legal,size,skip);
        return ListRoutesResponse.newBuilder().addAllRoutes(routes).setTotalCount(count).build();
    }

    /** Варианты направления с независимыми идентификаторами route/direction/pattern. */
    @Transactional(readOnly=true)
    public ListRoutePatternsResponse listPatterns(ListRoutePatternsRequest request) {
        UUID route=uuid(request.getRouteId(),false),direction=uuid(request.getDirectionId(),false),
            legal=uuid(request.getLegalEntityId(),false);
        int size=limit(request.getPage());long skip=offset(request.getPage(),size);
        String base=" FROM route_pattern p JOIN route_direction d ON d.id=p.direction_id"+
            " WHERE (?::uuid IS NULL OR d.route_id=?::uuid)"+
            " AND (?::uuid IS NULL OR p.direction_id=?::uuid)"+
            " AND (?::uuid IS NULL OR EXISTS(SELECT 1 FROM operator_assignment a"+
            " WHERE a.route_id=d.route_id AND a.legal_entity_id=?::uuid))";
        Object[] params={route,route,direction,direction,legal,legal};
        long count=jdbc.queryForObject("SELECT count(*)"+base,Long.class,params);
        var items=jdbc.query("SELECT p.id,d.route_id,p.direction_id,p.pattern_code,p.display_name"+
            base+" ORDER BY d.route_id,p.direction_id,p.pattern_code NULLS LAST,p.id LIMIT ? OFFSET ?",
            (rs,i)->RoutePattern.newBuilder()
                .setPatternId(((UUID)rs.getObject(1)).toString())
                .setRouteId(((UUID)rs.getObject(2)).toString())
                .setDirectionId(((UUID)rs.getObject(3)).toString())
                .setCode(string(rs.getString(4))).setDisplayName(string(rs.getString(5))).build(),
            route,route,direction,direction,legal,legal,size,skip);
        return ListRoutePatternsResponse.newBuilder().addAllPatterns(items).setTotalCount(count).build();
    }

    /** Статусы публикации и продажи никогда не смешиваются. */
    @Transactional(readOnly=true)
    public ListRoutePatternVersionsResponse listVersions(ListRoutePatternVersionsRequest request) {
        UUID pattern=uuid(request.getPatternId(),false),legal=uuid(request.getLegalEntityId(),false);
        String status=request.getPublicationStatus();
        if(!status.isBlank() && !status.equals("DRAFT") && !status.equals("PUBLISHED"))
            throw new IllegalArgumentException("Недопустимый статус публикации");
        int size=limit(request.getPage());long skip=offset(request.getPage(),size);
        String base=" FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id"+
            " JOIN route_direction d ON d.id=p.direction_id"+
            " LEFT JOIN transport_version_activation a ON a.pattern_version_id=v.id"+
            " WHERE (?::uuid IS NULL OR v.pattern_id=?::uuid)"+
            " AND (?::uuid IS NULL OR v.legal_entity_id=?::uuid)"+
            " AND (?='' OR v.status=?)";
        long count=jdbc.queryForObject("SELECT count(*)"+base,Long.class,
            pattern,pattern,legal,legal,status,status);
        var items=jdbc.query("SELECT v.id,v.pattern_id,d.route_id,p.direction_id,v.legal_entity_id,"+
            "v.version_number,v.status,v.content_fingerprint,a.sale_status"+
            base+" ORDER BY v.pattern_id,v.version_number,v.id LIMIT ? OFFSET ?",
            (rs,i)->version(rs),pattern,pattern,legal,legal,status,status,size,skip);
        return ListRoutePatternVersionsResponse.newBuilder().addAllVersions(items).setTotalCount(count).build();
    }

    private static PatternVersion version(ResultSet rs)throws SQLException {
        return PatternVersion.newBuilder().setVersionId(((UUID)rs.getObject(1)).toString())
            .setPatternId(((UUID)rs.getObject(2)).toString())
            .setRouteId(((UUID)rs.getObject(3)).toString())
            .setDirectionId(((UUID)rs.getObject(4)).toString())
            .setLegalEntityId(((UUID)rs.getObject(5)).toString())
            .setVersionNumber(rs.getInt(6)).setPublicationStatus(rs.getString(7))
            .setContentFingerprint(string(rs.getString(8)))
            .setSaleStatus(string(rs.getString(9)) .isBlank()?"NOT_FOR_SALE":rs.getString(9)).build();
    }
    private static StopOccurrence stop(ResultSet rs)throws SQLException {
        StopOccurrence.Builder b=StopOccurrence.newBuilder()
            .setOccurrenceId(((UUID)rs.getObject(1)).toString())
            .setVersionId(((UUID)rs.getObject(2)).toString())
            .setStopId(((UUID)rs.getObject(3)).toString())
            .setSequenceNumber(rs.getInt(4)).setStopName(string(rs.getString(5)))
            .setPickupAllowed(rs.getBoolean(6)).setDropoffAllowed(rs.getBoolean(7));
        long distance=rs.getLong(8);
        if(!rs.wasNull()) b.setConfirmedDistanceMeters(distance);
        return b.build();
    }
    private static final String STOPS=" FROM stop_occurrence o JOIN stop s ON s.id=o.stop_id"+
       " WHERE o.pattern_version_id=?";
    private static final String STOP_FIELDS="SELECT o.id,o.pattern_version_id,o.stop_id,"+
       "o.sequence_number,s.canonical_name,o.pickup_allowed,o.dropoff_allowed,o.distance_meters";

    /** Остановки сохраняют UUID вхождения при повторном посещении одного физического Stop. */
    @Transactional(readOnly=true)
    public ListPatternStopsResponse listStops(ListPatternStopsRequest request) {
        UUID id=uuid(request.getVersionId(),true);
        if(!exists(id)) throw new java.util.NoSuchElementException("Версия не найдена");
        int size=limit(request.getPage());long skip=offset(request.getPage(),size);
        long count=jdbc.queryForObject("SELECT count(*) FROM stop_occurrence WHERE pattern_version_id=?",
            Long.class,id);
        var items=jdbc.query(STOP_FIELDS+STOPS+" ORDER BY o.sequence_number,o.id LIMIT ? OFFSET ?",
            (rs,i)->stop(rs),id,size,skip);
        return ListPatternStopsResponse.newBuilder().addAllStops(items).setTotalCount(count).build();
    }

    /** Неизменяемый структурный снимок; DRAFT передаётся с явным статусом. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public GetRoutePatternVersionResponse getVersion(GetRoutePatternVersionRequest request) {
        UUID id=uuid(request.getVersionId(),true);
        var versions=jdbc.query("SELECT v.id,v.pattern_id,d.route_id,p.direction_id,v.legal_entity_id,"+
                "v.version_number,v.status,v.content_fingerprint,a.sale_status"+
                " FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id"+
                " JOIN route_direction d ON d.id=p.direction_id"+
                " LEFT JOIN transport_version_activation a ON a.pattern_version_id=v.id WHERE v.id=?",
            (rs,i)->version(rs),id);
        if(versions.isEmpty()) throw new java.util.NoSuchElementException("Версия не найдена");
        var stops=jdbc.query(STOP_FIELDS+STOPS+" ORDER BY o.sequence_number,o.id",
            (rs,i)->stop(rs),id);
        var sections=jdbc.query("SELECT id,pattern_version_id,start_occurrence_id,end_occurrence_id,sequence_number"+
                " FROM fare_section WHERE pattern_version_id=? ORDER BY sequence_number,id",
            (rs,i)->FareSection.newBuilder().setSectionId(((UUID)rs.getObject(1)).toString())
                .setVersionId(((UUID)rs.getObject(2)).toString())
                .setStartOccurrenceId(((UUID)rs.getObject(3)).toString())
                .setEndOccurrenceId(((UUID)rs.getObject(4)).toString())
                .setSequenceNumber(rs.getInt(5)).build(),id);
        return GetRoutePatternVersionResponse.newBuilder().setVersion(versions.get(0))
            .addAllStops(stops).addAllFareSections(sections).build();
    }
    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM route_pattern_version WHERE id=?)",Boolean.class,id));
    }
}
