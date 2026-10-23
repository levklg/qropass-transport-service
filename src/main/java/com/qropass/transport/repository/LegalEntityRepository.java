package com.qropass.transport.repository;

import com.qropass.transport.entity.LegalEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям LegalEntity. */
public interface LegalEntityRepository extends JpaRepository<LegalEntity, UUID> {}
