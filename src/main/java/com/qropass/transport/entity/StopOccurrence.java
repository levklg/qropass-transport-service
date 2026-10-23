package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность StopOccurrence. */
@Entity
@Table(name="stop_occurrence")
public class StopOccurrence {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected StopOccurrence() {}
    /** Создаёт новый черновой объект. */
    public static StopOccurrence create() { return new StopOccurrence(); }
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
    /** Значение stop. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="stop_id")
    private Stop stop;
    /** Читает stop. */
    public Stop getStop() { return stop; }
    /** Изменяет stop в черновике. */
    public void setStop(Stop value) { this.stop=value; }
    /** Значение sequenceNumber. */
    @Column(name="sequence_number")
    private Integer sequenceNumber;
    /** Читает sequenceNumber. */
    public Integer getSequenceNumber() { return sequenceNumber; }
    /** Изменяет sequenceNumber в черновике. */
    public void setSequenceNumber(Integer value) { this.sequenceNumber=value; }
    /** Значение pickupAllowed. */
    @Column(name="pickup_allowed")
    private Boolean pickupAllowed;
    /** Читает pickupAllowed. */
    public Boolean getPickupAllowed() { return pickupAllowed; }
    /** Изменяет pickupAllowed в черновике. */
    public void setPickupAllowed(Boolean value) { this.pickupAllowed=value; }
    /** Значение dropoffAllowed. */
    @Column(name="dropoff_allowed")
    private Boolean dropoffAllowed;
    /** Читает dropoffAllowed. */
    public Boolean getDropoffAllowed() { return dropoffAllowed; }
    /** Изменяет dropoffAllowed в черновике. */
    public void setDropoffAllowed(Boolean value) { this.dropoffAllowed=value; }
    /** Значение distanceMeters. */
    @Column(name="distance_meters")
    private Long distanceMeters;
    /** Читает distanceMeters. */
    public Long getDistanceMeters() { return distanceMeters; }
    /** Изменяет distanceMeters в черновике. */
    public void setDistanceMeters(Long value) { this.distanceMeters=value; }
}
