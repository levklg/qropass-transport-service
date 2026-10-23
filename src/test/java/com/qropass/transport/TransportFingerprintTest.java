package com.qropass.transport;

import com.qropass.transport.service.TransportFingerprintService;
import com.qropass.transport.service.TransportFingerprintService.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Эталонные векторы Fingerprint Contract v1.0. */
class TransportFingerprintTest {
    private final TransportFingerprintService service=new TransportFingerprintService();
    private static UUID id(int tail) { return UUID.fromString(String.format("00000000-0000-0000-0000-%012d",tail)); }
    private Snapshot snapshot(Long first,Long second,boolean pickup,boolean section) {
        return new Snapshot(id(1),id(2),id(3),id(4),id(5),
            List.of(new Occurrence(id(11),id(21),1,true,false,first),
                    new Occurrence(id(12),id(22),2,pickup,true,second)),
            section?List.of(new Section(id(31),id(11),id(12),1)):List.of());
    }
    private void assertVector(Snapshot s,int length,String hash) {
        assertEquals(length,service.canonicalBytes(s).length);
        assertEquals("sha256:"+hash,service.fingerprint(s));
    }
    /** Вектор A. */
    @Test void vectorA() {
        assertVector(snapshot(null,null,false,false),183,"a1d6d184068cbfb15db118c8e205764b9fa5685b895b6a66bbf54551540329d1");
    }
    /** Вектор B. */
    @Test void vectorB() {
        assertVector(snapshot(0L,1250L,false,false),199,"eaaebdfc88911f3d25477d3b3f91eb8a9fbb063faf151b7ec78a6bf0309b026d");
    }
    /** Вектор C. */
    @Test void vectorC() {
        assertVector(snapshot(null,null,false,true),235,"02c725ea6f023f6120d586e10bee96ea9570ca7d8e7779e35d10c33e1080bb70");
    }
    /** Вектор D. */
    @Test void vectorD() {
        assertVector(snapshot(null,null,true,false),183,"a4753733c0bd59306275ddd7648f138233521caf29bbf374786acf105590ff74");
    }
    /** Сортировка не зависит от порядка загрузки записей. */
    @Test void orderIndependent() {
        Snapshot a=snapshot(null,null,false,true);
        Snapshot b=new Snapshot(a.legalEntityId(),a.routeId(),a.directionId(),a.patternId(),a.versionId(),
            List.of(a.occurrences().get(1),a.occurrences().get(0)),a.sections());
        assertArrayEquals(service.canonicalBytes(a),service.canonicalBytes(b));
    }
    /** Структурно значимые поля изменяют fingerprint. */
    @Test void structuralMutation() {
        Snapshot a=snapshot(null,null,false,true);
        Snapshot changedRoute=new Snapshot(a.legalEntityId(),id(90),a.directionId(),a.patternId(),a.versionId(),a.occurrences(),a.sections());
        Snapshot changedStop=new Snapshot(a.legalEntityId(),a.routeId(),a.directionId(),a.patternId(),a.versionId(),
            List.of(new Occurrence(id(11),id(91),1,true,false,null),a.occurrences().get(1)),a.sections());
        assertNotEquals(service.fingerprint(a),service.fingerprint(changedRoute));
        assertNotEquals(service.fingerprint(a),service.fingerprint(changedStop));
        assertNotEquals(service.fingerprint(a),service.fingerprint(snapshot(null,null,true,true)));
        assertNotEquals(service.fingerprint(a),service.fingerprint(snapshot(null,null,false,false)));
    }
}
