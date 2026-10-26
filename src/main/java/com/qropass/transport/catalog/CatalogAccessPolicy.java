package com.qropass.transport.catalog;

import com.qropass.grpc.panel.security.v1.CatalogOperation;
import com.qropass.transport.catalog.security.CatalogDelegationAuthorizer;
import com.qropass.transport.catalog.security.GatewayTlsServerInterceptor;
import io.grpc.Status;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fail-closed policy SEC-TRANSPORT-001. Без установленного доверенного
 * клиента Core и mTLS interceptor выдача каталога технически невозможна.
 * Никогда не доверяет legalEntityId в protobuf как полномочию.
 */
@Component
public class CatalogAccessPolicy {
    private final CatalogDelegationAuthorizer delegations;
    /** Production пока отключён: нет подключённого и доверенного mTLS Core. */
    public CatalogAccessPolicy() { this.delegations=null; }

    /** Изолированный конструктор для проверки полностью связанного контура. */
    public CatalogAccessPolicy(CatalogDelegationAuthorizer delegations) {
        this.delegations=java.util.Objects.requireNonNull(delegations);
    }
    /** Совместимый барьер: закрыт без полномочий. */
    public void requireAuthorized(UUID legalEntityId) {
        throw Status.UNAUTHENTICATED.withDescription(
            "Доверенная идентичность QRP-SEC-001 ещё не интегрирована").asRuntimeException();
    }

    /** Только результат Core VALIDATE_DELEGATION используется для SQL-фильтров. */
    public CatalogDelegationAuthorizer.Scope requireAuthorized(
        CatalogOperation operation, UUID targetLegalEntityId) {
        if(delegations==null)
            throw Status.UNAUTHENTICATED.withDescription("Core/mTLS authorization не сконфигурирована")
                .asRuntimeException();
        return delegations.authorizeSignedScope(GatewayTlsServerInterceptor.requireToken(),
            operation,GatewayTlsServerInterceptor.requirePeer(),targetLegalEntityId);
    }
}
