package com.qropass.transport;

import com.qropass.transport.catalog.security.*;
import com.qropass.grpc.panel.security.v1.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.security.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Криптография, связность полномочий, fail-closed Core и поле №16. */
class CatalogDelegationSecurityTest {
    private final UUID legal=UUID.fromString("00000000-0000-0000-0000-000000000111");
    private final byte[] gatewayFingerprint=fingerprint(new byte[]{48,3,2,1,5});
    private final GatewayTlsPeer.Verified gateway=trustedPeer(new byte[]{48,3,2,1,5});
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"),ZoneOffset.UTC);
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();

    /** Тестовый источник: mocked TLS session, не production-конструктор Verified. */
    private static GatewayTlsPeer.Verified trustedPeer(byte[] der) {
        try {
            java.security.cert.X509Certificate cert=org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
            org.mockito.Mockito.when(cert.getEncoded()).thenReturn(der);
            javax.net.ssl.SSLSession tls=org.mockito.Mockito.mock(javax.net.ssl.SSLSession.class);
            org.mockito.Mockito.when(tls.getPeerCertificates()).thenReturn(
                new java.security.cert.Certificate[]{cert});
            org.mockito.Mockito.when(tls.getPeerPrincipal()).thenReturn(
                new javax.security.auth.x500.X500Principal("CN=qropass-panel-gateway"));
            @SuppressWarnings("unchecked")
            io.grpc.ServerCall<String,String> call=org.mockito.Mockito.mock(io.grpc.ServerCall.class);
            org.mockito.Mockito.when(call.getAttributes()).thenReturn(io.grpc.Attributes.newBuilder()
                .set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION,tls).build());
            return GatewayTlsPeer.fromCall(call,"CN=qropass-panel-gateway");
        } catch(Exception ex) { throw new AssertionError(ex); }
    }
    private static byte[] fingerprint(byte[] der) {
        try { return MessageDigest.getInstance("SHA-256").digest(der); }
        catch(Exception ex) { throw new AssertionError(ex); }
    }
    @Test void verifiedPeerHasNoPublicConstructors() {
        assertEquals(0,GatewayTlsPeer.Verified.class.getConstructors().length);
    }
    private static KeyPair keys()throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }
    private String token(KeyPair pair,String operation,String audience,String cnf,long expiry)throws Exception {
        long now=clock.instant().getEpochSecond();
        Map<String,Object> header=Map.of("alg","EdDSA","typ","JWT","kid","core-key-1");
        Map<String,Object> claims=new LinkedHashMap<>();
        claims.put("iss","qropass-core");
        claims.put("aud",audience);
        claims.put("sub","42");
        claims.put("sid",UUID.randomUUID().toString());
        claims.put("jti",UUID.randomUUID().toString());
        claims.put("iat",now);
        claims.put("nbf",now);
        claims.put("exp",expiry);
        claims.put("operation",operation);
        claims.put("accessMode","SINGLE_ORGANIZATION");
        claims.put("legalEntityUuids",List.of(legal.toString()));
        claims.put("allowPublished",true);
        claims.put("allowDrafts",false);
        claims.put("authorizationRevision",1);
        claims.put("sessionRevision",1);
        claims.put("policyRevision",1);
        claims.put("cnf",Map.of("x5t#S256",cnf));
        ObjectMapper json=new ObjectMapper();
        String signing=B64.encodeToString(json.writeValueAsBytes(header))+"."+
                       B64.encodeToString(json.writeValueAsBytes(claims));
        Signature signature=Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(signing.getBytes(StandardCharsets.US_ASCII));
        return signing+"."+B64.encodeToString(signature.sign());
    }
    private DelegationTokenVerifier verifier(KeyPair pair) {
        return new DelegationTokenVerifier(kid->"core-key-1".equals(kid)?pair.getPublic():null,clock);
    }
    @Test void validEd25519TokenAndTrustedGatewayBinding()throws Exception {
        KeyPair pair=keys();
        String t=token(pair,"LIST_ROUTES","qropass-transport-catalog",
            B64.encodeToString(gatewayFingerprint),clock.instant().getEpochSecond()+30);
        assertEquals("qropass-core",verifier(pair).verify(t,"LIST_ROUTES",gateway).get("iss"));
    }
    @Test void rejectWrongAudienceOperationFingerprintAndExpiry()throws Exception {
        KeyPair pair=keys();long exp=clock.instant().getEpochSecond()+30;
        assertEquals(Status.Code.PERMISSION_DENIED,Status.fromThrowable(assertThrows(StatusRuntimeException.class,
            ()->verifier(pair).verify(token(pair,"LIST_ROUTES","wrong",B64.encodeToString(gatewayFingerprint),exp),
                "LIST_ROUTES",gateway))).getCode());
        String good=token(pair,"LIST_ROUTES","qropass-transport-catalog",B64.encodeToString(gatewayFingerprint),exp);
        assertThrows(StatusRuntimeException.class,()->verifier(pair).verify(good,"GET_ROUTE_PATTERN_VERSION",gateway));
        String badCnf=token(pair,"LIST_ROUTES","qropass-transport-catalog",B64.encodeToString(new byte[32]),exp);
        GatewayTlsPeer.Verified different=trustedPeer(new byte[]{1,2,3});
        assertThrows(StatusRuntimeException.class,()->verifier(pair).verify(badCnf,"LIST_ROUTES",different));
        String expired=token(pair,"LIST_ROUTES","qropass-transport-catalog",
            B64.encodeToString(gatewayFingerprint),clock.instant().getEpochSecond()-1);
        assertThrows(StatusRuntimeException.class,()->verifier(pair).verify(expired,"LIST_ROUTES",gateway));
    }
    @Test void rejectUnknownKidAndTamperedSignature()throws Exception {
        KeyPair pair=keys();String t=token(pair,"LIST_ROUTES","qropass-transport-catalog",
            B64.encodeToString(gatewayFingerprint),clock.instant().getEpochSecond()+30);
        assertThrows(StatusRuntimeException.class,()->new DelegationTokenVerifier(kid->null,clock)
            .verify(t,"LIST_ROUTES",gateway));
        KeyPair attacker=keys();
        assertThrows(StatusRuntimeException.class,()->verifier(attacker).verify(t,"LIST_ROUTES",gateway));
    }
    @Test void coreValidationUsesOnlyVerifiedGatewayFingerprintInField16() {
        java.util.concurrent.atomic.AtomicReference<AuthorizeRequest> observed=new java.util.concurrent.atomic.AtomicReference<>();
        CoreDelegationValidation client=new CoreDelegationValidation(req->{
            observed.set(req);
            return AuthorizeResponse.newBuilder().setDecision(AuthorizationDecision.ALLOW)
                .setOperation(CatalogOperation.LIST_ROUTES)
                .addAuthorizedLegalEntityUuids(legal.toString()).setAllowPublished(true).build();
        });
        client.validate("signed-token",CatalogOperation.LIST_ROUTES,List.of(legal),true,false,gateway);
        AuthorizeRequest req=observed.get();
        assertEquals(AuthorizationAction.VALIDATE_DELEGATION,req.getAction());
        assertEquals(CatalogOperation.LIST_ROUTES,req.getExpectedOperation());
        assertArrayEquals(gatewayFingerprint,req.getVerifiedGatewayCertificateSha256().toByteArray());
        assertEquals(32,req.getVerifiedGatewayCertificateSha256().size());
        assertEquals(legal.toString(),req.getTargetLegalEntityUuids(0));
    }
    @Test void coreFailureFailsClosedAndScopeRejectsOtherOrganizations()throws Exception {
        CoreDelegationValidation fail=new CoreDelegationValidation(req->{throw new IllegalStateException("core unavailable");});
        StatusRuntimeException error=assertThrows(StatusRuntimeException.class,
            ()->fail.validate("token",CatalogOperation.LIST_ROUTES,List.of(legal),true,false,gateway));
        assertEquals(Status.Code.UNAVAILABLE,error.getStatus().getCode());
        KeyPair pair=keys();String t=token(pair,"LIST_ROUTES","qropass-transport-catalog",
            B64.encodeToString(gatewayFingerprint),clock.instant().getEpochSecond()+30);
        CatalogDelegationAuthorizer authorizer=new CatalogDelegationAuthorizer(verifier(pair),fail);
        assertThrows(StatusRuntimeException.class,()->authorizer.authorize(t,CatalogOperation.LIST_ROUTES,gateway,
            UUID.randomUUID(),true,false));
    }
    /** Отпечаток берётся именно из аутентифицированного SSLSession, не из Metadata. */
    @Test void gatewayFingerprintComesFromAuthenticatedTlsPeer() throws Exception {
        byte[] der=new byte[]{48,3,2,1,5};
        java.security.cert.X509Certificate certificate=
            org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(certificate.getEncoded()).thenReturn(der);
        javax.net.ssl.SSLSession session=org.mockito.Mockito.mock(javax.net.ssl.SSLSession.class);
        org.mockito.Mockito.when(session.getPeerCertificates()).thenReturn(
            new java.security.cert.Certificate[]{certificate});
        org.mockito.Mockito.when(session.getPeerPrincipal()).thenReturn(
            new javax.security.auth.x500.X500Principal("CN=qropass-panel-gateway"));
        @SuppressWarnings("unchecked")
        io.grpc.ServerCall<String,String> call=org.mockito.Mockito.mock(io.grpc.ServerCall.class);
        org.mockito.Mockito.when(call.getAttributes()).thenReturn(io.grpc.Attributes.newBuilder()
            .set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION,session).build());
        var verified=GatewayTlsPeer.fromCall(call,"CN=qropass-panel-gateway");
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(der),verified.certificateSha256());
        byte[] copy=verified.certificateSha256();
        copy[0]^=1;
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(der),verified.certificateSha256());
        assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(
            assertThrows(StatusRuntimeException.class,
                ()->GatewayTlsPeer.fromCall(call,"CN=another-service"))).getCode());
    }

    /** Ответ Core с иной организацией или отсутствующим ROUTES_VIEW не даёт права чтения. */
    @Test void coreScopeCannotBroadenSignedOrganizationsOrRights()throws Exception {
        KeyPair pair=keys();
        String t=token(pair,"LIST_ROUTES","qropass-transport-catalog",
            B64.encodeToString(gatewayFingerprint),clock.instant().getEpochSecond()+30);
        var foreign=UUID.randomUUID();
        CoreDelegationValidation incorrectScope=new CoreDelegationValidation(req->
            AuthorizeResponse.newBuilder().setDecision(AuthorizationDecision.ALLOW)
                .setOperation(CatalogOperation.LIST_ROUTES)
                .addAuthorizedLegalEntityUuids(foreign.toString())
                .setAllowPublished(true).build());
        var authorizer=new CatalogDelegationAuthorizer(verifier(pair),incorrectScope);
        assertThrows(StatusRuntimeException.class,()->authorizer.authorizeSignedScope(
            t,CatalogOperation.LIST_ROUTES,gateway,legal));

        CoreDelegationValidation missingPermission=new CoreDelegationValidation(req->
            AuthorizeResponse.newBuilder().setDecision(AuthorizationDecision.ALLOW)
                .setOperation(CatalogOperation.LIST_ROUTES)
                .addAuthorizedLegalEntityUuids(legal.toString())
                .setAllowPublished(false).build());
        var denied=new CatalogDelegationAuthorizer(verifier(pair),missingPermission);
        assertThrows(StatusRuntimeException.class,()->denied.authorizeSignedScope(
            t,CatalogOperation.LIST_ROUTES,gateway,legal));
    }
    @Test void mTlsInterceptorWithoutSessionRejects() {
        io.grpc.ServerCall<String,String> call=org.mockito.Mockito.mock(io.grpc.ServerCall.class);
        org.mockito.Mockito.when(call.getAttributes()).thenReturn(io.grpc.Attributes.EMPTY);
        assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(
            assertThrows(StatusRuntimeException.class,()->GatewayTlsPeer.fromCall(call,"CN=qropass-panel-gateway")))
            .getCode());
        assertThrows(StatusRuntimeException.class,GatewayTlsServerInterceptor::requirePeer);
    }
}
