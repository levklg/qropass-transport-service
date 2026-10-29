package com.qropass.transport.catalog;

import com.google.protobuf.Message;
import com.qropass.grpc.panel.security.v1.CatalogOperation;
import com.qropass.transport.grpc.v1.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** CEX-REQUEST-2: строгая проверка и независимый бинарный digest входного Catalog. */
public final class CatalogRequestCanonicalizer {
    private CatalogRequestCanonicalizer() {}
    private static final String PREFIX="qropass.transport.catalog.v1.TransportCatalogService/";
    public static UUID strictUuid(String s) {
        if(s==null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Неканонический UUID");
        try {
            UUID u=UUID.fromString(s);
            if(!u.toString().equals(s)) throw new IllegalArgumentException("Неканонический UUID");
            return u;
        } catch(IllegalArgumentException ex) { throw new IllegalArgumentException("Неканонический UUID",ex); }
    }
    private static String id(String s,boolean required) {
        if(s==null || s.isEmpty()) {
            if(required) throw new IllegalArgumentException("Отсутствует обязательный UUID");
            return null;
        }
        strictUuid(s);
        return s;
    }
    private static void noUnknown(Message m) {
        if(!m.getUnknownFields().asMap().isEmpty())
            throw new IllegalArgumentException("Неизвестные protobuf поля");
    }
    private static PageRequest page(boolean has,PageRequest p) {
        if(!has) return null;
        noUnknown(p);
        return p;
    }
    private static void validPage(PageRequest p) {
        if(p==null)return;
        if(p.getPageSize()<0 || p.getPageSize()>100 || p.getPageNumber()<0)
            throw new IllegalArgumentException("Недопустимая пагинация");
        long size=p.getPageSize()==0?20:p.getPageSize();
        Math.multiplyExact((long)p.getPageNumber(),size);
    }
    public static void validate(Message m,CatalogOperation op) {
        fields(m,op);
    }
    /** Возвращает собственный SHA-256 TLV (32 байта), не доверяет сетевому digest. */
    public static byte[] digest(Message m,CatalogOperation op) {
        Fields f=fields(m,op);
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            out.write("CEX-REQUEST-2".getBytes(StandardCharsets.US_ASCII));
            out.write(0);out.write(2);
            str(out,1,PREFIX+f.method);
            num(out,2,op.getNumber());
            str(out,3,f.legal);
            str(out,4,f.route);
            str(out,5,f.direction);
            str(out,6,f.pattern);
            str(out,7,f.version);
            str(out,8,f.status);
            num(out,9,f.page==null?null:f.page.getPageSize());
            num(out,10,f.page==null?null:f.page.getPageNumber());
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        } catch(Exception ex) { throw new IllegalStateException("Невозможно вычислить digest",ex); }
    }
    private record Fields(String method,String legal,String route,String direction,
                          String pattern,String version,String status,PageRequest page) {}
    private static Fields fields(Message m,CatalogOperation op) {
        if(m==null || op==null) throw new IllegalArgumentException("Отсутствует запрос");
        noUnknown(m);
        Fields f;
        if(m instanceof ListRoutesRequest r) {
            f=new Fields("ListRoutes",id(r.getLegalEntityId(),false),null,null,null,null,null,page(r.hasPage(),r.getPage()));
            require(op,CatalogOperation.LIST_ROUTES);
        } else if(m instanceof ListRoutePatternsRequest r) {
            f=new Fields("ListRoutePatterns",id(r.getLegalEntityId(),false),id(r.getRouteId(),false),
                id(r.getDirectionId(),false),null,null,null,page(r.hasPage(),r.getPage()));
            require(op,CatalogOperation.LIST_ROUTE_PATTERNS);
        } else if(m instanceof ListRoutePatternVersionsRequest r) {
            String status=r.getPublicationStatus();
            if(!status.isEmpty() && !status.equals("DRAFT") && !status.equals("PUBLISHED"))
                throw new IllegalArgumentException("Недопустимый статус публикации");
            f=new Fields("ListRoutePatternVersions",id(r.getLegalEntityId(),false),null,null,
                id(r.getPatternId(),true),null,status.isEmpty()?null:status,page(r.hasPage(),r.getPage()));
            require(op,CatalogOperation.LIST_ROUTE_PATTERN_VERSIONS);
        } else if(m instanceof GetRoutePatternVersionRequest r) {
            f=new Fields("GetRoutePatternVersion",null,null,null,null,id(r.getVersionId(),true),null,null);
            require(op,CatalogOperation.GET_ROUTE_PATTERN_VERSION);
        } else if(m instanceof ListPatternStopsRequest r) {
            f=new Fields("ListPatternStops",null,null,null,null,id(r.getVersionId(),true),null,page(r.hasPage(),r.getPage()));
            require(op,CatalogOperation.LIST_PATTERN_STOPS);
        } else throw new IllegalArgumentException("Неизвестный Catalog request");
        validPage(f.page);
        return f;
    }
    private static void require(CatalogOperation actual,CatalogOperation expected) {
        if(actual!=expected) throw new IllegalArgumentException("Неверная операция");
    }
    private static void str(ByteArrayOutputStream out,int tag,String val) {
        field(out,tag,val==null?null:val.getBytes(StandardCharsets.US_ASCII));
    }
    private static void num(ByteArrayOutputStream out,int tag,Integer v) {
        field(out,tag,v==null?null:ByteBuffer.allocate(4).putInt(v).array());
    }
    private static void field(ByteArrayOutputStream out,int tag,byte[] bytes) {
        out.write(tag);out.write(bytes==null?0:1);
        if(bytes!=null) {
            out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
    }
}
