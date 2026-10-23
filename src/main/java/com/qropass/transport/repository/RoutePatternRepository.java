package com.qropass.transport.repository;

import com.qropass.transport.entity.RoutePattern;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям RoutePattern. */
public interface RoutePatternRepository extends JpaRepository<RoutePattern, UUID> {}
