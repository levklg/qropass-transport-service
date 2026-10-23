package com.qropass.transport.service;

import com.qropass.transport.entity.*;
import com.qropass.transport.model.*;
import com.qropass.transport.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Атомарная публикация проверенного транспортного снимка, без разрешения продаж. */
@Service
public class TransportPublicationService {
    private final RoutePatternVersionRepository versions;
    private final StopOccurrenceRepository stops;
    private final FareSectionRepository sections;
    private final OperatorAssignmentRepository assignments;
    private final TransportVersionActivationRepository activations;
    private final TransportFingerprintService fingerprint;

    /** Конструктор зависимостей публикации. */
    public TransportPublicationService(RoutePatternVersionRepository versions,
       StopOccurrenceRepository stops, FareSectionRepository sections,
       OperatorAssignmentRepository assignments,
       TransportVersionActivationRepository activations,TransportFingerprintService fingerprint) {
       this.versions=versions;this.stops=stops;this.sections=sections;
       this.assignments=assignments;this.activations=activations;this.fingerprint=fingerprint;
    }

    /** Публикует черновик, проверяя актуальные данные внутри эксклюзивной транзакции. */
    @Transactional
    public String publish(UUID versionId) { return publish(versionId,null,null); }

    /**
     * Публикует или идемпотентно возвращает прежний hash.
     * Ожидаемая ревизия и ожидаемый hash могут быть переданы вызывающим сервисом.
     */
    @Transactional
    public String publish(UUID versionId, Long expectedRevision, String expectedFingerprint) {
        RoutePatternVersion version=versions.lockForPublication(versionId)
            .orElseThrow(()->new PublicationConflictException("Транспортная версия не найдена"));
        if(version.getStatus()==TransportVersionStatus.PUBLISHED) {
            if(version.getContentFingerprint()==null ||
               (expectedFingerprint!=null && !expectedFingerprint.equals(version.getContentFingerprint())))
                throw new PublicationConflictException("Конфликт опубликованного fingerprint");
            return version.getContentFingerprint();
        }
        if(version.getStatus()!=TransportVersionStatus.DRAFT)
            throw new PublicationConflictException("Недопустимое состояние для публикации");
        if(expectedRevision!=null && !expectedRevision.equals(version.getLockVersion()))
            throw new PublicationConflictException("Черновик изменён другим редактором");

        RoutePattern pattern=version.getPattern();
        RouteDirection direction=pattern.getDirection();
        Route route=direction.getRoute();
        LegalEntity legalEntity=version.getLegalEntity();
        if(!assignments.existsByRoute_IdAndLegalEntity_Id(route.getId(),legalEntity.getId()))
            throw new PublicationConflictException("Отсутствует назначение юридического лица на маршрут");

        List<StopOccurrence> orderedStops=stops.findByPatternVersion_Id(versionId).stream()
          .sorted(Comparator.comparingInt(StopOccurrence::getSequenceNumber)).toList();
        if(orderedStops.size()<2)
            throw new PublicationConflictException("Для публикации необходимо минимум две остановки");
        long lastDistance=-1;
        Set<UUID> stopIds=new HashSet<>();
        Map<UUID,Integer> positions=new HashMap<>();
        List<TransportFingerprintService.Occurrence> occurrenceData=new ArrayList<>();
        for(int i=0;i<orderedStops.size();i++) {
            StopOccurrence stop=orderedStops.get(i);
            if(stop.getSequenceNumber()==null || stop.getSequenceNumber()!=i+1
                || stop.getPickupAllowed()==null || stop.getDropoffAllowed()==null)
                throw new PublicationConflictException("Неверная последовательность или признаки остановок");
            if(!stop.getPickupAllowed() && !stop.getDropoffAllowed())
                throw new PublicationConflictException("Посадка и высадка одновременно запрещены");
            Long distance=stop.getDistanceMeters();
            if(distance!=null) {
                if(distance<0 || distance<lastDistance)
                    throw new PublicationConflictException("Накопленные расстояния не возрастают");
                lastDistance=distance;
            }
            stopIds.add(stop.getId());
            positions.put(stop.getId(),i+1);
            occurrenceData.add(new TransportFingerprintService.Occurrence(stop.getId(),stop.getStop().getId(),
                stop.getSequenceNumber(),stop.getPickupAllowed(),stop.getDropoffAllowed(),distance));
        }
        if(!orderedStops.get(0).getPickupAllowed() || !orderedStops.get(orderedStops.size()-1).getDropoffAllowed())
            throw new PublicationConflictException("Неверные признаки конечных остановок");

        List<FareSection> orderedSections=sections.findByPatternVersion_Id(versionId).stream()
          .sorted(Comparator.comparingInt(FareSection::getSequenceNumber)).toList();
        List<TransportFingerprintService.Section> sectionData=new ArrayList<>();
        for(int i=0;i<orderedSections.size();i++) {
            FareSection section=orderedSections.get(i);
            UUID start=section.getStartOccurrence().getId(),end=section.getEndOccurrence().getId();
            if(section.getSequenceNumber()==null || section.getSequenceNumber()!=i+1 ||
              !positions.containsKey(start) || !positions.containsKey(end) ||
              positions.get(start)>=positions.get(end))
                throw new PublicationConflictException("Некорректный тарифный участок");
            sectionData.add(new TransportFingerprintService.Section(section.getId(),start,end,section.getSequenceNumber()));
        }
        TransportFingerprintService.Snapshot snapshot=new TransportFingerprintService.Snapshot(
            legalEntity.getId(),route.getId(),direction.getId(),pattern.getId(),version.getId(),
            occurrenceData,sectionData);
        String hash=fingerprint.fingerprint(snapshot);
        if(expectedFingerprint!=null && !expectedFingerprint.equals(hash))
            throw new PublicationConflictException("Ожидаемый fingerprint не совпадает с содержимым");

        version.setContentFingerprint(hash);
        version.setStatus(TransportVersionStatus.PUBLISHED);
        versions.saveAndFlush(version);

        TransportVersionActivation activation=activations.findByPatternVersion_Id(versionId)
           .orElseGet(TransportVersionActivation::create);
        activation.setPatternVersion(version);
        activation.setSaleStatus(TransportSaleStatus.NOT_FOR_SALE);
        activation.setUpdatedAt(Instant.now());
        activations.saveAndFlush(activation);
        return hash;
    }

    /** Открытие продаж запрещено в рамках T1.3. */
    public void enableSales(UUID versionId) {
        throw new UnsupportedOperationException("Продажи запрещены до отдельного решения");
    }
}
