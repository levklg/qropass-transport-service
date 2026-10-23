package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность RoutePattern. */
@Entity
@Table(name="route_pattern")
public class RoutePattern {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected RoutePattern() {}
    /** Создаёт новый черновой объект. */
    public static RoutePattern create() { return new RoutePattern(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Значение direction. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="direction_id")
    private RouteDirection direction;
    /** Читает direction. */
    public RouteDirection getDirection() { return direction; }
    /** Изменяет direction в черновике. */
    public void setDirection(RouteDirection value) { this.direction=value; }
    /** Значение patternCode. */
    @Column(name="pattern_code")
    private String patternCode;
    /** Читает patternCode. */
    public String getPatternCode() { return patternCode; }
    /** Изменяет patternCode в черновике. */
    public void setPatternCode(String value) { this.patternCode=value; }
    /** Значение displayName. */
    @Column(name="display_name")
    private String displayName;
    /** Читает displayName. */
    public String getDisplayName() { return displayName; }
    /** Изменяет displayName в черновике. */
    public void setDisplayName(String value) { this.displayName=value; }
}
