package com.qropass.transport.repository;

import com.qropass.transport.entity.OperatorAssignment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям OperatorAssignment. */
public interface OperatorAssignmentRepository extends JpaRepository<OperatorAssignment, UUID> {}
