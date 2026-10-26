package com.qropass.transport.catalog.security;

import io.grpc.*;
import java.util.Objects;

/**
 * Изолированный mTLS interceptor. Не зарегистрирован в сетевом listener.
 * Контекст формируется только из атрибутов ServerCall, не из Metadata.
 */
public final class GatewayTlsServerInterceptor implements ServerInterceptor {
    private static final Context.Key<GatewayTlsPeer.Verified> PEER=Context.key("transport.verifiedGatewayPeer");
    private static final Context.Key<String> TOKEN=Context.key("transport.delegationToken");
    private static final Metadata.Key<String> AUTHORIZATION=Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER);
    private final String expectedTlsPrincipal;
    /** Principal должен быть задан из проверенной конфигурации, без значения по умолчанию. */
    public GatewayTlsServerInterceptor(String expectedTlsPrincipal) {
        this.expectedTlsPrincipal=Objects.requireNonNull(expectedTlsPrincipal);
        if(expectedTlsPrincipal.isBlank()) throw new IllegalArgumentException("Нужна TLS identity Gateway");
    }
    @Override
    public <Q,R> ServerCall.Listener<Q> interceptCall(ServerCall<Q,R> call,Metadata headers,
                                                      ServerCallHandler<Q,R> next) {
        GatewayTlsPeer.Verified verified=GatewayTlsPeer.fromCall(call,expectedTlsPrincipal);
        String bearer=headers.get(AUTHORIZATION);
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()>16400)
            throw Status.UNAUTHENTICATED.withDescription("Делегирование не представлено").asRuntimeException();
        return Contexts.interceptCall(Context.current().withValue(PEER,verified)
            .withValue(TOKEN,bearer.substring(7)),call,headers,next);
    }
    /** Возвращает JWS из request metadata; этот токен сам по себе не удостоверяет клиента. */
    public static String requireToken() {
        String token=TOKEN.get();
        if(token==null || token.isBlank())
            throw Status.UNAUTHENTICATED.withDescription("Токен отсутствует").asRuntimeException();
        return token;
    }
    /** Извлекает только значение, установленное mTLS interceptor. */
    public static GatewayTlsPeer.Verified requirePeer() {
        GatewayTlsPeer.Verified peer=PEER.get();
        if(peer==null) throw Status.UNAUTHENTICATED.withDescription("Отсутствует mTLS Gateway").asRuntimeException();
        return peer;
    }
}
