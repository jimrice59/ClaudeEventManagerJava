package com.eventmanager.grpcserver;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import java.util.Set;

/**
 * Runs the mapped REST DTO (e.g. a {@code CreateVenueRequest} gRPC message mapped to a
 * {@code VenueDto}) through the same Bean Validation constraints {@code @Valid} enforces at the
 * REST controller boundary ({@code @NotBlank}, {@code @Positive}, {@code @DecimalMin}, etc. on
 * {@code VenueDto}/{@code PerformerDto}/{@code EventRequest}). proto3 has no field-validation
 * mechanism of its own, so this is the gRPC-side replacement for {@code @Valid} — without it, a
 * malformed create/update request would reach {@code VenueService}/{@code PerformerService}/
 * {@code EventService} unchecked, since those services trust the DTO is already validated (the
 * same trust `@Valid` earns on the REST path).
 */
final class GrpcValidationSupport {

    private GrpcValidationSupport() {
    }

    /** Throws {@link ConstraintViolationException} (mapped to {@code INVALID_ARGUMENT} by {@link GrpcExceptionMapper}) if invalid. */
    static <T> void validate(Validator validator, T dto) {
        Set<ConstraintViolation<T>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
