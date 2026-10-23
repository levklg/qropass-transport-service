package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность Route. */
@Entity
@Table(name="route")
public class Route {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected Route() {}
    /** Создаёт новый черновой объект. */
    public static Route create() { return new Route(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Значение routeNumber. */
    @Column(name="route_number")
    private String routeNumber;
    /** Читает routeNumber. */
    public String getRouteNumber() { return routeNumber; }
    /** Изменяет routeNumber в черновике. */
    public void setRouteNumber(String value) { this.routeNumber=value; }
    /** Значение routeName. */
    @Column(name="route_name")
    private String routeName;
    /** Читает routeName. */
    public String getRouteName() { return routeName; }
    /** Изменяет routeName в черновике. */
    public void setRouteName(String value) { this.routeName=value; }
    /** Значение status. */
    @Column(name="status")
    private String status;
    /** Читает status. */
    public String getStatus() { return status; }
    /** Изменяет status в черновике. */
    public void setStatus(String value) { this.status=value; }
}
