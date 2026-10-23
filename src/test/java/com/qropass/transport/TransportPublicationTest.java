package com.qropass.transport;

import com.qropass.transport.service.*;
import com.qropass.transport.repository.*;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Защита публикации и продаж. */
class TransportPublicationTest {
    private TransportPublicationService service(RoutePatternVersionRepository versions) {
        return new TransportPublicationService(versions,mock(StopOccurrenceRepository.class),
            mock(FareSectionRepository.class),mock(OperatorAssignmentRepository.class),
            mock(TransportVersionActivationRepository.class),new TransportFingerprintService());
    }
    /** Отсутствующую версию опубликовать нельзя. */
    @Test void missingVersionRejected() {
        RoutePatternVersionRepository versions=mock(RoutePatternVersionRepository.class);
        when(versions.lockForPublication(any())).thenReturn(Optional.empty());
        assertThrows(PublicationConflictException.class,()->service(versions).publish(UUID.randomUUID()));
    }
    /** Продажи остаются заблокированными. */
    @Test void salesForbidden() {
        assertThrows(UnsupportedOperationException.class,
            ()->service(mock(RoutePatternVersionRepository.class)).enableSales(UUID.randomUUID()));
    }
}
