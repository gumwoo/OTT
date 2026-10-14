package dev.gumwoo.ott.qoe;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record QoeReport(@NotNull UUID clientEventId, @NotNull UUID sessionId, @NotNull Kind kind,
		@PositiveOrZero Integer startupMs, @PositiveOrZero long watchedMs, @PositiveOrZero int positionSec,
		@PositiveOrZero long rebufferMs, @PositiveOrZero int rebufferCount, @PositiveOrZero int levelSwitches,
		@Size(max = 20) List<@NotNull @Size(max = 200) String> errors, @NotNull Instant measuredAt) {

	public enum Kind {

		PERIODIC, FINAL

	}

}
