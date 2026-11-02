package com.qropass.transport.e2e;

import com.qropass.transport.TransportApplication;
import com.qropass.transport.purchasefacts.CorePurchaseFactsTlsInterceptor;
import com.qropass.transport.purchasefacts.TransportPurchaseFactsGrpcService;
import com.qropass.transport.service.CityTransportContextService;
import com.qropass.transport.service.TransportPublicationService;
import org.springframework.jdbc.core.JdbcTemplate;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Только ручной локальный E2E: самостоятельная JVM, отдельный Purchase Facts listener.
 * Не является Spring-компонентом и не регистрируется в production.
 */
public final class PurchaseFactsStandaloneLauncher {
    private PurchaseFactsStandaloneLauncher() {}

    /** При отсутствии любого TEST ONLY разрешения запуск невозможен. */
    public static void main(String[] args) throws Exception {
        Map<String,String> env=System.getenv();
        if(!"I_ACKNOWLEDGE_TEST_ONLY".equals(env.get("QRP_TEST_ONLY_LAUNCH")))
            throw new SecurityException("Требуется явное разрешение TEST ONLY");
        String jdbc=required(env,"QRP_TEST_DB_URL");
        if(!jdbc.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/qropass_transport_e2e_test(?:\\?.*)?"))
            throw new SecurityException("Разрешена только локальная тестовая БД qropass_transport_e2e_test");
        Path ca=cert(env,"QRP_TEST_CA"),crt=cert(env,"QRP_TEST_SERVER_CERT"),
             key=cert(env,"QRP_TEST_SERVER_KEY");
        int port=Integer.parseInt(env.getOrDefault("QRP_TEST_GRPC_PORT","0"));
        if(port<0 || port>65535) throw new IllegalArgumentException("Некорректный test-only порт");
        try(ConfigurableApplicationContext context=new SpringApplicationBuilder(TransportApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url="+jdbc,
                    "--spring.datasource.username="+required(env,"QRP_TEST_DB_USER"),
                    "--spring.datasource.password="+required(env,"QRP_TEST_DB_PASSWORD"),
                    "--spring.flyway.enabled=true",
                    "--spring.jpa.hibernate.ddl-auto=validate")) {
            if("YES".equals(env.get("QRP_TEST_SEED")))
                PurchaseFactsE2EFixtureSeeder.seed(context.getBean(JdbcTemplate.class),
                    context.getBean(TransportPublicationService.class));
            Server server=NettyServerBuilder.forAddress(new java.net.InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"),port))
                .sslContext(GrpcSslContexts.forServer(crt.toFile(),key.toFile())
                    .trustManager(ca.toFile()).clientAuth(ClientAuth.REQUIRE).build())
                .addService(ServerInterceptors.intercept(
                    new TransportPurchaseFactsGrpcService(
                        context.getBean(CityTransportContextService.class),Clock.systemUTC()),
                    new CorePurchaseFactsTlsInterceptor(CorePurchaseFactsTlsInterceptor.TEST_CORE_URI)))
                .build().start();
            Runtime.getRuntime().addShutdownHook(new Thread(()->{
                server.shutdown();
                try {server.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
            },"transport-test-only-shutdown"));
            System.out.println("TEST_ONLY_PURCHASE_FACTS_READY pid="+ProcessHandle.current().pid()+
                " host=127.0.0.1 port="+server.getPort()+
                " method="+CorePurchaseFactsTlsInterceptor.METHOD);
            try { server.awaitTermination(); }
            finally { server.shutdownNow(); server.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }
    /** Проверяет наличие обязательного параметра. */
    private static String required(Map<String,String> env,String name) {
        String value=env.get(name);
        if(value==null || value.isBlank()) throw new SecurityException("Отсутствует "+name);
        return value;
    }
    /** Использует только существующие файлы сертификатов из внешнего каталога. */
    private static Path cert(Map<String,String> env,String name) {
        Path value=Path.of(required(env,name)).toAbsolutePath().normalize();
        if(!Files.isRegularFile(value)) throw new SecurityException("Отсутствует файл "+name);
        return value;
    }
}
