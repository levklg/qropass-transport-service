package com.qropass.transport;

import com.qropass.grpc.panel.security.v1.CatalogOperation;
import com.qropass.transport.catalog.*;
import com.qropass.transport.catalog.security.CatalogDelegationAuthorizer.Scope;
import com.qropass.transport.grpc.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Изолированный gRPC adapter: поиск версии допускается только после Core ALLOW. */
class TransportVersionDisclosureTest {
    private static final UUID A=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID B=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID V=UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final Scope OWN=new Scope(Set.of(A),true,false);
    private record Outcome(Status.Code code,String description,boolean completed) {}
    private static <T> Outcome invoke(java.util.function.Consumer<StreamObserver<T>> method) {
        AtomicReference<Throwable> error=new AtomicReference<>();
        AtomicReference<Boolean> completed=new AtomicReference<>(false);
        method.accept(new StreamObserver<T>() {
            public void onNext(T value) {}
            public void onError(Throwable cause) { error.set(cause); }
            public void onCompleted() { completed.set(true); }
        });
        if(error.get()==null)return new Outcome(Status.Code.OK,"",completed.get());
        Status s=Status.fromThrowable(error.get());
        return new Outcome(s.getCode(),s.getDescription(),completed.get());
    }
    @Test void twoMethodsHideForeignHiddenAndMissingVersion() {
        for(boolean stops:new boolean[]{false,true}) {
            CatalogAccessPolicy policy=mock(CatalogAccessPolicy.class);
            TransportScopedCatalogQueryService queries=mock(TransportScopedCatalogQueryService.class);
            TransportCatalogGrpcService service=new TransportCatalogGrpcService(policy,queries);
            CatalogOperation op=stops?CatalogOperation.LIST_PATTERN_STOPS:CatalogOperation.GET_ROUTE_PATTERN_VERSION;
            when(policy.requireAuthorized(op,null)).thenReturn(OWN);
            when(queries.requireVisibleVersionIdentity(V,OWN)).thenThrow(new NoSuchElementException("Версия не найдена"));
            Outcome missing=call(service,stops);
            assertEquals(Status.Code.NOT_FOUND,missing.code());
            assertEquals("Объект не найден",missing.description());
            verify(policy).requireAuthorized(op,null);
            verify(queries).requireVisibleVersionIdentity(V,OWN);
            verify(policy,never()).requireAuthorized(op,A);
            verify(queries,never()).getVersion(any(),any());
            verify(queries,never()).listStops(any(),any());
            reset(policy,queries);
            when(policy.requireAuthorized(op,null)).thenReturn(OWN);
            when(queries.requireVisibleVersionIdentity(V,OWN)).thenThrow(new NoSuchElementException("Версия не найдена"));
            Outcome foreign=call(service,stops);
            assertEquals(missing,foreign);
            Outcome hidden=call(service,stops);
            assertEquals(missing,hidden);
        }
    }
    @Test void preliminaryCoreDenyAndUnavailableNeverReadVersion() {
        for(boolean stops:new boolean[]{false,true}) {
            for(Status.Code code:new Status.Code[]{Status.Code.PERMISSION_DENIED,Status.Code.UNAVAILABLE}) {
                CatalogAccessPolicy policy=mock(CatalogAccessPolicy.class);
                TransportScopedCatalogQueryService queries=mock(TransportScopedCatalogQueryService.class);
                TransportCatalogGrpcService service=new TransportCatalogGrpcService(policy,queries);
                CatalogOperation op=stops?CatalogOperation.LIST_PATTERN_STOPS:CatalogOperation.GET_ROUTE_PATTERN_VERSION;
                when(policy.requireAuthorized(op,null)).thenThrow(Status.fromCode(code).asRuntimeException());
                assertEquals(code,call(service,stops).code());
                verifyNoInteractions(queries);
            }
        }
    }
    @Test void targetedCoreDenialPreventsResultForBothRpc() {
        for(boolean stops:new boolean[]{false,true}) {
            CatalogAccessPolicy policy=mock(CatalogAccessPolicy.class);
            TransportScopedCatalogQueryService queries=mock(TransportScopedCatalogQueryService.class);
            TransportCatalogGrpcService service=new TransportCatalogGrpcService(policy,queries);
            CatalogOperation op=stops?CatalogOperation.LIST_PATTERN_STOPS:CatalogOperation.GET_ROUTE_PATTERN_VERSION;
            when(policy.requireAuthorized(op,null)).thenReturn(OWN);
            when(queries.requireVisibleVersionIdentity(V,OWN))
                .thenReturn(new TransportScopedCatalogQueryService.VersionIdentity(A,true));
            when(policy.requireAuthorized(op,A)).thenThrow(Status.PERMISSION_DENIED.asRuntimeException());
            assertEquals(Status.Code.PERMISSION_DENIED,call(service,stops).code());
            verify(policy).requireAuthorized(op,null);
            verify(policy).requireAuthorized(op,A);
            verify(queries,never()).getVersion(any(),any());
            verify(queries,never()).listStops(any(),any());
        }
    }
    @Test void invalidUuidNeverReachesCoreOrSql() {
        CatalogAccessPolicy policy=mock(CatalogAccessPolicy.class);
        TransportScopedCatalogQueryService queries=mock(TransportScopedCatalogQueryService.class);
        TransportCatalogGrpcService service=new TransportCatalogGrpcService(policy,queries);
        var bad=GetRoutePatternVersionRequest.newBuilder().setVersionId(V.toString().toUpperCase()).build();
        Outcome result=TransportVersionDisclosureTest.<GetRoutePatternVersionResponse>invoke(o->service.getRoutePatternVersion(bad,o));
        assertEquals(Status.Code.INVALID_ARGUMENT,result.code());
        verifyNoInteractions(policy,queries);
    }
    private static Outcome call(TransportCatalogGrpcService service,boolean stops) {
        return stops?TransportVersionDisclosureTest.<ListPatternStopsResponse>invoke(o->service.listPatternStops(
            ListPatternStopsRequest.newBuilder().setVersionId(V.toString()).build(),o))
            :TransportVersionDisclosureTest.<GetRoutePatternVersionResponse>invoke(o->service.getRoutePatternVersion(
                GetRoutePatternVersionRequest.newBuilder().setVersionId(V.toString()).build(),o));
    }
}
