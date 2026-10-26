package com.qropass.transport.catalog;

import com.qropass.transport.catalog.security.CatalogDelegationAuthorizer.Scope;
import com.qropass.transport.grpc.v1.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Защищённое SQL-чтение Transport Catalog.
 * Core-approved legal scope и DRAFT/PUBLISHED применяются в SQL до COUNT и LIMIT.
 * operator_assignment не участвует в административной авторизации.
 */
@Service
public class TransportScopedCatalogQueryService {
    private final JdbcTemplate jdbc;

    /** Использует только собственную БД Transport Service. */
    public TransportScopedCatalogQueryService(JdbcTemplate jdbc) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
    }

    /** UUID версии разрешено извлекать только для определения целевого юридического лица. */
    public record VersionIdentity(UUID legalEntityId,boolean published) {}

    /** Не выдаёт никаких данных версии клиенту до авторизации. */
    @Transactional(readOnly=true)
    public VersionIdentity requireVersionIdentity(UUID id) {
        List<VersionIdentity> rows=jdbc.query(
            "SELECT legal_entity_id,status FROM route_pattern_version WHERE id=?",
            (rs,n)->new VersionIdentity((UUID)rs.getObject(1),"PUBLISHED".equals(rs.getString(2))),id);
        if(rows.isEmpty()) throw new java.util.NoSuchElementException("Версия не найдена");
        return rows.get(0);
    }

    private record Filter(String placeholders,Object[] args,String visibility) {}
    private static Filter filter(Scope scope) {
        if(scope==null || scope.organizations().isEmpty() || !scope.published() && !scope.drafts())
            throw new SecurityException("Core не предоставил область просмотра");
        if(scope.organizations().size()>64)
            throw new SecurityException("Превышен предел организаций");
        List<UUID> ordered=scope.organizations().stream().sorted().toList();
        String placeholders=String.join(",",java.util.Collections.nCopies(ordered.size(),"?"));
        String visibility=scope.published() && scope.drafts()
            ? "('PUBLISHED','DRAFT')" : scope.published()?"('PUBLISHED')":"('DRAFT')";
        return new Filter(placeholders,ordered.toArray(),visibility);
    }
    private static List<Object> args(Object... elements) {
        return new ArrayList<>(java.util.Arrays.asList(elements));
    }
    private static String value(String input) { return input==null?"":input; }
    private static UUID uuid(String input,boolean mandatory) {
        if(input==null || input.isBlank()) {
            if(mandatory) throw new IllegalArgumentException("Отсутствует обязательный UUID");
            return null;
        }
        try { return UUID.fromString(input); }
        catch(IllegalArgumentException ex) { throw new IllegalArgumentException("Некорректный UUID",ex); }
    }
    private static int size(PageRequest page) {
        if(page.getPageSize()<0 || page.getPageSize()>100)
            throw new IllegalArgumentException("Недопустимый размер страницы");
        return page.getPageSize()==0?20:page.getPageSize();
    }
    private static long offset(PageRequest page,int size) {
        if(page.getPageNumber()<0) throw new IllegalArgumentException("Недопустимый номер страницы");
        return Math.multiplyExact((long)page.getPageNumber(),size);
    }
    private static String qualifiedVersionScope(String alias,Filter filter) {
        return alias+".legal_entity_id IN ("+filter.placeholders()+") AND "+alias+
            ".status IN "+filter.visibility();
    }

    /** Возвращает только маршруты с разрешёнными версиями; направления тоже отфильтрованы. */
    @Transactional(readOnly=true)
    public ListRoutesResponse listRoutes(ListRoutesRequest request,Scope scope) {
        Filter f=filter(scope);
        UUID requested=uuid(request.getLegalEntityId(),false);
        if(requested!=null && !scope.organizations().contains(requested))
            return ListRoutesResponse.getDefaultInstance();
        int n=size(request.getPage());long skip=offset(request.getPage(),n);
        String allowed=qualifiedVersionScope("v",f)+(requested==null?"":" AND v.legal_entity_id=?");
        String base=" FROM route r WHERE EXISTS("+
          "SELECT 1 FROM route_direction d JOIN route_pattern p ON p.direction_id=d.id"+
          " JOIN route_pattern_version v ON v.pattern_id=p.id WHERE d.route_id=r.id AND "+allowed+")";
        List<Object> params=args(f.args());
        if(requested!=null) params.add(requested);
        long count=jdbc.queryForObject("SELECT count(*)"+base,Long.class,params.toArray());
        params.add(n);params.add(skip);
        var rows=jdbc.query("SELECT r.id,r.route_number,r.route_name"+base+
          " ORDER BY r.route_number NULLS LAST,r.id LIMIT ? OFFSET ?",
          (rs,k)->{
            UUID routeId=(UUID)rs.getObject(1);
            Route.Builder builder=Route.newBuilder().setRouteId(routeId.toString())
               .setNumber(value(rs.getString(2))).setName(value(rs.getString(3)));
            List<Object> directionArgs=args(routeId);
            directionArgs.addAll(java.util.Arrays.asList(f.args()));
            if(requested!=null) directionArgs.add(requested);
            jdbc.query("SELECT d.id,d.direction_code,d.display_name FROM route_direction d WHERE d.route_id=?"+
              " AND EXISTS(SELECT 1 FROM route_pattern p JOIN route_pattern_version v"+
              " ON v.pattern_id=p.id WHERE p.direction_id=d.id AND "+allowed+")"+
              " ORDER BY d.direction_code NULLS LAST,d.id",
              (org.springframework.jdbc.core.RowCallbackHandler) dr->builder.addDirections(RouteDirection.newBuilder()
                 .setDirectionId(((UUID)dr.getObject(1)).toString())
                 .setRouteId(routeId.toString()).setCode(value(dr.getString(2)))
                 .setDisplayName(value(dr.getString(3))).build()),directionArgs.toArray());
            return builder.build();
          },params.toArray());
        return ListRoutesResponse.newBuilder().addAllRoutes(rows).setTotalCount(count).build();
    }

    /** Не раскрывает варианты, существующие исключительно в чужих или запрещённых DRAFT. */
    @Transactional(readOnly=true)
    public ListRoutePatternsResponse listPatterns(ListRoutePatternsRequest request,Scope scope) {
        Filter f=filter(scope);
        UUID route=uuid(request.getRouteId(),false),direction=uuid(request.getDirectionId(),false),
            legal=uuid(request.getLegalEntityId(),false);
        if(legal!=null && !scope.organizations().contains(legal))
            return ListRoutePatternsResponse.getDefaultInstance();
        int n=size(request.getPage());long skip=offset(request.getPage(),n);
        String base=" FROM route_pattern p JOIN route_direction d ON d.id=p.direction_id"+
          " WHERE (?::uuid IS NULL OR d.route_id=?::uuid)"+
          " AND (?::uuid IS NULL OR p.direction_id=?::uuid)"+
          " AND EXISTS(SELECT 1 FROM route_pattern_version v WHERE v.pattern_id=p.id AND "+
          qualifiedVersionScope("v",f)+(legal==null?"": " AND v.legal_entity_id=?")+")";
        List<Object> values=args(route,route,direction,direction);
        values.addAll(java.util.Arrays.asList(f.args()));
        if(legal!=null) values.add(legal);
        long count=jdbc.queryForObject("SELECT count(*)"+base,Long.class,values.toArray());
        values.add(n);values.add(skip);
        var rows=jdbc.query("SELECT p.id,d.route_id,p.direction_id,p.pattern_code,p.display_name"+
          base+" ORDER BY d.route_id,p.direction_id,p.pattern_code NULLS LAST,p.id LIMIT ? OFFSET ?",
          (rs,i)->RoutePattern.newBuilder()
            .setPatternId(((UUID)rs.getObject(1)).toString())
            .setRouteId(((UUID)rs.getObject(2)).toString())
            .setDirectionId(((UUID)rs.getObject(3)).toString())
            .setCode(value(rs.getString(4))).setDisplayName(value(rs.getString(5))).build(),
          values.toArray());
        return ListRoutePatternsResponse.newBuilder().addAllPatterns(rows).setTotalCount(count).build();
    }

    /** Core policy применяется до количества и пагинации версий. */
    @Transactional(readOnly=true)
    public ListRoutePatternVersionsResponse listVersions(ListRoutePatternVersionsRequest request,Scope scope) {
        Filter f=filter(scope);
        UUID pattern=uuid(request.getPatternId(),false),legal=uuid(request.getLegalEntityId(),false);
        if(legal!=null && !scope.organizations().contains(legal))
            return ListRoutePatternVersionsResponse.getDefaultInstance();
        String status=request.getPublicationStatus();
        if(!status.isEmpty() && !status.equals("DRAFT") && !status.equals("PUBLISHED"))
            throw new IllegalArgumentException("Неверный статус публикации");
        if(status.equals("DRAFT") && !scope.drafts() || status.equals("PUBLISHED") && !scope.published())
            return ListRoutePatternVersionsResponse.getDefaultInstance();
        int n=size(request.getPage());long skip=offset(request.getPage(),n);
        String base=" FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id"+
            " JOIN route_direction d ON d.id=p.direction_id"+
            " LEFT JOIN transport_version_activation a ON a.pattern_version_id=v.id"+
            " WHERE "+qualifiedVersionScope("v",f)+
            " AND (?::uuid IS NULL OR v.pattern_id=?::uuid)"+
            " AND (?::uuid IS NULL OR v.legal_entity_id=?::uuid)"+
            " AND (?='' OR v.status=?)";
        List<Object> values=new ArrayList<>(java.util.Arrays.asList(f.args()));
        values.addAll(args(pattern,pattern,legal,legal,status,status));
        long count=jdbc.queryForObject("SELECT count(*)"+base,Long.class,values.toArray());
        values.add(n);values.add(skip);
        var rows=jdbc.query("SELECT v.id,v.pattern_id,d.route_id,p.direction_id,v.legal_entity_id,"+
            "v.version_number,v.status,v.content_fingerprint,a.sale_status"+base+
            " ORDER BY v.pattern_id,v.version_number,v.id LIMIT ? OFFSET ?",
            (rs,i)->version(rs),values.toArray());
        return ListRoutePatternVersionsResponse.newBuilder().addAllVersions(rows).setTotalCount(count).build();
    }
    private static PatternVersion version(ResultSet rs)throws SQLException {
        return PatternVersion.newBuilder().setVersionId(((UUID)rs.getObject(1)).toString())
            .setPatternId(((UUID)rs.getObject(2)).toString())
            .setRouteId(((UUID)rs.getObject(3)).toString())
            .setDirectionId(((UUID)rs.getObject(4)).toString())
            .setLegalEntityId(((UUID)rs.getObject(5)).toString())
            .setVersionNumber(rs.getInt(6)).setPublicationStatus(rs.getString(7))
            .setContentFingerprint(value(rs.getString(8)))
            .setSaleStatus(value(rs.getString(9)).isBlank()?"NOT_FOR_SALE":rs.getString(9)).build();
    }
    private static StopOccurrence stop(ResultSet rs)throws SQLException {
        StopOccurrence.Builder b=StopOccurrence.newBuilder()
            .setOccurrenceId(((UUID)rs.getObject(1)).toString())
            .setVersionId(((UUID)rs.getObject(2)).toString())
            .setStopId(((UUID)rs.getObject(3)).toString())
            .setSequenceNumber(rs.getInt(4)).setStopName(value(rs.getString(5)))
            .setPickupAllowed(rs.getBoolean(6)).setDropoffAllowed(rs.getBoolean(7));
        long distance=rs.getLong(8);
        if(!rs.wasNull()) b.setConfirmedDistanceMeters(distance);
        return b.build();
    }
    private static final String STOP_FIELDS="SELECT o.id,o.pattern_version_id,o.stop_id,o.sequence_number,"+
       "s.canonical_name,o.pickup_allowed,o.dropoff_allowed,o.distance_meters";
    private static final String STOP_FROM=" FROM stop_occurrence o JOIN stop s ON s.id=o.stop_id"+
       " JOIN route_pattern_version v ON v.id=o.pattern_version_id";

    /** Проверяет организацию и класс версии в самой SQL выборке. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ListPatternStopsResponse listStops(ListPatternStopsRequest request,Scope scope) {
        UUID id=uuid(request.getVersionId(),true);
        Filter f=filter(scope);
        int n=size(request.getPage());long skip=offset(request.getPage(),n);
        String where=" WHERE o.pattern_version_id=? AND "+qualifiedVersionScope("v",f);
        List<Object> values=args(id);
        values.addAll(java.util.Arrays.asList(f.args()));
        long count=jdbc.queryForObject("SELECT count(*)"+STOP_FROM+where,Long.class,values.toArray());
        values.add(n);values.add(skip);
        var rows=jdbc.query(STOP_FIELDS+STOP_FROM+where+
          " ORDER BY o.sequence_number,o.id LIMIT ? OFFSET ?",
          (rs,i)->stop(rs),values.toArray());
        return ListPatternStopsResponse.newBuilder().addAllStops(rows).setTotalCount(count).build();
    }

    /** Один повторяемый SQL-снимок: статус/область до остановок и FareSection. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public GetRoutePatternVersionResponse getVersion(GetRoutePatternVersionRequest request,Scope scope) {
        UUID id=uuid(request.getVersionId(),true);
        Filter f=filter(scope);
        String base=" FROM route_pattern_version v JOIN route_pattern p ON p.id=v.pattern_id"+
          " JOIN route_direction d ON d.id=p.direction_id"+
          " LEFT JOIN transport_version_activation a ON a.pattern_version_id=v.id"+
          " WHERE v.id=? AND "+qualifiedVersionScope("v",f);
        List<Object> versionArgs=args(id);
        versionArgs.addAll(java.util.Arrays.asList(f.args()));
        var versions=jdbc.query("SELECT v.id,v.pattern_id,d.route_id,p.direction_id,v.legal_entity_id,"+
          "v.version_number,v.status,v.content_fingerprint,a.sale_status"+base,
          (rs,i)->version(rs),versionArgs.toArray());
        if(versions.isEmpty()) throw new java.util.NoSuchElementException("Разрешённая версия не найдена");
        var stops=jdbc.query(STOP_FIELDS+STOP_FROM+" WHERE o.pattern_version_id=?"+
          " ORDER BY o.sequence_number,o.id",(rs,i)->stop(rs),id);
        var sections=jdbc.query("SELECT id,pattern_version_id,start_occurrence_id,end_occurrence_id,sequence_number"+
          " FROM fare_section WHERE pattern_version_id=? ORDER BY sequence_number,id",
          (rs,i)->FareSection.newBuilder()
              .setSectionId(((UUID)rs.getObject(1)).toString())
              .setVersionId(((UUID)rs.getObject(2)).toString())
              .setStartOccurrenceId(((UUID)rs.getObject(3)).toString())
              .setEndOccurrenceId(((UUID)rs.getObject(4)).toString())
              .setSequenceNumber(rs.getInt(5)).build(),id);
        return GetRoutePatternVersionResponse.newBuilder()
            .setVersion(versions.get(0)).addAllStops(stops).addAllFareSections(sections).build();
    }
}
