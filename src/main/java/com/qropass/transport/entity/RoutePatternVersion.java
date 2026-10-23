package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность RoutePatternVersion. */
@Entity
@Table(name="route_pattern_version")
public class RoutePatternVersion {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected RoutePatternVersion() {}
    /** Создаёт новый черновой объект. */
    public static RoutePatternVersion create() { return new RoutePatternVersion(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Ревизия черновика для защиты конкурентных изменений. */
    @Version
    @Column(name="lock_version", nullable=false)
    private Long lockVersion = 0L;
    /** Читает текущую ревизию. */
    public Long getLockVersion() { return lockVersion; }
    /** Значение pattern. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="pattern_id")
    private RoutePattern pattern;
    /** Читает pattern. */
    public RoutePattern getPattern() { return pattern; }
    /** Изменяет pattern в черновике. */
    public void setPattern(RoutePattern value) { this.pattern=value; }
    /** Значение legalEntity. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="legal_entity_id")
    private LegalEntity legalEntity;
    /** Читает legalEntity. */
    public LegalEntity getLegalEntity() { return legalEntity; }
    /** Изменяет legalEntity в черновике. */
    public void setLegalEntity(LegalEntity value) { this.legalEntity=value; }
    /** Значение versionNumber. */
    @Column(name="version_number")
    private Integer versionNumber;
    /** Читает versionNumber. */
    public Integer getVersionNumber() { return versionNumber; }
    /** Изменяет versionNumber в черновике. */
    public void setVersionNumber(Integer value) { this.versionNumber=value; }
    /** Значение status. */
    @Enumerated(EnumType.STRING)
    @Column(name="status")
    private TransportVersionStatus status;
    /** Читает status. */
    public TransportVersionStatus getStatus() { return status; }
    /** Изменяет status в черновике. */
    public void setStatus(TransportVersionStatus value) { this.status=value; }
    /** Значение contentFingerprint. */
    @Column(name="content_fingerprint")
    private String contentFingerprint;
    /** Читает contentFingerprint. */
    public String getContentFingerprint() { return contentFingerprint; }
    /** Изменяет contentFingerprint в черновике. */
    public void setContentFingerprint(String value) { this.contentFingerprint=value; }
    /** Значение effectiveFrom. */
    @Column(name="effective_from")
    private Instant effectiveFrom;
    /** Читает effectiveFrom. */
    public Instant getEffectiveFrom() { return effectiveFrom; }
    /** Изменяет effectiveFrom в черновике. */
    public void setEffectiveFrom(Instant value) { this.effectiveFrom=value; }
    /** Значение effectiveUntil. */
    @Column(name="effective_until")
    private Instant effectiveUntil;
    /** Читает effectiveUntil. */
    public Instant getEffectiveUntil() { return effectiveUntil; }
    /** Изменяет effectiveUntil в черновике. */
    public void setEffectiveUntil(Instant value) { this.effectiveUntil=value; }
}
