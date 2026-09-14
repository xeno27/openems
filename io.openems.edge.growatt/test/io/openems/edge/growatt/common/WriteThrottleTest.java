package io.openems.edge.growatt.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.openems.common.test.TimeLeapClock;

public class WriteThrottleTest {

	@Test
	public void testFirstWriteIsAllowed() {
		var throttle = new WriteThrottle(new TimeLeapClock(Instant.parse("2026-01-01T00:00:00Z")),
				Duration.ofSeconds(30));

		assertTrue(throttle.tryRelease());
	}

	@Test
	public void testSecondWriteIsBlockedUntilTheIntervalHasPassed() {
		var clock = new TimeLeapClock(Instant.parse("2026-01-01T00:00:00Z"));
		var throttle = new WriteThrottle(clock, Duration.ofSeconds(30));

		assertTrue(throttle.tryRelease());
		assertFalse(throttle.tryRelease());

		clock.leap(29, ChronoUnit.SECONDS);
		assertFalse(throttle.tryRelease());

		clock.leap(1, ChronoUnit.SECONDS);
		assertTrue(throttle.tryRelease());
	}

	@Test
	public void testResetAllowsAnImmediateWrite() {
		var clock = new TimeLeapClock(Instant.parse("2026-01-01T00:00:00Z"));
		var throttle = new WriteThrottle(clock, Duration.ofSeconds(30));

		assertTrue(throttle.tryRelease());
		assertFalse(throttle.tryRelease());

		throttle.reset();
		assertTrue(throttle.tryRelease());
	}

	@Test
	public void testZeroIntervalNeverBlocks() {
		var throttle = new WriteThrottle(new TimeLeapClock(Instant.parse("2026-01-01T00:00:00Z")), Duration.ZERO);

		assertTrue(throttle.tryRelease());
		assertTrue(throttle.tryRelease());
	}
}
