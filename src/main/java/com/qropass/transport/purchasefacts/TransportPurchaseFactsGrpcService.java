package com.qropass.transport.purchasefacts;

import com.qropass.transport.service.CityTransportContextService;
import com.qropass.transport.grpc.purchasefacts.v1.*;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.util.UUID;

/** Отдельный внутренний gRPC adapter для проверенных транспортных фактов CITY-01. */
public final class TransportPurchaseFactsGrpcService
    extends TransportPurchaseFactsServiceGrpc.TransportPurchaseFactsServiceImplBase {
    private final CityTransportContextService facts;
    private final Clock clock;
    /** Компоненты передаются только доверенной тестовой сборкой либо утверждённым bootstrap. */
    public TransportPurchaseFactsGrpcService(CityTransportContextService facts,Clock clock) {
        this.facts=java.util.Objects.requireNonNull(facts);
        this.clock=java.util.Objects.requireNonNull(clock);
    }
    /** Проверяет ввод и получает факты без выдачи прав продажи. */
    @Override public void resolveCityTransportContext(ResolveCityTransportContextRequest request,
            StreamObserver<ResolveCityTransportContextResponse> observer) {
        try {
            if(!request.getUnknownFields().asMap().isEmpty())
                throw new IllegalArgumentException("Неизвестные поля запроса");
            String inn=request.getLegalEntityInn();
            if(!inn.matches("[0-9]{10}|[0-9]{12}"))
                throw new IllegalArgumentException("Некорректный ИНН");
            UUID route=uuid(request.getRouteId()),version=uuid(request.getPatternVersionId());
            var factsResult=facts.resolveByInn(inn,route,version,clock.instant());
            var response=ResolveCityTransportContextResponse.newBuilder()
                .setLegalEntityId(factsResult.legalEntityId().toString())
                .setLegalEntityInn(factsResult.inn())
                .setRouteId(factsResult.routeId().toString())
                .setDirectionId(factsResult.directionId().toString())
                .setPatternId(factsResult.patternId().toString())
                .setPatternVersionId(factsResult.versionId().toString())
                .setPublicationStatus(factsResult.publicationStatus())
                .setEffectiveFrom(factsResult.effectiveFrom().toString())
                .setEffectiveUntil(factsResult.effectiveUntil()==null?"":factsResult.effectiveUntil().toString())
                .setContentFingerprint(factsResult.contentFingerprint())
                .setCityCategoryConfirmed(false).setSalePermitted(false).build();
            observer.onNext(response);
            observer.onCompleted();
        } catch(IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Некорректный запрос").asRuntimeException());
        } catch(SecurityException e) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("Транспортный контекст недоступен").asRuntimeException());
        } catch(org.springframework.dao.DataAccessException e) {
            observer.onError(Status.UNAVAILABLE.withDescription("Источник транспортных данных недоступен").asRuntimeException());
        } catch(Exception e) {
            observer.onError(Status.INTERNAL.withDescription("Внутренняя ошибка").asRuntimeException());
        }
    }
    /** Требует канонический lowercase UUID без нормализации. */
    private static UUID uuid(String text) {
        if(!text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Неканонический UUID");
        UUID parsed=UUID.fromString(text);
        if(!parsed.toString().equals(text)) throw new IllegalArgumentException("Неканонический UUID");
        return parsed;
    }
}
