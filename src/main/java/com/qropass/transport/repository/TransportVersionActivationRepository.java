package com.qropass.transport.repository;

import com.qropass.transport.entity.TransportVersionActivation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Репозиторий состояния продаж версий. */
public interface TransportVersionActivationRepository extends JpaRepository<TransportVersionActivation,UUID> {
    /** Находит запись доступности версии. */
    Optional<TransportVersionActivation> findByPatternVersion_Id(UUID versionId);
}
