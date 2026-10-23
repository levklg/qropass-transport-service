package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность RouteDirection. */
@Entity
@Table(name="route_direction")
public class RouteDirection {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected RouteDirection() {}
    /** Создаёт новый черновой объект. */
    public static RouteDirection create() { return new RouteDirection(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Значение route. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="route_id")
    private Route route;
    /** Читает route. */
    public Route getRoute() { return route; }
    /** Изменяет route в черновике. */
    public void setRoute(Route value) { this.route=value; }
    /** Значение directionCode. */
    @Column(name="direction_code")
    private String directionCode;
    /** Читает directionCode. */
    public String getDirectionCode() { return directionCode; }
    /** Изменяет directionCode в черновике. */
    public void setDirectionCode(String value) { this.directionCode=value; }
    /** Значение displayName. */
    @Column(name="display_name")
    private String displayName;
    /** Читает displayName. */
    public String getDisplayName() { return displayName; }
    /** Изменяет displayName в черновике. */
    public void setDisplayName(String value) { this.displayName=value; }
}
