package org.example.jython3.control;

import java.util.Objects;

/**
 * Host-issued attestation metadata recorded when registering a custom or
 * hardware-tier {@link ControlDevice}. Attestation originates from trusted
 * host code — never from the {@link ControlDevice} implementation itself.
 */
public record HostAttestation(
        ControlRegistry.AttestedTier tier,
        String origin,
        String reviewerOrSigner
) {
    public HostAttestation {
        Objects.requireNonNull(tier, "tier");
        if (tier == ControlRegistry.AttestedTier.UNTRUSTED) {
            throw new IllegalArgumentException("HostAttestation cannot attest UNTRUSTED tier");
        }
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("attestation origin must be non-blank");
        }
        if (reviewerOrSigner == null || reviewerOrSigner.isBlank()) {
            throw new IllegalArgumentException("attestation reviewerOrSigner must be non-blank");
        }
    }

    public static HostAttestation trustedHost(String origin, String reviewer) {
        return new HostAttestation(ControlRegistry.AttestedTier.TRUSTED_HOST, origin, reviewer);
    }

    public static HostAttestation hardware(String origin, String signer) {
        return new HostAttestation(ControlRegistry.AttestedTier.HARDWARE, origin, signer);
    }
}
