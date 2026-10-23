package com.qropass.transport.repository;

import com.qropass.transport.entity.RoutePatternVersion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям RoutePatternVersion. */
public interface RoutePatternVersionRepository extends JpaRepository<RoutePatternVersion, UUID> {}
