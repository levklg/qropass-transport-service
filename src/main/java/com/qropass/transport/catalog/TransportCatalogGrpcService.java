package com.qropass.transport.catalog;

import com.qropass.transport.grpc.v1.*;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * gRPC каталог версии 1. Серверная авторизация обязательна и закрыта по умолчанию.
 * Не регистрируется в сетевом gRPC-сервере на этапе T1.4.
 */
@Service
public class TransportCatalogGrpcService extends TransportCatalogServiceGrpc.TransportCatalogServiceImplBase {
    private final CatalogAccessPolicy access;

    /** Создаёт реализацию контракта с обязательным security-barrier. */
    public TransportCatalogGrpcService(CatalogAccessPolicy access) { this.access=access; }

    /** Публичная выдача маршрутов запрещена без подтверждённой сервисной идентичности. */
    @Override public void listRoutes(ListRoutesRequest request,StreamObserver<ListRoutesResponse> response) {
        deny(request.getLegalEntityId(),response);
    }
    /** Публичная выдача вариантов движения запрещена без сервисного делегирования. */
    @Override public void listRoutePatterns(ListRoutePatternsRequest request,StreamObserver<ListRoutePatternsResponse> response) {
        deny(request.getLegalEntityId(),response);
    }
    /** Состояния DRAFT требуют административного делегирования. */
    @Override public void listRoutePatternVersions(ListRoutePatternVersionsRequest request,StreamObserver<ListRoutePatternVersionsResponse> response) {
        deny(request.getLegalEntityId(),response);
    }
    /** Снимки версий недоступны без серверной авторизации. */
    @Override public void getRoutePatternVersion(GetRoutePatternVersionRequest request,StreamObserver<GetRoutePatternVersionResponse> response) {
        deny("",response);
    }
    /** Остановочные данные недоступны без серверной авторизации. */
    @Override public void listPatternStops(ListPatternStopsRequest request,StreamObserver<ListPatternStopsResponse> response) {
        deny("",response);
    }
    private <T> void deny(String legalEntityId,StreamObserver<T> response) {
        UUID requested=null;
        try {
            if(legalEntityId!=null && !legalEntityId.isBlank()) requested=UUID.fromString(legalEntityId);
        } catch(IllegalArgumentException ex) {
            response.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription("Некорректный UUID организации").asRuntimeException());
            return;
        }
        try { access.requireAuthorized(requested); }
        catch(io.grpc.StatusRuntimeException ex) { response.onError(ex); return; }
        response.onError(io.grpc.Status.PERMISSION_DENIED.withDescription("Доступ запрещён").asRuntimeException());
    }
}
