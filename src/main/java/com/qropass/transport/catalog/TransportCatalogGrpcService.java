package com.qropass.transport.catalog;

import com.qropass.transport.grpc.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Только read-only RPC v1. До подключения QRP-SEC-001 защищённый барьер
 * всегда отклоняет вызовы. Сетевой gRPC listener в T1.4 не создаётся.
 */
@Service
public class TransportCatalogGrpcService extends TransportCatalogServiceGrpc.TransportCatalogServiceImplBase {
    private final CatalogAccessPolicy access;
    private final TransportCatalogQueryService queries;

    /** Внедряет запретительную политику и read-only адаптер. */
    public TransportCatalogGrpcService(CatalogAccessPolicy access,TransportCatalogQueryService queries) {
        this.access=access;this.queries=queries;
    }
    /** Реестр маршрутов, включая направления. */
    @Override public void listRoutes(ListRoutesRequest request,StreamObserver<ListRoutesResponse> observer) {
        execute(request.getLegalEntityId(),observer,()->queries.listRoutes(request));
    }
    /** Варианты движения с UUID направления. */
    @Override public void listRoutePatterns(ListRoutePatternsRequest request,StreamObserver<ListRoutePatternsResponse> observer) {
        execute(request.getLegalEntityId(),observer,()->queries.listPatterns(request));
    }
    /** Версии с отдельными статусами публикации и продаж. */
    @Override public void listRoutePatternVersions(ListRoutePatternVersionsRequest request,StreamObserver<ListRoutePatternVersionsResponse> observer) {
        execute(request.getLegalEntityId(),observer,()->queries.listVersions(request));
    }
    /** Подробный структурный снимок версии. */
    @Override public void getRoutePatternVersion(GetRoutePatternVersionRequest request,StreamObserver<GetRoutePatternVersionResponse> observer) {
        execute("",observer,()->queries.getVersion(request));
    }
    /** Остановки с оригинальными идентификаторами вхождений. */
    @Override public void listPatternStops(ListPatternStopsRequest request,StreamObserver<ListPatternStopsResponse> observer) {
        execute("",observer,()->queries.listStops(request));
    }
    private <T> void execute(String organization,StreamObserver<T> observer,Supplier<T> query) {
        try {
            UUID legalId=null;
            if(!organization.isBlank()) legalId=UUID.fromString(organization);
            access.requireAuthorized(legalId);
            // Даже после успешной авторизации будущая политика обязана проверить
            // права на конкретный маршрут/версию и область организации.
            observer.onNext(query.get());
            observer.onCompleted();
        } catch(StatusRuntimeException ex) {
            observer.onError(ex);
        } catch(IllegalArgumentException | ArithmeticException ex) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
        } catch(NoSuchElementException ex) {
            observer.onError(Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
        } catch(RuntimeException ex) {
            observer.onError(Status.INTERNAL.withDescription("Ошибка чтения транспортного каталога").asRuntimeException());
        }
    }
}
