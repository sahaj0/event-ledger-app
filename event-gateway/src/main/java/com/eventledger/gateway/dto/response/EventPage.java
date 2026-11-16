package com.eventledger.gateway.dto.response;

import java.util.List;

/** One page of events plus the total number of events the account has (returned as the X-Total-Count header). */
public record EventPage(List<EventResponse> items, long total) {
}
