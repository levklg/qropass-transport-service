package com.qropass.transport.catalog;

import com.qropass.grpc.panel.security.v1.CatalogOperation;
import com.qropass.transport.catalog.security.CatalogDelegationAuthorizer.Scope;
import com.qropass.transport.grpc.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Транспортные RPC без сетевого listener. Core Validate Delegation обязателен;
 * результат Core применяется в SQL до пагинации и totalCount.
 */
@Service
public class TransportCatalogGrpcService extends TransportCatalogServiceGrpc.TransportCatalogServiceImplBase {
    private final CatalogAccessPolicy access;
    private final TransportScopedCatalogQueryService queries;

    /** Production bean: без доверенного mTLS/Core policy всегда возвращает отказ. */
    @Autowired
    public TransportCatalogGrpcService(CatalogAccessPolicy access,TransportScopedCatalogQueryService queries) {
        this.access=java.util.Objects.requireNonNull(access);
        this.queries=java.util.Objects.requireNonNull(queries);
    }
    /** Совместимость существующих тестов закрытого каталога T1.4. */
    public TransportCatalogGrpcService(CatalogAccessPolicy access,TransportCatalogQueryService unused) {
        this.access=java.util.Objects.requireNonNull(access);
        this.queries=null;
    }
    @Override public void listRoutes(ListRoutesRequest request,StreamObserver<ListRoutesResponse> observer) {
        execute(observer,()->{
            UUID legal=uuid(request.getLegalEntityId(),false);
            Scope scope=access.requireAuthorized(CatalogOperation.LIST_ROUTES,legal);
            return queries.listRoutes(request,scope);
        });
    }
    @Override public void listRoutePatterns(ListRoutePatternsRequest request,
                                              StreamObserver<ListRoutePatternsResponse> observer) {
        execute(observer,()->{
            UUID legal=uuid(request.getLegalEntityId(),false);
            Scope scope=access.requireAuthorized(CatalogOperation.LIST_ROUTE_PATTERNS,legal);
            return queries.listPatterns(request,scope);
        });
    }
    @Override public void listRoutePatternVersions(ListRoutePatternVersionsRequest request,
                                                     StreamObserver<ListRoutePatternVersionsResponse> observer) {
        execute(observer,()->{
            UUID legal=uuid(request.getLegalEntityId(),false);
            Scope scope=access.requireAuthorized(CatalogOperation.LIST_ROUTE_PATTERN_VERSIONS,legal);
            return queries.listVersions(request,scope);
        });
    }
    @Override public void getRoutePatternVersion(GetRoutePatternVersionRequest request,
                                                   StreamObserver<GetRoutePatternVersionResponse> observer) {
        execute(observer,()->{
            if(queries==null) access.requireAuthorized(null);
            UUID id=uuid(request.getVersionId(),true);
            UUID legal=queries.requireVersionIdentity(id).legalEntityId();
            Scope scope=access.requireAuthorized(CatalogOperation.GET_ROUTE_PATTERN_VERSION,legal);
            return queries.getVersion(request,scope);
        });
    }
    @Override public void listPatternStops(ListPatternStopsRequest request,
                                             StreamObserver<ListPatternStopsResponse> observer) {
        execute(observer,()->{
            UUID id=uuid(request.getVersionId(),true);
            UUID legal=queries.requireVersionIdentity(id).legalEntityId();
            Scope scope=access.requireAuthorized(CatalogOperation.LIST_PATTERN_STOPS,legal);
            return queries.listStops(request,scope);
        });
    }
    private static UUID uuid(String text,boolean mandatory) {
        if(text==null || text.isBlank()) {
            if(mandatory) throw new IllegalArgumentException("Требуется идентификатор версии");
            return null;
        }
        try { return UUID.fromString(text); }
        catch(IllegalArgumentException ex) { throw new IllegalArgumentException("Некорректный UUID",ex); }
    }
    private static <T> void execute(StreamObserver<T> observer,Supplier<T> action) {
        try {
            T result=action.get();
            observer.onNext(result);
            observer.onCompleted();
        } catch(StatusRuntimeException ex) {
            observer.onError(ex);
        } catch(IllegalArgumentException | ArithmeticException ex) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Недопустимые параметры").asRuntimeException());
        } catch(NoSuchElementException ex) {
            observer.onError(Status.NOT_FOUND.withDescription("Объект не найден").asRuntimeException());
        } catch(SecurityException ex) {
            observer.onError(Status.PERMISSION_DENIED.withDescription("Нет полномочий для просмотра").asRuntimeException());
        } catch(RuntimeException ex) {
            observer.onError(Status.INTERNAL.withDescription("Ошибка чтения каталога").asRuntimeException());
        }
    }
}
