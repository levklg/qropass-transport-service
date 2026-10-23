package com.qropass.transport.repository;

import com.qropass.transport.entity.Route;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Доступ к записям Route. */
public interface RouteRepository extends JpaRepository<Route, UUID> {}
