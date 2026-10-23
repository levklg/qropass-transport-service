package com.qropass.transport.repository;

import com.qropass.transport.entity.RoutePatternVersion;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Репозиторий транспортных версий с сериализуемой блокировкой публикации. */
public interface RoutePatternVersionRepository extends JpaRepository<RoutePatternVersion,UUID> {
    /** Блокирует версию против конкурирующей публикации и структурного изменения. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from RoutePatternVersion v where v.id=:id")
    Optional<RoutePatternVersion> lockForPublication(@Param("id") UUID id);
}
