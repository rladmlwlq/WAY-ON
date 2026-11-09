package com.wayon.trip;

import java.time.LocalDateTime;

/** DB 행. 시간은 UTC */
public final class TripRows {

	private TripRows() {
	}

	public record Trip(long id, String publicId, long vehicleId, LocalDateTime createdAt) {
	}
}
