package com.easytask.backend.common;

import java.util.List;

/** Contract shape for non-paginated list endpoints: {@code {"items": [...]}}. */
public record ItemsResponse<T>(List<T> items) {
}
