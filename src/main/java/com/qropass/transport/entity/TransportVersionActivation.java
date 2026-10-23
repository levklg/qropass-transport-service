package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность TransportVersionActivation. */
@Entity
@Table(name="transport_version_activation")
public class TransportVersionActivation {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected TransportVersionActivation() {}
    /** Создаёт новый черновой объект. */
    public static TransportVersionActivation create() { return new TransportVersionActivation(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Значение patternVersion. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="pattern_version_id")
    private RoutePatternVersion patternVersion;
    /** Читает patternVersion. */
    public RoutePatternVersion getPatternVersion() { return patternVersion; }
    /** Изменяет patternVersion в черновике. */
    public void setPatternVersion(RoutePatternVersion value) { this.patternVersion=value; }
    /** Значение saleStatus. */
    @Enumerated(EnumType.STRING)
    @Column(name="sale_status")
    private TransportSaleStatus saleStatus;
    /** Читает saleStatus. */
    public TransportSaleStatus getSaleStatus() { return saleStatus; }
    /** Изменяет saleStatus в черновике. */
    public void setSaleStatus(TransportSaleStatus value) { this.saleStatus=value; }
    /** Значение updatedAt. */
    @Column(name="updated_at")
    private Instant updatedAt;
    /** Читает updatedAt. */
    public Instant getUpdatedAt() { return updatedAt; }
    /** Изменяет updatedAt в черновике. */
    public void setUpdatedAt(Instant value) { this.updatedAt=value; }
}
