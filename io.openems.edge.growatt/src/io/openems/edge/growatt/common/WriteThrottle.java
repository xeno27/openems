package io.openems.edge.growatt.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Limits how often a Set-Point may be written to the inverter.
 *
 * <p>
 * Growatt keeps the remote control settings in non-volatile memory, which wears
 * out on cyclic writes. A new value is therefore only released after a minimum
 * hold time has passed since the last released value.
 */
public class WriteThrottle {

	private final Clock clock;
	private final Duration minimumInterval;

	private Instant lastReleaseAt = Instant.MIN;

	public WriteThrottle(Clock clock, Duration minimumInterval) {
		this.clock = clock;
		this.minimumInterval = minimumInterval;
	}

	/**
	 * Is a write allowed at this moment?.
	 *
	 * <p>
	 * If this method returns true, the current time is stored as the last release
	 * time, i.e. the next release is blocked for the configured minimum interval.
	 *
	 * @return true if the caller may write
	 */
	public boolean tryRelease() {
		var now = Instant.now(this.clock);
		if (Duration.between(this.lastReleaseAt, now).compareTo(this.minimumInterval) < 0) {
			return false;
		}
		this.lastReleaseAt = now;
		return true;
	}

	/**
	 * Forgets the last release time, so that the next call to
	 * {@link #tryRelease()} is allowed again.
	 */
	public void reset() {
		this.lastReleaseAt = Instant.MIN;
	}
}
