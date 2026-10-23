package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность OperatorAssignment. */
@Entity
@Table(name="operator_assignment")
public class OperatorAssignment {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected OperatorAssignment() {}
    /** Создаёт новый черновой объект. */
    public static OperatorAssignment create() { return new OperatorAssignment(); }
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
    /** Значение legalEntity. */
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="legal_entity_id")
    private LegalEntity legalEntity;
    /** Читает legalEntity. */
    public LegalEntity getLegalEntity() { return legalEntity; }
    /** Изменяет legalEntity в черновике. */
    public void setLegalEntity(LegalEntity value) { this.legalEntity=value; }
    /** Значение role. */
    @Column(name="role")
    private String role;
    /** Читает role. */
    public String getRole() { return role; }
    /** Изменяет role в черновике. */
    public void setRole(String value) { this.role=value; }
    /** Значение status. */
    @Enumerated(EnumType.STRING)
    @Column(name="status")
    private OperatorAssignmentStatus status;
    /** Читает status. */
    public OperatorAssignmentStatus getStatus() { return status; }
    /** Изменяет status в черновике. */
    public void setStatus(OperatorAssignmentStatus value) { this.status=value; }
    /** Значение validFrom. */
    @Column(name="valid_from")
    private Instant validFrom;
    /** Читает validFrom. */
    public Instant getValidFrom() { return validFrom; }
    /** Изменяет validFrom в черновике. */
    public void setValidFrom(Instant value) { this.validFrom=value; }
    /** Значение validUntil. */
    @Column(name="valid_until")
    private Instant validUntil;
    /** Читает validUntil. */
    public Instant getValidUntil() { return validUntil; }
    /** Изменяет validUntil в черновике. */
    public void setValidUntil(Instant value) { this.validUntil=value; }
    /** Значение evidenceReference. */
    @Column(name="evidence_reference")
    private String evidenceReference;
    /** Читает evidenceReference. */
    public String getEvidenceReference() { return evidenceReference; }
    /** Изменяет evidenceReference в черновике. */
    public void setEvidenceReference(String value) { this.evidenceReference=value; }
}
