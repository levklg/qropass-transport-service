package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность FareSection. */
@Entity
@Table(name="fare_section")
public class FareSection {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected FareSection() {}
    /** Создаёт новый черновой объект. */
    public static FareSection create() { return new FareSection(); }
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
    /** Значение startOccurrence. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="start_occurrence_id")
    private StopOccurrence startOccurrence;
    /** Читает startOccurrence. */
    public StopOccurrence getStartOccurrence() { return startOccurrence; }
    /** Изменяет startOccurrence в черновике. */
    public void setStartOccurrence(StopOccurrence value) { this.startOccurrence=value; }
    /** Значение endOccurrence. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="end_occurrence_id")
    private StopOccurrence endOccurrence;
    /** Читает endOccurrence. */
    public StopOccurrence getEndOccurrence() { return endOccurrence; }
    /** Изменяет endOccurrence в черновике. */
    public void setEndOccurrence(StopOccurrence value) { this.endOccurrence=value; }
    /** Значение sequenceNumber. */
    @Column(name="sequence_number")
    private Integer sequenceNumber;
    /** Читает sequenceNumber. */
    public Integer getSequenceNumber() { return sequenceNumber; }
    /** Изменяет sequenceNumber в черновике. */
    public void setSequenceNumber(Integer value) { this.sequenceNumber=value; }
}
