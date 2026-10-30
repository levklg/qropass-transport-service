package com.qropass.transport;

import com.qropass.transport.purchasefacts.*;
import com.qropass.transport.service.*;
import com.qropass.transport.grpc.purchasefacts.v1.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** TEST ONLY: реальный Netty mTLS handshake и PostgreSQL 15 без production listener. */
@SpringBootTest
@Testcontainers
class PurchaseFactsMtlsPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15");
    @DynamicPropertySource static void db(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",postgres::getJdbcUrl);
        props.add("spring.datasource.username",postgres::getUsername);
        props.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CityTransportContextService facts;
    @Autowired TransportPublicationService publication;
    static Path dir;
    static final String CORE="spiffe://qropass.test/core",TRANSPORT="spiffe://qropass.test/transport";
    static final Instant NOW=Instant.parse("2026-10-30T12:00:00Z");
    private Server server;
    private ManagedChannel channel;
    private UUID route,version;

    /** Создаёт одноразовые сертификаты через openssl, без хранения ключей в GitHub. */
    @BeforeAll static void certificates() throws Exception {
        dir=Files.createTempDirectory("qropass-test-pki-");
        run("openssl","req","-x509","-newkey","rsa:2048","-nodes","-keyout",path("ca.key"),
            "-out",path("ca.crt"),"-days","1","-subj","/CN=TEST-ONLY-QroPass-CA",
            "-addext","basicConstraints=critical,CA:TRUE",
            "-addext","keyUsage=critical,keyCertSign,cRLSign");
        cert("server",TRANSPORT,"serverAuth",false);
        cert("core",CORE,"clientAuth",false);
        cert("gateway","spiffe://qropass.test/gateway","clientAuth",false);
        cert("wrong","spiffe://qropass.test/wrong","clientAuth",false);
        cert("server-only",CORE,"serverAuth",false);
        run("openssl","req","-x509","-newkey","rsa:2048","-nodes",
            "-keyout",path("rogue-ca.key"),"-out",path("rogue-ca.crt"),"-days","1",
            "-subj","/CN=TEST-ONLY-ROGUE-CA","-addext","basicConstraints=critical,CA:TRUE",
            "-addext","keyUsage=critical,keyCertSign,cRLSign");
        cert("rogue",CORE,"clientAuth",true);
    }
    private static String path(String s){return dir.resolve(s).toString();}
    private static void run(String...cmd)throws Exception {
        Process proc=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out=new String(proc.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(proc.waitFor()!=0) throw new IllegalStateException("Ошибка генерации тестового сертификата: "+out);
    }
    private static void cert(String name,String uri,String eku,boolean otherCA)throws Exception {
        run("openssl","req","-newkey","rsa:2048","-nodes","-keyout",path(name+".key"),
            "-out",path(name+".csr"),"-subj","/CN=TEST-ONLY-"+name);
        Files.writeString(dir.resolve(name+".ext"),"basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage="+eku+
            "\nsubjectAltName=URI:"+uri+"\n");
        run("openssl","x509","-req","-in",path(name+".csr"),"-CA",path(otherCA?"rogue-ca.crt":"ca.crt"),"-CAkey",
            path(otherCA?"rogue-ca.key":"ca.key"),"-CAcreateserial","-out",path(name+".crt"),"-days","1",
            "-extfile",path(name+".ext"));
    }
    @AfterAll static void cleanup() throws Exception {
        if(dir!=null)try(var files=Files.list(dir)) {
            for(Path path:files.toList()) Files.deleteIfExists(path);
            Files.deleteIfExists(dir);
        }
    }
    /** Тестовые схемы не смешиваются между методами. */
    @BeforeEach void before() throws Exception {
        jdbc.execute("TRUNCATE TABLE legal_entity,route,stop CASCADE");
        route=UUID.randomUUID();version=UUID.randomUUID();
        UUID legal=UUID.randomUUID(),direction=UUID.randomUUID(),pattern=UUID.randomUUID();
        UUID stop1=UUID.randomUUID(),stop2=UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entity(id,legal_name,inn,status) VALUES(?,'TEST ONLY ЭГОНОМ-3','4027059760','ACTIVE')",legal);
        jdbc.update("INSERT INTO route(id,route_number,route_name) VALUES(?,'TEST-CITY','Тестовый маршрут')",route);
        jdbc.update("INSERT INTO route_direction(id,route_id,direction_code) VALUES(?,?,'OUT')",direction,route);
        jdbc.update("INSERT INTO route_pattern(id,direction_id,pattern_code) VALUES(?,?,'MAIN')",pattern,direction);
        jdbc.update("INSERT INTO operator_assignment(id,route_id,legal_entity_id,role,status,valid_from,evidence_reference) "+
            "VALUES(?,?,?,'CARRIER','VERIFIED',?,'TEST-ONLY')",UUID.randomUUID(),route,legal,
            java.sql.Timestamp.from(NOW.minusSeconds(86400)));
        jdbc.update("INSERT INTO route_pattern_version(id,pattern_id,legal_entity_id,version_number,effective_from) "+
            "VALUES(?,?,?,1,?)",version,pattern,legal,java.sql.Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("INSERT INTO stop(id,canonical_name) VALUES(?,'TEST A'),(?,'TEST B')",stop1,stop2);
        jdbc.update("INSERT INTO stop_occurrence(id,pattern_version_id,stop_id,sequence_number,pickup_allowed,dropoff_allowed) "+
            "VALUES(?,?,?,1,true,true),(?,?,?,2,true,true)",
            UUID.randomUUID(),version,stop1,UUID.randomUUID(),version,stop2);
        publication.publish(version);
        server=NettyServerBuilder.forPort(0)
            .sslContext(GrpcSslContexts.forServer(dir.resolve("server.crt").toFile(),
                dir.resolve("server.key").toFile())
                .trustManager(dir.resolve("ca.crt").toFile()).clientAuth(ClientAuth.REQUIRE).build())
            .addService(ServerInterceptors.intercept(new TransportPurchaseFactsGrpcService(
                facts,Clock.fixed(NOW,ZoneOffset.UTC)),new CorePurchaseFactsTlsInterceptor(CORE)))
            .build().start();
    }
    /** Завершает изолированный сервер и клиент после каждого теста. */
    @AfterEach void after()throws Exception {
        if(channel!=null) channel.shutdownNow();
        if(server!=null) server.shutdownNow();
    }
    /** Создаёт клиента со своим test-only сертификатом и серверной CA. */
    private TransportPurchaseFactsServiceGrpc.TransportPurchaseFactsServiceBlockingStub client(String cert,
            boolean trusted) throws Exception {
        var builder=GrpcSslContexts.forClient();
        if(trusted)builder.trustManager(dir.resolve("ca.crt").toFile());
        else {
            // Самоподписанная посторонняя CA: TLS доверие к серверу отсутствует.
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }
        if(cert!=null)builder.keyManager(dir.resolve(cert+".crt").toFile(),dir.resolve(cert+".key").toFile());
        channel=NettyChannelBuilder.forAddress("localhost",server.getPort())
            .overrideAuthority("TEST-ONLY-server")
            .sslContext(builder.build()).build();
        return TransportPurchaseFactsServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(4,java.util.concurrent.TimeUnit.SECONDS);
    }
    private ResolveCityTransportContextRequest request() {
        return ResolveCityTransportContextRequest.newBuilder().setLegalEntityInn("4027059760")
            .setRouteId(route.toString()).setPatternVersionId(version.toString()).build();
    }
    @Test void realCoreCertificateCallsPostgresAndNeverAllowsSale() throws Exception {
        var response=client("core",true).resolveCityTransportContext(request());
        assertEquals(route.toString(),response.getRouteId());
        assertEquals(version.toString(),response.getPatternVersionId());
        assertEquals("PUBLISHED",response.getPublicationStatus());
        assertTrue(response.getContentFingerprint().startsWith("sha256:"));
        assertFalse(response.getCityCategoryConfirmed());
        assertFalse(response.getSalePermitted());
    }
    @Test void noCertificateRejectedAtTlsHandshake() throws Exception {
        assertThrows(StatusRuntimeException.class,()->client(null,true).resolveCityTransportContext(request()));
    }
    @Test void gatewayIdentityRejected() throws Exception {
        assertEquals(Status.Code.PERMISSION_DENIED,Status.fromThrowable(assertThrows(
            StatusRuntimeException.class,()->client("gateway",true).resolveCityTransportContext(request()))).getCode());
    }
    @Test void wrongUriSanRejected() throws Exception {
        assertEquals(Status.Code.PERMISSION_DENIED,Status.fromThrowable(assertThrows(
            StatusRuntimeException.class,()->client("wrong",true).resolveCityTransportContext(request()))).getCode());
    }
    @Test void missingClientAuthEkuRejected() throws Exception {
        assertThrows(StatusRuntimeException.class,
            ()->client("server-only",true).resolveCityTransportContext(request()));
    }
    /** Неизвестный центр сертификации не может аутентифицировать Core. */
    @Test void unknownCertificateAuthorityRejectedAtTlsHandshake() throws Exception {
        assertThrows(StatusRuntimeException.class,
            ()->client("rogue",true).resolveCityTransportContext(request()));
    }
    /** Другой RPC отсутствует в test-only listener и не исполняет SQL. */
    @Test void unauthorizedMethodCannotBeCalled() throws Exception {
        var stub=client("core",true);
        var otherMethod=io.grpc.MethodDescriptor.<ResolveCityTransportContextRequest,
            ResolveCityTransportContextResponse>newBuilder()
            .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("qropass.transport.catalog.v1.TransportCatalogService/ListRoutes")
            .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                ResolveCityTransportContextRequest.getDefaultInstance()))
            .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                ResolveCityTransportContextResponse.getDefaultInstance())).build();
        var denied=assertThrows(StatusRuntimeException.class,()->io.grpc.stub.ClientCalls.blockingUnaryCall(
            channel,otherMethod,io.grpc.CallOptions.DEFAULT.withDeadlineAfter(4,
                java.util.concurrent.TimeUnit.SECONDS),request()));
        assertEquals(Status.Code.UNIMPLEMENTED,denied.getStatus().getCode());
    }
    @Test void invalidInputAndUnconfirmedOrganizationRejected() throws Exception {
        var stub=client("core",true);
        assertEquals(Status.Code.INVALID_ARGUMENT,Status.fromThrowable(assertThrows(
            StatusRuntimeException.class,()->stub.resolveCityTransportContext(
                request().toBuilder().setRouteId("INVALID").build()))).getCode());
        assertEquals(Status.Code.FAILED_PRECONDITION,Status.fromThrowable(assertThrows(
            StatusRuntimeException.class,()->stub.resolveCityTransportContext(
                request().toBuilder().setLegalEntityInn("1111111111").build()))).getCode());
    }
}
