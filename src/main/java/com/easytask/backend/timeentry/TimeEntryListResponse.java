package com.easytask.backend.timeentry;

import java.math.BigDecimal;
import java.util.List;

/** Contract list shape: items + totals. */
public record TimeEntryListResponse(
        List<TimeEntryResponse> items,
        BigDecimal totalLoggedHours,
        BigDecimal estimatedHours
) {
}
