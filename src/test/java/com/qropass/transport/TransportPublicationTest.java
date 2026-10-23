package com.qropass.transport;

import com.qropass.transport.service.TransportPublicationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки запрета публикации и продажи до T1.3. */
class TransportPublicationTest {
  private final TransportPublicationService service=new TransportPublicationService();

  /** Публикация не допускается. */
  @Test void publicationForbidden() {
    assertThrows(UnsupportedOperationException.class,()->service.publish(UUID.randomUUID()));
  }

  /** Продажа не допускается. */
  @Test void salesForbidden() {
    assertThrows(UnsupportedOperationException.class,()->service.enableSales(UUID.randomUUID()));
  }
}
