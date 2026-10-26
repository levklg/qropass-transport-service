package com.qropass.transport.catalog.security;

import com.qropass.grpc.panel.security.v1.AuthorizeResponse;
import com.qropass.grpc.panel.security.v1.CatalogOperation;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Двойная авторизация: локальная Ed25519/cnf проверка, затем обязательная
 * Validate Delegation в Core. Никогда не выводит права из operator_assignment.
 */
public final class CatalogDelegationAuthorizer {
    private final DelegationTokenVerifier verifier;
    private final CoreDelegationValidation core;
    /** Для подключения нужен проверенный TLS peer и доступный Core. */
    public CatalogDelegationAuthorizer(DelegationTokenVerifier verifier,CoreDelegationValidation core) {
        this.verifier=java.util.Objects.requireNonNull(verifier);
        this.core=java.util.Objects.requireNonNull(core);
    }
    /** Возвращает только пересечение подтверждённых Core полномочий и токена. */
    public Scope authorize(String token,CatalogOperation operation,GatewayTlsPeer.Verified gateway,
                           UUID requiredOrganization,boolean requestPublished,boolean requestDrafts) {
        if(operation==null || operation==CatalogOperation.CATALOG_OPERATION_UNSPECIFIED)
            throw Status.PERMISSION_DENIED.withDescription("Неизвестная операция").asRuntimeException();
        Map<String,Object> claims=verifier.verify(token,operation.name(),gateway);
        List<UUID> signedOrganizations=readUuids(claims.get("legalEntityUuids"));
        if(signedOrganizations.isEmpty())
            throw Status.PERMISSION_DENIED.withDescription("Пустая подписанная область").asRuntimeException();
        if(requiredOrganization!=null && !signedOrganizations.contains(requiredOrganization))
            throw Status.PERMISSION_DENIED.withDescription("Чужая организация").asRuntimeException();
        if(!(claims.get("allowPublished") instanceof Boolean signedPublished) ||
           !(claims.get("allowDrafts") instanceof Boolean signedDrafts))
            throw Status.PERMISSION_DENIED.withDescription("Неизвестные полномочия").asRuntimeException();
        if(requestPublished && !signedPublished || requestDrafts && !signedDrafts)
            throw Status.PERMISSION_DENIED.withDescription("Делегирование не включает данные").asRuntimeException();
        List<UUID> requested=requiredOrganization==null?signedOrganizations:List.of(requiredOrganization);
        AuthorizeResponse decision=core.validate(token,operation,requested,requestPublished,requestDrafts,gateway);
        List<UUID> authorized=readUuids(decision.getAuthorizedLegalEntityUuidsList());
        if(!signedOrganizations.containsAll(authorized) ||
           !authorized.containsAll(requested) ||
           (requestPublished && !decision.getAllowPublished()) ||
           (requestDrafts && !decision.getAllowDrafts()))
            throw Status.PERMISSION_DENIED.withDescription("Несоответствие области Core и JWS").asRuntimeException();
        // Статусы PUBLISHED и DRAFT проверяются независимо; ROLES_VIEW
        // и ROUTE_DRAFT_VIEW рассчитываются Core в Validate Delegation.
        return new Scope(Set.copyOf(authorized),
            signedPublished && decision.getAllowPublished(),
            signedDrafts && decision.getAllowDrafts());
    }
    private static List<UUID> readUuids(Object raw) {
        if(!(raw instanceof List<?> list)) throw Status.PERMISSION_DENIED.asRuntimeException();
        List<UUID> result=new ArrayList<>();
        try {
            for(Object value:list) {
                if(!(value instanceof String s)) throw new IllegalArgumentException();
                UUID uuid=UUID.fromString(s);
                if(result.contains(uuid)) throw new IllegalArgumentException();
                result.add(uuid);
            }
        } catch(RuntimeException ex) {
            throw Status.PERMISSION_DENIED.withDescription("Недействительная область организаций").asRuntimeException();
        }
        return List.copyOf(result);
    }
    /** Результат с уже подтверждённым Core организационным перечнем. */
    public record Scope(Set<UUID> organizations,boolean published,boolean drafts) {
        public Scope {
            organizations=Set.copyOf(organizations);
            if(organizations.isEmpty() || !published && !drafts) throw new IllegalArgumentException("Пустая область");
        }
        /** Для сверки с SQL: никакого глобального разрешения при пустом наборе. */
        public boolean covers(UUID legal,boolean isPublished) {
            return legal!=null && organizations.contains(legal) &&
                (isPublished?published:drafts);
        }
    }
}
