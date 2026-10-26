package com.qropass.transport.catalog.security;

import io.grpc.*;
import java.util.Objects;

/**
 * Изолированный mTLS interceptor. Не зарегистрирован в сетевом listener.
 * Контекст формируется только из атрибутов ServerCall, не из Metadata.
 */
public final class GatewayTlsServerInterceptor implements ServerInterceptor {
    private static final Context.Key<GatewayTlsPeer.Verified> PEER=Context.key("transport.verifiedGatewayPeer");
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
        return Contexts.interceptCall(Context.current().withValue(PEER,verified),call,headers,next);
    }
    /** Извлекает только значение, установленное mTLS interceptor. */
    public static GatewayTlsPeer.Verified requirePeer() {
        GatewayTlsPeer.Verified peer=PEER.get();
        if(peer==null) throw Status.UNAUTHENTICATED.withDescription("Отсутствует mTLS Gateway").asRuntimeException();
        return peer;
    }
}
