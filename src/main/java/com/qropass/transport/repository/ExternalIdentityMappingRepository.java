package com.qropass.transport.repository;

import com.qropass.transport.entity.ExternalIdentityMapping;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям ExternalIdentityMapping. */
public interface ExternalIdentityMappingRepository extends JpaRepository<ExternalIdentityMapping, UUID> {}
