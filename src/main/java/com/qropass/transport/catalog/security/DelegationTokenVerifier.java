package com.qropass.transport.catalog.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Локальная криптографическая проверка короткоживущего JWS Core. */
public final class DelegationTokenVerifier {
    /** Публичные ключи Core задаются доверенной конфигурацией, не клиентом. */
    public interface TrustedKeyResolver { PublicKey resolve(String kid); }
    private final TrustedKeyResolver keys;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();

    /** Не допускает отсутствующих ключей или часов. */
    public DelegationTokenVerifier(TrustedKeyResolver keys,Clock clock) {
        this.keys=java.util.Objects.requireNonNull(keys);
        this.clock=java.util.Objects.requireNonNull(clock);
    }
    /** Проверяет подпись Ed25519 и привязку к текущему Gateway mTLS peer. */
    public Map<String,Object> verify(String token,String operation,GatewayTlsPeer.Verified peer) {
        try {
            if(token==null || token.length()>16384 || peer==null || operation==null)
                throw new SecurityException("Отсутствует делегирование");
            String[] chunks=token.split("\\.",-1);
            if(chunks.length!=3 || chunks[0].length()>2048 || chunks[1].length()>12000 || chunks[2].length()!=86)
                throw new SecurityException("Неверная структура JWS");
            for(String chunk:chunks) {
                if(!chunk.matches("[A-Za-z0-9_-]+") ||
                    !ENCODER.encodeToString(DECODER.decode(chunk)).equals(chunk))
                    throw new SecurityException("Неканонический JWS");
            }
            Map<String,Object> head=json.readValue(DECODER.decode(chunks[0]),new TypeReference<>() {});
            if(!head.keySet().equals(Set.of("alg","typ","kid")) ||
                !"EdDSA".equals(head.get("alg")) || !"JWT".equals(head.get("typ")) ||
                !(head.get("kid") instanceof String kid) || kid.isBlank())
                throw new SecurityException("Недопустимый заголовок");
            PublicKey key=keys.resolve(kid);
            if(key==null || !Set.of("Ed25519","EdDSA").contains(key.getAlgorithm()))
                throw new SecurityException("Недоверенный kid");
            Signature signature=Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update((chunks[0]+"."+chunks[1]).getBytes(StandardCharsets.US_ASCII));
            if(!signature.verify(DECODER.decode(chunks[2])))
                throw new SecurityException("Неправильная подпись");
            Map<String,Object> claims=json.readValue(DECODER.decode(chunks[1]),new TypeReference<>() {});
            if(!"qropass-core".equals(claims.get("iss")) ||
               !"qropass-transport-catalog".equals(claims.get("aud")) ||
               !operation.equals(claims.get("operation")))
                throw new SecurityException("Нарушена область делегирования");
            long now=clock.instant().getEpochSecond();
            long iat=number(claims.get("iat")),nbf=number(claims.get("nbf")),exp=number(claims.get("exp"));
            if(iat>now || nbf>now || nbf<iat || exp<=now || exp<=iat || exp-iat>60)
                throw new SecurityException("Недействительный TTL");
            if(!(claims.get("cnf") instanceof Map<?,?> cnf) ||
                !(cnf.get("x5t#S256") instanceof String encoded) ||
                !MessageDigest.isEqual(ENCODER.encodeToString(peer.certificateSha256()).getBytes(StandardCharsets.US_ASCII),
                    encoded.getBytes(StandardCharsets.US_ASCII)))
                throw new SecurityException("Неверная cnf привязка");
            for(String keyName:ListKeys.REQUIRED)
                if(!claims.containsKey(keyName)) throw new SecurityException("Отсутствует обязательное поле "+keyName);
            return Map.copyOf(claims);
        } catch(Exception ex) {
            throw Status.PERMISSION_DENIED.withDescription("Делегирование не подтверждено").asRuntimeException();
        }
    }
    private static long number(Object value) {
        if(!(value instanceof java.math.BigInteger n)) throw new SecurityException("Некорректный timestamp");
        return n.longValueExact();
    }
    private static final class ListKeys {
        static final Set<String> REQUIRED=Set.of("sub","sid","jti","accessMode","legalEntityUuids",
            "allowPublished","allowDrafts","authorizationRevision","sessionRevision","policyRevision");
    }
}
