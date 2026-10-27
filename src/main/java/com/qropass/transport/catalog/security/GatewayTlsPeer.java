package com.qropass.transport.catalog.security;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.ServerCall;
import io.grpc.Status;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Доказательство Gateway peer, выведенное только из TLS сеанса gRPC.
 * Не принимает сертификат или отпечаток из Metadata, protobuf или параметров клиента.
 */
public final class GatewayTlsPeer {
    private GatewayTlsPeer() {}

    /** Извлекает аутентифицированный X.509 peer с проверенной сервисной идентичностью. */
    public static Verified fromCall(ServerCall<?, ?> call, String expectedServiceIdentity) {
        if (call==null || expectedServiceIdentity==null || expectedServiceIdentity.isBlank())
            throw Status.UNAUTHENTICATED.withDescription("Отсутствует TLS peer или доверенная конфигурация").asRuntimeException();
        SSLSession session=call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if(session==null) throw Status.UNAUTHENTICATED.withDescription("mTLS необходим").asRuntimeException();
        try {
            var chain=session.getPeerCertificates();
            if(chain.length<1 || !(chain[0] instanceof X509Certificate certificate))
                throw new SecurityException("Не представлен X509 Gateway");
            certificate.checkValidity();
            // Сервисное имя должно соответствовать проверенному peer principal.
            // В production сопоставление SAN и профиля доверия требуется расширить до
            // утверждённой политики mTLS; одного поля Subject недостаточно.
            if(!expectedServiceIdentity.equals(session.getPeerPrincipal().getName()))
                throw new SecurityException("TLS identity не совпадает с Gateway");
            byte[] sha256=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            if(sha256.length!=32) throw new SecurityException("Некорректный TLS fingerprint");
            return new Verified(expectedServiceIdentity,sha256);
        } catch(SSLPeerUnverifiedException | java.security.GeneralSecurityException | SecurityException ex) {
            throw Status.UNAUTHENTICATED.withDescription("Gateway mTLS peer не подтверждён").asRuntimeException();
        }
    }

    /** Значение из TLS; массив нельзя изменять извне. */
    /** Создаётся исключительно из проверенного peer TLS-сеанса. */
    public static final class Verified {
        private final String serviceIdentity;
        private final byte[] certificateSha256;
        private Verified(String serviceIdentity,byte[] certificateSha256) {
            this.serviceIdentity=serviceIdentity;
            this.certificateSha256=certificateSha256.clone();
        }
        public String serviceIdentity() { return serviceIdentity; }
        public byte[] certificateSha256() { return certificateSha256.clone(); }
    }
}
