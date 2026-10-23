package com.qropass.transport.repository;

import com.qropass.transport.entity.StopOccurrence;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям StopOccurrence. */
public interface StopOccurrenceRepository extends JpaRepository<StopOccurrence, UUID> {}
