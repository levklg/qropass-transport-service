package com.qropass.transport;

import com.google.protobuf.UnknownFieldSet;
import com.qropass.grpc.panel.security.v1.CatalogOperation;
import com.qropass.transport.catalog.CatalogRequestCanonicalizer;
import com.qropass.transport.grpc.v1.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Независимые Transport-векторы CEX-REQUEST-2 и негативные проверки дескрипторов. */
class CatalogRequestCanonicalizerTest {
    private static final String A="11111111-1111-4111-8111-111111111111";
    private static final String B="22222222-2222-4222-8222-222222222222";
    private static final String C="33333333-3333-4333-8333-333333333333";
    private static final String D="44444444-4444-4444-8444-444444444444";
    private static final String E="55555555-5555-4555-8555-555555555555";
    private static String digest(com.google.protobuf.Message m,CatalogOperation op) {
        return HexFormat.of().formatHex(CatalogRequestCanonicalizer.digest(m,op));
    }
    @Test void fiveGatewayReferenceDigests() {
        assertEquals("b14e96a9c5dcb28c90c86186156717d09d45714dbbf219026c85bee18317d9ac",
            digest(ListRoutesRequest.newBuilder().setLegalEntityId(A)
                .setPage(PageRequest.newBuilder().setPageSize(50)).build(),CatalogOperation.LIST_ROUTES));
        assertEquals("09383451498749f4a8f589794452516d07fcef3fdb99a4d3da31b9a71a405689",
            digest(ListRoutePatternsRequest.newBuilder().setLegalEntityId(A).setRouteId(B).setDirectionId(C)
                .setPage(PageRequest.newBuilder().setPageSize(20).setPageNumber(1)).build(),
                CatalogOperation.LIST_ROUTE_PATTERNS));
        assertEquals("e86a74ad1e4acbefc4df9f96c1f7b2dde30cad812c7654c25bae1f22c3e0d590",
            digest(ListRoutePatternVersionsRequest.newBuilder().setPatternId(D).setLegalEntityId(A)
                .setPublicationStatus("PUBLISHED").build(),CatalogOperation.LIST_ROUTE_PATTERN_VERSIONS));
        assertEquals("ea5f4069c9d8a6f65e882d2cd24027edde48e46bbad179ceb0e4062dc4095670",
            digest(GetRoutePatternVersionRequest.newBuilder().setVersionId(E).build(),
                CatalogOperation.GET_ROUTE_PATTERN_VERSION));
        assertEquals("4e8af107bae5935b80ce4c711a5b40e10e080c1ccaef3249e8e3d0cf59e9333c",
            digest(ListPatternStopsRequest.newBuilder().setVersionId(E)
                .setPage(PageRequest.newBuilder().setPageSize(100).setPageNumber(2)).build(),
                CatalogOperation.LIST_PATTERN_STOPS));
    }
    @Test void omittedAndEmptyScalarIdenticalButPagePresenceDifferent() {
        assertEquals(digest(ListRoutesRequest.getDefaultInstance(),CatalogOperation.LIST_ROUTES),
            digest(ListRoutesRequest.newBuilder().setLegalEntityId("").build(),CatalogOperation.LIST_ROUTES));
        assertNotEquals(digest(ListRoutesRequest.getDefaultInstance(),CatalogOperation.LIST_ROUTES),
            digest(ListRoutesRequest.newBuilder().setPage(PageRequest.getDefaultInstance()).build(),
                CatalogOperation.LIST_ROUTES));
    }
    @Test void strictUuidRequiredIdentifiersAndOperation() {
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutesRequest.newBuilder().setLegalEntityId(A.toUpperCase()).build(),CatalogOperation.LIST_ROUTES));
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutesRequest.newBuilder().setLegalEntityId("1111").build(),CatalogOperation.LIST_ROUTES));
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutePatternVersionsRequest.getDefaultInstance(),CatalogOperation.LIST_ROUTE_PATTERN_VERSIONS));
        assertThrows(IllegalArgumentException.class,()->digest(
            GetRoutePatternVersionRequest.getDefaultInstance(),CatalogOperation.GET_ROUTE_PATTERN_VERSION));
        assertThrows(IllegalArgumentException.class,()->digest(
            ListPatternStopsRequest.getDefaultInstance(),CatalogOperation.LIST_PATTERN_STOPS));
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutesRequest.getDefaultInstance(),CatalogOperation.LIST_PATTERN_STOPS));
    }
    @Test void unknownRootAndNestedProtobufAreRejected() {
        UnknownFieldSet unknown=UnknownFieldSet.newBuilder()
            .addField(97,UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutesRequest.newBuilder().setUnknownFields(unknown).build(),CatalogOperation.LIST_ROUTES));
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutesRequest.newBuilder().setPage(PageRequest.newBuilder().setUnknownFields(unknown)).build(),
            CatalogOperation.LIST_ROUTES));
    }
    @Test void paginationAndStatusAreStrict() {
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutesRequest.newBuilder().setPage(PageRequest.newBuilder().setPageSize(101)).build(),
            CatalogOperation.LIST_ROUTES));
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutesRequest.newBuilder().setPage(PageRequest.newBuilder().setPageNumber(-1)).build(),
            CatalogOperation.LIST_ROUTES));
        assertThrows(IllegalArgumentException.class,()->digest(
            ListRoutePatternVersionsRequest.newBuilder().setPatternId(D)
                .setPublicationStatus("draft").build(),CatalogOperation.LIST_ROUTE_PATTERN_VERSIONS));
        assertNotEquals(digest(ListRoutesRequest.newBuilder().setLegalEntityId(A).build(),
                CatalogOperation.LIST_ROUTES),
            digest(ListRoutesRequest.newBuilder().setLegalEntityId(B).build(),
                CatalogOperation.LIST_ROUTES));
    }
}
