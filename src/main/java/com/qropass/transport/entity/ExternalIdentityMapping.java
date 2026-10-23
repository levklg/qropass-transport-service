package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность ExternalIdentityMapping. */
@Entity
@Table(name="external_identity_mapping")
public class ExternalIdentityMapping {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected ExternalIdentityMapping() {}
    /** Создаёт новый черновой объект. */
    public static ExternalIdentityMapping create() { return new ExternalIdentityMapping(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Значение sourceSystem. */
    @Column(name="source_system")
    private String sourceSystem;
    /** Читает sourceSystem. */
    public String getSourceSystem() { return sourceSystem; }
    /** Изменяет sourceSystem в черновике. */
    public void setSourceSystem(String value) { this.sourceSystem=value; }
    /** Значение sourceEntityType. */
    @Column(name="source_entity_type")
    private String sourceEntityType;
    /** Читает sourceEntityType. */
    public String getSourceEntityType() { return sourceEntityType; }
    /** Изменяет sourceEntityType в черновике. */
    public void setSourceEntityType(String value) { this.sourceEntityType=value; }
    /** Значение sourceLocalId. */
    @Column(name="source_local_id")
    private String sourceLocalId;
    /** Читает sourceLocalId. */
    public String getSourceLocalId() { return sourceLocalId; }
    /** Изменяет sourceLocalId в черновике. */
    public void setSourceLocalId(String value) { this.sourceLocalId=value; }
    /** Значение targetEntityType. */
    @Column(name="target_entity_type")
    private String targetEntityType;
    /** Читает targetEntityType. */
    public String getTargetEntityType() { return targetEntityType; }
    /** Изменяет targetEntityType в черновике. */
    public void setTargetEntityType(String value) { this.targetEntityType=value; }
    /** Значение targetUuid. */
    @Column(name="target_uuid")
    private UUID targetUuid;
    /** Читает targetUuid. */
    public UUID getTargetUuid() { return targetUuid; }
    /** Изменяет targetUuid в черновике. */
    public void setTargetUuid(UUID value) { this.targetUuid=value; }
    /** Значение status. */
    @Enumerated(EnumType.STRING)
    @Column(name="status")
    private ExternalMappingStatus status;
    /** Читает status. */
    public ExternalMappingStatus getStatus() { return status; }
    /** Изменяет status в черновике. */
    public void setStatus(ExternalMappingStatus value) { this.status=value; }
}
