package com.eventledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of transaction events")
public final class EventPageResponse {

    @Schema(description = "Events on the current page, ordered by eventTimestamp ASC")
    private List<EventResponse> events;

    @Schema(description = "Total number of events across all pages")
    private long totalElements;

    @Schema(description = "Total number of pages")
    private int totalPages;

    @Schema(description = "Current page index (0-based)")
    private int currentPage;

    @Schema(description = "Number of items per page")
    private int pageSize;

    @Schema(description = "True if this is the last page")
    private boolean last;
}
