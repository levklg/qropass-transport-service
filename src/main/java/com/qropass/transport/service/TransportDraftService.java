package com.qropass.transport.service;

import com.qropass.transport.entity.*;
import com.qropass.transport.model.TransportVersionStatus;
import com.qropass.transport.repository.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/** Создание черновика версии движения без права публикации. */
@Service
public class TransportDraftService {
    private final RoutePatternRepository patterns;
    private final LegalEntityRepository legalEntities;
    private final RoutePatternVersionRepository versions;
    private final StopOccurrenceRepository occurrences;
    private final FareSectionRepository sections;
    private final StopRepository stops;
    private final OperatorAssignmentRepository assignments;

    /** Внедряет репозитории транспортного справочника. */
    public TransportDraftService(RoutePatternRepository patterns, LegalEntityRepository legalEntities,
      RoutePatternVersionRepository versions, StopOccurrenceRepository occurrences,
      FareSectionRepository sections, StopRepository stops, OperatorAssignmentRepository assignments) {
      this.patterns=patterns; this.legalEntities=legalEntities; this.versions=versions;
      this.occurrences=occurrences; this.sections=sections; this.stops=stops; this.assignments=assignments;
    }

    /** Создаёт черновик с новой идентичностью версии. */
    @Transactional
    public RoutePatternVersion createDraft(UUID patternId, UUID legalEntityId, int number) {
      if(number <= 0) throw new IllegalArgumentException("Номер версии должен быть положительным");
      RoutePattern pattern=patterns.findById(patternId).orElseThrow();
      if (!assignments.existsByRoute_IdAndLegalEntity_Id(pattern.getDirection().getRoute().getId(),legalEntityId))
        throw new IllegalArgumentException("Юридическое лицо не связано с маршрутом через назначение");
      RoutePatternVersion version=RoutePatternVersion.create();
      version.setPattern(pattern);
      version.setLegalEntity(legalEntities.findById(legalEntityId).orElseThrow());
      version.setVersionNumber(number);
      version.setStatus(TransportVersionStatus.DRAFT);
      return versions.save(version);
    }

    /** Меняет описание черновика с обязательным ожидаемым номером ревизии. */
    @Transactional
    public RoutePatternVersion updateDraftEffectiveFrom(UUID versionId, long expectedRevision, java.time.Instant instant) {
      RoutePatternVersion version=requiredDraft(versionId);
      if (!version.getLockVersion().equals(expectedRevision))
        throw new ObjectOptimisticLockingFailureException(RoutePatternVersion.class, versionId);
      version.setEffectiveFrom(instant);
      return versions.saveAndFlush(version);
    }

    /** Добавляет остановку в черновую схему; повторный stopId допустим. */
    @Transactional
    public StopOccurrence addStop(UUID versionId,UUID stopId,int sequence,boolean pickup,boolean dropoff,Long distance) {
      if(sequence <= 0 || (distance != null && distance < 0))
        throw new IllegalArgumentException("Недопустимый порядок или расстояние");
      RoutePatternVersion version=requiredDraft(versionId);
      StopOccurrence occurrence=StopOccurrence.create();
      occurrence.setPatternVersion(version);
      occurrence.setStop(stops.findById(stopId).orElseThrow());
      occurrence.setSequenceNumber(sequence);
      occurrence.setPickupAllowed(pickup);
      occurrence.setDropoffAllowed(dropoff);
      occurrence.setDistanceMeters(distance);
      return occurrences.save(occurrence);
    }

    /** Создаёт участок только между вхождениями той же черновой версии. */
    @Transactional
    public FareSection addSection(UUID versionId,UUID startId,UUID endId,int sequence) {
      if(sequence <= 0) throw new IllegalArgumentException("Номер участка должен быть положительным");
      RoutePatternVersion version=requiredDraft(versionId);
      StopOccurrence start=occurrences.findById(startId).orElseThrow();
      StopOccurrence end=occurrences.findById(endId).orElseThrow();
      if(!start.getPatternVersion().getId().equals(versionId)
          || !end.getPatternVersion().getId().equals(versionId)
          || start.getSequenceNumber() >= end.getSequenceNumber())
        throw new IllegalArgumentException("Недопустимые границы тарифного участка");
      FareSection section=FareSection.create();
      section.setPatternVersion(version);
      section.setStartOccurrence(start);
      section.setEndOccurrence(end);
      section.setSequenceNumber(sequence);
      return sections.save(section);
    }

    /** Изменяет расстояние существующего вхождения черновика. */
    @Transactional
    public StopOccurrence changeDistance(UUID occurrenceId, Long distance) {
      if(distance != null && distance < 0) throw new IllegalArgumentException("Отрицательное расстояние");
      StopOccurrence occurrence=occurrences.findById(occurrenceId).orElseThrow();
      requiredDraft(occurrence.getPatternVersion().getId());
      occurrence.setDistanceMeters(distance);
      return occurrences.save(occurrence);
    }

    private RoutePatternVersion requiredDraft(UUID versionId) {
      RoutePatternVersion version=versions.findById(versionId).orElseThrow();
      if(version.getStatus()!=TransportVersionStatus.DRAFT)
        throw new IllegalStateException("Опубликованную версию изменять запрещено");
      return version;
    }
}
