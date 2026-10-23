package com.qropass.transport.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Барьер публикации T1.2.
 * Переход в PUBLISHED разрешается только на T1.3 одновременно с fingerprint.
 */
@Service
public class TransportPublicationService {
    /** Запрещает любую публикацию до реализации утверждённого SHA-256. */
    public void publish(UUID versionId) {
        throw new UnsupportedOperationException("Публикация запрещена до завершения T1.3");
    }

    /** Запрещает продажу на этапе T1.2. */
    public void enableSales(UUID versionId) {
        throw new UnsupportedOperationException("Продажи запрещены на этапе T1.2");
    }
}
