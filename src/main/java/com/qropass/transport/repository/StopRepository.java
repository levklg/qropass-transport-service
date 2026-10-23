package com.qropass.transport.repository;

import com.qropass.transport.entity.Stop;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям Stop. */
public interface StopRepository extends JpaRepository<Stop, UUID> {}
