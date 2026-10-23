package com.qropass.transport.repository;

import com.qropass.transport.entity.FareSection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям FareSection. */
public interface FareSectionRepository extends JpaRepository<FareSection, UUID> {}
