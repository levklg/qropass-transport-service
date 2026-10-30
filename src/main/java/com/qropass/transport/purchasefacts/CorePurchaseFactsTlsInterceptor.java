package com.qropass.transport.purchasefacts;

import io.grpc.*;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLSession;

/** Проверяет непосредственно TLS peer Core; не принимает identity из metadata. */
public final class CorePurchaseFactsTlsInterceptor implements ServerInterceptor {
    /** Тестовая identity. Не является production конфигурацией. */
    public static final String TEST_CORE_URI = "spiffe://qropass.test/core";
    /** Единственный разрешённый метод. */
    public static final String METHOD =
        "qropass.transport.purchasefacts.v1.TransportPurchaseFactsService/ResolveCityTransportContext";
    private final String expectedUri;
    /** Требует явного URI SAN для вызова. */
    public CorePurchaseFactsTlsInterceptor(String expectedUri) {
        if(expectedUri==null || !expectedUri.startsWith("spiffe://"))
            throw new IllegalArgumentException("Не задан доверенный URI SAN");
        this.expectedUri=expectedUri;
    }
    /** Разрешает только подтверждённый TLS сеанс Core и один gRPC метод. */
    @Override public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT,RespT> call, Metadata headers, ServerCallHandler<ReqT,RespT> next) {
        if(!METHOD.equals(call.getMethodDescriptor().getFullMethodName()))
            return reject(call,Status.PERMISSION_DENIED);
        try {
            SSLSession ssl=call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
            if(ssl==null) return reject(call,Status.UNAUTHENTICATED);
            java.security.cert.Certificate[] chain=ssl.getPeerCertificates();
            if(chain.length==0 || !(chain[0] instanceof X509Certificate cert))
                return reject(call,Status.UNAUTHENTICATED);
            cert.checkValidity();
            List<String> eku=cert.getExtendedKeyUsage();
            if(eku==null || !eku.contains("1.3.6.1.5.5.7.3.2"))
                return reject(call,Status.PERMISSION_DENIED);
            Collection<List<?>> sans=cert.getSubjectAlternativeNames();
            if(sans==null || sans.stream().noneMatch(x->x.size()>1 &&
                    Integer.valueOf(6).equals(x.get(0)) && expectedUri.equals(x.get(1))))
                return reject(call,Status.PERMISSION_DENIED);
            return next.startCall(call,headers);
        } catch(Exception e) {
            return reject(call,Status.UNAUTHENTICATED);
        }
    }
    /** Возвращает отказ без выполнения обработчика и запроса к PostgreSQL. */
    private static <ReqT,RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT,RespT> call,Status status) {
        call.close(status.withDescription("Служебный доступ запрещён"),new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
