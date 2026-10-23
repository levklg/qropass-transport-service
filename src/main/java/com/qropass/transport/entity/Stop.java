package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность Stop. */
@Entity
@Table(name="stop")
public class Stop {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected Stop() {}
    /** Создаёт новый черновой объект. */
    public static Stop create() { return new Stop(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Значение canonicalName. */
    @Column(name="canonical_name")
    private String canonicalName;
    /** Читает canonicalName. */
    public String getCanonicalName() { return canonicalName; }
    /** Изменяет canonicalName в черновике. */
    public void setCanonicalName(String value) { this.canonicalName=value; }
    /** Значение status. */
    @Column(name="status")
    private String status;
    /** Читает status. */
    public String getStatus() { return status; }
    /** Изменяет status в черновике. */
    public void setStatus(String value) { this.status=value; }
    /** Значение latitude. */
    @Column(name="latitude")
    private BigDecimal latitude;
    /** Читает latitude. */
    public BigDecimal getLatitude() { return latitude; }
    /** Изменяет latitude в черновике. */
    public void setLatitude(BigDecimal value) { this.latitude=value; }
    /** Значение longitude. */
    @Column(name="longitude")
    private BigDecimal longitude;
    /** Читает longitude. */
    public BigDecimal getLongitude() { return longitude; }
    /** Изменяет longitude в черновике. */
    public void setLongitude(BigDecimal value) { this.longitude=value; }
}
