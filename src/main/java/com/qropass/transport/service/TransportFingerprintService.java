package com.qropass.transport.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Каноническая бинарная сериализация Fingerprint Contract v1.0. */
@Service
public final class TransportFingerprintService {
    /** Остановочное вхождение в неизменяемом структурном снимке. */
    public record Occurrence(UUID id, UUID stopId, int sequence, boolean pickup, boolean dropoff, Long distanceMeters) {}
    /** Тарифный участок в неизменяемом структурном снимке. */
    public record Section(UUID id, UUID startId, UUID endId, int sequence) {}
    /** Структура версии; порядок коллекций не влияет на результат. */
    public record Snapshot(UUID legalEntityId, UUID routeId, UUID directionId, UUID patternId, UUID versionId,
                           List<Occurrence> occurrences, List<Section> sections) {}

    /** Возвращает канонические байты в строго утверждённом порядке. */
    public byte[] canonicalBytes(Snapshot source) {
        if (source == null) throw new IllegalArgumentException("Не задан снимок");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write("QRP-TRANSPORT-FP".getBytes(StandardCharsets.US_ASCII));
            out.writeByte(1);
            uuid(out, source.legalEntityId());
            uuid(out, source.routeId());
            uuid(out, source.directionId());
            uuid(out, source.patternId());
            uuid(out, source.versionId());
            List<Occurrence> stops = List.copyOf(source.occurrences()).stream()
                .sorted(Comparator.comparingInt(Occurrence::sequence)
                    .thenComparing(Occurrence::id, TransportFingerprintService::compareUuid))
                .toList();
            out.writeInt(stops.size());
            for (Occurrence stop : stops) {
                uuid(out,stop.id()); uuid(out,stop.stopId());
                out.writeInt(stop.sequence());
                out.writeByte(stop.pickup() ? 1 : 0);
                out.writeByte(stop.dropoff() ? 1 : 0);
                if (stop.distanceMeters() == null) out.writeByte(0);
                else { out.writeByte(1); out.writeLong(stop.distanceMeters()); }
            }
            List<Section> sections = List.copyOf(source.sections()).stream()
                .sorted(Comparator.comparingInt(Section::sequence)
                    .thenComparing(Section::id, TransportFingerprintService::compareUuid))
                .toList();
            out.writeInt(sections.size());
            for (Section section : sections) {
                uuid(out,section.id()); uuid(out,section.startId()); uuid(out,section.endId());
                out.writeInt(section.sequence());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Ошибка сериализации транспортного снимка",ex);
        }
    }

    /** SHA-256 с префиксом sha256: и шестнадцатеричным регистром lowercase. */
    public String fingerprint(Snapshot source) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalBytes(source)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 недоступен",ex);
        }
    }

    private static void uuid(DataOutputStream out,UUID value) throws IOException {
        if (value == null) throw new IllegalArgumentException("Отсутствует обязательный UUID");
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }
    private static int compareUuid(UUID a,UUID b) {
        int first=Long.compareUnsigned(a.getMostSignificantBits(),b.getMostSignificantBits());
        return first != 0 ? first : Long.compareUnsigned(a.getLeastSignificantBits(),b.getLeastSignificantBits());
    }
}
