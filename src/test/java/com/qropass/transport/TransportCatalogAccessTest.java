package com.qropass.transport;

import com.qropass.transport.catalog.*;
import com.qropass.transport.grpc.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Доступ запрещён до подключения реального доверенного делегирования. */
class TransportCatalogAccessTest {
    private final TransportCatalogGrpcService catalog=new TransportCatalogGrpcService(new CatalogAccessPolicy(),org.mockito.Mockito.mock(TransportCatalogQueryService.class));
    private <T> Status.Code code(java.util.function.Consumer<StreamObserver<T>> call) {
        AtomicReference<Throwable> error=new AtomicReference<>();
        call.accept(new StreamObserver<T>() {
            public void onNext(T value) { fail("Неавторизованные данные выдавать нельзя"); }
            public void onError(Throwable cause) { error.set(cause); }
            public void onCompleted() { fail("Ожидался отказ"); }
        });
        assertInstanceOf(StatusRuntimeException.class,error.get());
        return Status.fromThrowable(error.get()).getCode();
    }
    /** Каждый RPC fail-closed даже при произвольном legalEntityId. */
    @Test void allFiveRpcRequireIdentity() {
        assertEquals(Status.Code.UNAUTHENTICATED,code(
            (StreamObserver<ListRoutesResponse> o)->catalog.listRoutes(ListRoutesRequest.newBuilder().build(),o)));
        assertEquals(Status.Code.UNAUTHENTICATED,code(
            (StreamObserver<ListRoutePatternsResponse> o)->catalog.listRoutePatterns(ListRoutePatternsRequest.newBuilder().build(),o)));
        assertEquals(Status.Code.UNAUTHENTICATED,code(
            (StreamObserver<ListRoutePatternVersionsResponse> o)->catalog.listRoutePatternVersions(ListRoutePatternVersionsRequest.newBuilder().setPatternId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").build(),o)));
        assertEquals(Status.Code.UNAUTHENTICATED,code(
            (StreamObserver<GetRoutePatternVersionResponse> o)->catalog.getRoutePatternVersion(GetRoutePatternVersionRequest.newBuilder().setVersionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").build(),o)));
        assertEquals(Status.Code.UNAUTHENTICATED,code(
            (StreamObserver<ListPatternStopsResponse> o)->catalog.listPatternStops(ListPatternStopsRequest.newBuilder().setVersionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").build(),o)));
    }
    /** Некорректный UUID не позволяет обойти проверку. */
    @Test void invalidLegalEntityIdRejected() {
        assertEquals(Status.Code.INVALID_ARGUMENT,code(
            (StreamObserver<ListRoutesResponse> o)->catalog.listRoutes(
                ListRoutesRequest.newBuilder().setLegalEntityId("not-uuid").build(),o)));
    }
    /** Контракт содержит только чтение, без mutate-RPC. */
    @Test void noMutationsInGrpcService() {
        var methods=TransportCatalogServiceGrpc.getServiceDescriptor().getMethods();
        assertEquals(5,methods.size());
        assertTrue(methods.stream().allMatch(m->m.getType()==io.grpc.MethodDescriptor.MethodType.UNARY));
        assertTrue(methods.stream().noneMatch(m->m.getFullMethodName().matches(
            "(?i).*(create|update|delete|publish|enableSales).*")));
    }
}
