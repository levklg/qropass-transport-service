package com.qropass.transport;

import com.qropass.transport.catalog.TransportScopedCatalogQueryService;
import com.qropass.transport.catalog.security.CatalogDelegationAuthorizer.Scope;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Архитектурный барьер: публичное чтение Catalog невозможно без Core scope. */
class TransportVersionLookupArchitectureTest {
    @Test void allPublicScopedQueryMethodsRequireScope() {
        var methods=Arrays.stream(TransportScopedCatalogQueryService.class.getDeclaredMethods())
            .filter(m->Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()).toList();
        assertFalse(methods.isEmpty());
        for(var method:methods)
            assertTrue(Arrays.asList(method.getParameterTypes()).contains(Scope.class),
                "Публичный метод не принимает подтверждённый Scope: "+method.getName());
        assertTrue(methods.stream().noneMatch(m->m.getName().equals("requireVersionIdentity")),
            "Нельзя восстанавливать unscoped requireVersionIdentity");
        assertTrue(methods.stream().anyMatch(m->m.getName().equals("requireVisibleVersionIdentity")));
    }

    @Test void bothVersionRpcAuthorizeBeforeLookupAndTargetValidateBeforeData() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/qropass/transport/catalog/TransportCatalogGrpcService.java"));
        verifySequence(source,"getRoutePatternVersion(","listPatternStops(",
            "GET_ROUTE_PATTERN_VERSION","getVersion(");
        verifySequence(source,"listPatternStops(","private static UUID uuid(",
            "LIST_PATTERN_STOPS","listStops(");
    }

    private static void verifySequence(String source,String start,String end,String operation,String finalQuery) {
        int begin=source.indexOf("void "+start);
        int finish=source.indexOf(end,begin+start.length());
        assertTrue(begin>=0 && finish>begin,"Отсутствует утверждённый Catalog RPC");
        String body=source.substring(begin,finish);
        int canonical=body.indexOf("CatalogRequestCanonicalizer.validate(");
        int preliminary=body.indexOf("access.requireAuthorized(CatalogOperation."+operation+",null)");
        int lookup=body.indexOf("queries.requireVisibleVersionIdentity(");
        int targeted=body.indexOf("access.requireAuthorized(CatalogOperation."+operation+",legal)");
        int query=body.indexOf("queries."+finalQuery);
        assertTrue(canonical>=0 && canonical<preliminary && preliminary<lookup &&
            lookup<targeted && targeted<query,
            "Нарушен порядок CEX → Core ALLOW → scoped lookup → targeted Core VALIDATE → SQL");
        assertFalse(body.contains("queries.requireVersionIdentity("),"Обнаружен unscoped lookup");
    }
}
