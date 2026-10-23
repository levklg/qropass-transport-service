package com.qropass.transport.catalog;

import io.grpc.Status;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fail-closed барьер QRP-SEC-001. До подключения доверенного сервисного
 * делегирования доступ к любому каталогу, включая опубликованные данные, закрыт.
 * UUID, переданный в запросе, не является подтверждением полномочия.
 */
@Component
public class CatalogAccessPolicy {
    /** Не выдаёт разрешение по непроверенной клиентской идентичности. */
    public void requireAuthorized(UUID legalEntityId) {
        throw Status.UNAUTHENTICATED.withDescription(
            "Доверенная идентичность QRP-SEC-001 ещё не интегрирована").asRuntimeException();
    }
}
