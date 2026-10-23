package com.qropass.transport.entity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import com.qropass.transport.model.*;

/** Транспортная сущность LegalEntity. */
@Entity
@Table(name="legal_entity")
public class LegalEntity {
    /** Постоянный UUID. */
    @Id
    @Column(name="id", nullable=false, updatable=false)
    private UUID id = UUID.randomUUID();
    /** Конструктор для JPA. */
    protected LegalEntity() {}
    /** Создаёт новый черновой объект. */
    public static LegalEntity create() { return new LegalEntity(); }
    /** Возвращает UUID. */
    public UUID getId() { return id; }
    /** Значение legalName. */
    @Column(name="legal_name")
    private String legalName;
    /** Читает legalName. */
    public String getLegalName() { return legalName; }
    /** Изменяет legalName в черновике. */
    public void setLegalName(String value) { this.legalName=value; }
    /** Значение inn. */
    @Column(name="inn")
    private String inn;
    /** Читает inn. */
    public String getInn() { return inn; }
    /** Изменяет inn в черновике. */
    public void setInn(String value) { this.inn=value; }
    /** Значение ogrn. */
    @Column(name="ogrn")
    private String ogrn;
    /** Читает ogrn. */
    public String getOgrn() { return ogrn; }
    /** Изменяет ogrn в черновике. */
    public void setOgrn(String value) { this.ogrn=value; }
    /** Значение status. */
    @Column(name="status")
    private String status;
    /** Читает status. */
    public String getStatus() { return status; }
    /** Изменяет status в черновике. */
    public void setStatus(String value) { this.status=value; }
}
