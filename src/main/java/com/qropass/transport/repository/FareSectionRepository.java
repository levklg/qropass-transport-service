package com.qropass.transport.repository;

import com.qropass.transport.entity.FareSection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Репозиторий тарифных участков. */
public interface FareSectionRepository extends JpaRepository<FareSection,UUID> {
    /** Читает участки одной версии. */
    List<FareSection> findByPatternVersion_Id(UUID versionId);
}
