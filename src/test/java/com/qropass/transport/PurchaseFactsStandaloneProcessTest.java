package com.qropass.transport;

import com.qropass.transport.grpc.purchasefacts.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Реальная отдельная JVM Transport и test-only PostgreSQL, без запуска Core. */
class PurchaseFactsStandaloneProcessTest {
    /** Проверяет PID, готовность RPC и гарантированное завершение дочерней JVM. */
    @Test void separateJvmIsReadyServesGrpcAndExits() throws Exception {
        try(PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("qropass_transport_e2e_test")) {
            postgres.start();
            PurchaseFactsMtlsPostgresTest.certificates();
            Process process=null;
            ManagedChannel channel=null;
            try {
                String java=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
                String classpath=System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path"));
                ProcessBuilder builder=new ProcessBuilder(java,"-cp",classpath,
                    "com.qropass.transport.e2e.PurchaseFactsStandaloneLauncher");
                builder.redirectErrorStream(true);
                var env=builder.environment();
                env.put("QRP_TEST_ONLY_LAUNCH","I_ACKNOWLEDGE_TEST_ONLY");
                env.put("QRP_TEST_DB_URL",postgres.getJdbcUrl());
                env.put("QRP_TEST_DB_USER",postgres.getUsername());
                env.put("QRP_TEST_DB_PASSWORD",postgres.getPassword());
                env.put("QRP_TEST_CA",PurchaseFactsMtlsPostgresTest.dir.resolve("ca.crt").toString());
                env.put("QRP_TEST_SERVER_CERT",PurchaseFactsMtlsPostgresTest.dir.resolve("server.crt").toString());
                env.put("QRP_TEST_SERVER_KEY",PurchaseFactsMtlsPostgresTest.dir.resolve("server.key").toString());
                env.put("QRP_TEST_SEED","YES");
                env.put("QRP_TEST_GRPC_PORT","0");
                process=builder.start();
                var lines=new LinkedBlockingQueue<String>();
                Process child=process;
                Thread reader=Thread.ofVirtual().start(()->{
                    try(var input=new BufferedReader(new InputStreamReader(
                            child.getInputStream(),StandardCharsets.UTF_8))) {
                        String line;
                        while((line=input.readLine())!=null) lines.offer(line);
                    }catch(IOException e){lines.offer("TEST_LOG_READ_ERROR "+e.getMessage());}
                });
                String ready=null,fixture=null;
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
                while(System.nanoTime()<until && (ready==null || fixture==null)) {
                    String line=lines.poll(1,TimeUnit.SECONDS);
                    if(line==null) {if(!child.isAlive()) break;continue;}
                    if(line.contains("TEST_ONLY_FIXTURE"))fixture=line;
                    if(line.contains("TEST_ONLY_PURCHASE_FACTS_READY"))ready=line;
                }
                assertNotNull(ready,"Нет готовности отдельной JVM: "+lines);
                assertNotNull(fixture,"Нет TEST ONLY транспортной фикстуры");
                var match=Pattern.compile("pid=(\\d+) host=127\\.0\\.0\\.1 port=(\\d+)").matcher(ready);
                assertTrue(match.find(),ready);
                assertEquals(child.pid(),Long.parseLong(match.group(1)));
                int port=Integer.parseInt(match.group(2));
                assertTrue(port>0);
                var route=Pattern.compile("routeId=([0-9a-f-]{36})").matcher(fixture);
                var version=Pattern.compile("patternVersionId=([0-9a-f-]{36})").matcher(fixture);
                assertTrue(route.find()&&version.find(),fixture);
                channel=NettyChannelBuilder.forAddress("127.0.0.1",port)
                    .overrideAuthority("TEST-ONLY-server")
                    .sslContext(GrpcSslContexts.forClient()
                        .trustManager(PurchaseFactsMtlsPostgresTest.dir.resolve("ca.crt").toFile())
                        .keyManager(PurchaseFactsMtlsPostgresTest.dir.resolve("core.crt").toFile(),
                            PurchaseFactsMtlsPostgresTest.dir.resolve("core.key").toFile())
                        .build()).build();
                var response=TransportPurchaseFactsServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(6,TimeUnit.SECONDS)
                    .resolveCityTransportContext(ResolveCityTransportContextRequest.newBuilder()
                        .setLegalEntityInn("4027059760").setRouteId(route.group(1))
                        .setPatternVersionId(version.group(1)).build());
                assertEquals("PUBLISHED",response.getPublicationStatus());
                assertFalse(response.getCityCategoryConfirmed());
                assertFalse(response.getSalePermitted());
                System.out.println("TEST ONLY отдельная JVM: pid="+child.pid()+" port="+port+
                    " gRPC/PostgreSQL=PASS");
            } finally {
                if(channel!=null){channel.shutdownNow();channel.awaitTermination(5,TimeUnit.SECONDS);}
                if(process!=null) {
                    process.destroy();
                    if(!process.waitFor(10,TimeUnit.SECONDS)){
                        process.destroyForcibly();
                        assertTrue(process.waitFor(10,TimeUnit.SECONDS));
                    }
                    assertFalse(process.isAlive(),"Дочерняя JVM не завершилась");
                }
                PurchaseFactsMtlsPostgresTest.cleanup();
            }
        }
    }
}
