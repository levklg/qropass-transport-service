package com.qropass.transport.catalog.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Строгий верификатор подписанного Core JWS v1 (только доверенные Ed25519 ключи). */
public final class DelegationTokenVerifier {
    public interface TrustedKeyResolver { PublicKey resolve(String kid); }
    private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    private static final Set<String> OPERATIONS=Set.of("LIST_ROUTES","LIST_ROUTE_PATTERNS",
        "LIST_ROUTE_PATTERN_VERSIONS","GET_ROUTE_PATTERN_VERSION","LIST_PATTERN_STOPS");
    private static final Set<String> MODES=Set.of("SINGLE_ORGANIZATION","ALL_ACCESSIBLE","PLATFORM_ADMIN");
    private static final Set<String> CLAIMS=Set.of("iss","aud","sub","sid","jti","iat","nbf","exp",
        "operation","accessMode","legalEntityUuids","allowPublished","allowDrafts",
        "authorizationRevision","sessionRevision","policyRevision","cnf");
    private final TrustedKeyResolver keys;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    public DelegationTokenVerifier(TrustedKeyResolver keys,Clock clock) {
        this.keys=java.util.Objects.requireNonNull(keys);
        this.clock=java.util.Objects.requireNonNull(clock);
    }
    public Map<String,Object> verify(String token,String operation,GatewayTlsPeer.Verified peer) {
        try {
            if(token==null || token.isEmpty() || token.length()>16384 || peer==null ||
                !OPERATIONS.contains(operation)) throw new SecurityException("Недопустимый JWS или операция");
            String[] parts=token.split("\\.",-1);
            if(parts.length!=3 || parts[0].length()>2048 || parts[1].length()>12000 ||
                parts[2].length()!=86) throw new SecurityException("Формат JWS");
            for(String part:parts) {
                if(!part.matches("[A-Za-z0-9_-]+") ||
                    !ENCODER.encodeToString(DECODER.decode(part)).equals(part))
                    throw new SecurityException("Неканонический Base64URL");
            }
            Map<String,Object> header=json.readValue(DECODER.decode(parts[0]),new TypeReference<>() {});
            if(!header.keySet().equals(Set.of("alg","typ","kid")) ||
                !"EdDSA".equals(header.get("alg")) || !"JWT".equals(header.get("typ")) ||
                !(header.get("kid") instanceof String kid) || kid.isBlank())
                throw new SecurityException("Заголовок JWS");
            PublicKey key=keys.resolve(kid);
            if(key==null || !Set.of("Ed25519","EdDSA").contains(key.getAlgorithm()))
                throw new SecurityException("Недоверенный kid");
            Signature signature=Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!signature.verify(DECODER.decode(parts[2])))
                throw new SecurityException("Неверная подпись");
            Map<String,Object> claims=json.readValue(DECODER.decode(parts[1]),new TypeReference<>() {});
            if(!claims.keySet().equals(CLAIMS) ||
                !"qropass-core".equals(claims.get("iss")) ||
                !"qropass-transport-catalog".equals(claims.get("aud")) ||
                !operation.equals(claims.get("operation")) ||
                !(claims.get("sub") instanceof String sub) || !sub.matches("[1-9][0-9]{0,17}") ||
                !(claims.get("sid") instanceof String sid) || !uuid(sid) ||
                !(claims.get("jti") instanceof String jti) || !uuid(jti) ||
                !(claims.get("accessMode") instanceof String mode) || !MODES.contains(mode) ||
                !(claims.get("allowPublished") instanceof Boolean published) ||
                !(claims.get("allowDrafts") instanceof Boolean drafts) || (!published && !drafts) ||
                !(claims.get("legalEntityUuids") instanceof List<?> organizations) ||
                organizations.isEmpty() || organizations.size()>64)
                throw new SecurityException("Неверные claims");
            Set<String> unique=new HashSet<>();
            for(Object entry:organizations)
                if(!(entry instanceof String id) || !uuid(id) || !unique.add(id.toLowerCase(java.util.Locale.ROOT)))
                    throw new SecurityException("Некорректная область организаций");
            // Не принимаем числа с плавающей точкой, отрицательные или переполненные.
            long iat=number(claims.get("iat")),nbf=number(claims.get("nbf")),exp=number(claims.get("exp"));
            number(claims.get("authorizationRevision"));
            number(claims.get("sessionRevision"));
            number(claims.get("policyRevision"));
            long now=clock.instant().getEpochSecond();
            if(iat>now || nbf>now || nbf<iat || exp<=now || exp<=iat || exp-iat>60)
                throw new SecurityException("TTL");
            if(!(claims.get("cnf") instanceof Map<?,?> cnf) ||
               cnf.size()!=1 || !(cnf.get("x5t#S256") instanceof String thumbprint) ||
               !thumbprint.matches("[A-Za-z0-9_-]{43}") ||
               !ENCODER.encodeToString(DECODER.decode(thumbprint)).equals(thumbprint) ||
               DECODER.decode(thumbprint).length!=32 ||
               !MessageDigest.isEqual(DECODER.decode(thumbprint),peer.certificateSha256()))
                throw new SecurityException("cnf не совпадает с Gateway TLS");
            return Map.copyOf(claims);
        } catch(Exception ex) {
            throw Status.PERMISSION_DENIED.withDescription("Делегирование не подтверждено").asRuntimeException();
        }
    }
    private static boolean uuid(String input) {
        if(input==null || !input.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            return false;
        try { return UUID.fromString(input).toString().equalsIgnoreCase(input); }
        catch(IllegalArgumentException ex) { return false; }
    }
    private static long number(Object value) {
        if(!(value instanceof java.math.BigInteger v)) throw new SecurityException("Неверный тип числа");
        try {
            long number=v.longValueExact();
            if(number<0) throw new SecurityException("Отрицательный claim");
            return number;
        } catch(ArithmeticException ex) {
            throw new SecurityException("Переполнение claim",ex);
        }
    }
}
