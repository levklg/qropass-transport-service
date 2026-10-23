package com.qropass.transport.repository;

import com.qropass.transport.entity.StopOccurrence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Репозиторий остановочных вхождений. */
public interface StopOccurrenceRepository extends JpaRepository<StopOccurrence,UUID> {
    /** Читает вхождения одной версии. */
    List<StopOccurrence> findByPatternVersion_Id(UUID versionId);
}
