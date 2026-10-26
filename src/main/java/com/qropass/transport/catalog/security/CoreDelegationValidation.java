package com.qropass.transport.catalog.security;

import com.google.protobuf.ByteString;
import com.qropass.grpc.panel.security.v1.*;
import io.grpc.Status;
import java.util.List;
import java.util.UUID;

/**
 * Изолированный контракт Transport -> Core.
 * В production transport identity и TLS channel должны быть проверены отдельно.
 */
public final class CoreDelegationValidation {
    /** Внедрённый клиент; без настроенного транспорта не выдаёт разрешений. */
    public interface CoreClient { AuthorizeResponse authorize(AuthorizeRequest request); }
    private final CoreClient client;
    /** Никаких default-заглушек, выдающих ALLOW. */
    public CoreDelegationValidation(CoreClient client) { this.client=java.util.Objects.requireNonNull(client); }

    /** Поле 16 получается только из Verified TLS peer. */
    public AuthorizeResponse validate(String token,CatalogOperation operation,
       List<UUID> targetLegalEntities,boolean includesPublished,boolean includesDrafts,
       GatewayTlsPeer.Verified gateway) {
        if(gateway==null || targetLegalEntities==null || targetLegalEntities.isEmpty())
            throw Status.PERMISSION_DENIED.withDescription("Недостаточная область проверки").asRuntimeException();
        AuthorizeRequest request=AuthorizeRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setAction(AuthorizationAction.VALIDATE_DELEGATION)
            .setDelegationToken(token)
            .setExpectedOperation(operation)
            .addAllTargetLegalEntityUuids(targetLegalEntities.stream().map(UUID::toString).toList())
            .setTargetIncludesPublished(includesPublished)
            .setTargetIncludesDrafts(includesDrafts)
            .setVerifiedGatewayCertificateSha256(ByteString.copyFrom(gateway.certificateSha256()))
            .build();
        try {
            AuthorizeResponse response=client.authorize(request);
            if(response==null || response.getDecision()!=AuthorizationDecision.ALLOW ||
                response.getOperation()!=operation)
                throw Status.PERMISSION_DENIED.withDescription("Core отказал").asRuntimeException();
            return response;
        } catch(io.grpc.StatusRuntimeException ex) {
            throw ex;
        } catch(RuntimeException ex) {
            throw Status.UNAVAILABLE.withDescription("Core Validate Delegation недоступен").asRuntimeException();
        }
    }
}
