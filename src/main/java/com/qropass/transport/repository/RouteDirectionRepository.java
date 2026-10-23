package com.qropass.transport.repository;

import com.qropass.transport.entity.RouteDirection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям RouteDirection. */
public interface RouteDirectionRepository extends JpaRepository<RouteDirection, UUID> {}
