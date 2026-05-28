package com.eventledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Standard error response envelope")
public final class ErrorResponse {

    @Schema(example = "404")
    private int status;

    @Schema(example = "Not Found")
    private String error;

    @Schema(example = "Event not found with id: evt-999")
    private String message;

    @Schema(example = "2026-05-15T14:02:11Z")
    private Instant timestamp;

    @Schema(description = "Field-level validation errors (only present on 400 responses)")
    private Map<String, String> fieldErrors;
}
