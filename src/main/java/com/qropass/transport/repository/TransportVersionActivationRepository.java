package com.qropass.transport.repository;

import com.qropass.transport.entity.TransportVersionActivation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям TransportVersionActivation. */
public interface TransportVersionActivationRepository extends JpaRepository<TransportVersionActivation, UUID> {}
