package io.openems.edge.growatt.common;

/**
 * Counts write attempts for a setting and gives up after a limit is reached.
 *
 * <p>
 * The VPP register bank grew over several protocol versions, and Growatt
 * inverters answer a read on a register they do not implement with zero instead
 * of rejecting it. A setting whose read-back never reaches the desired value is
 * therefore indistinguishable from a missing register, and retrying it in every
 * Cycle would produce a failed Modbus write forever.
 */
public class LimitedWriteAttempts {

	private final int maxAttempts;

	private int attempts = 0;

	public LimitedWriteAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	/**
	 * Consumes one attempt.
	 *
	 * @return true if the caller may write; false if the limit is reached
	 */
	public boolean tryAttempt() {
		if (this.attempts >= this.maxAttempts) {
			return false;
		}
		this.attempts++;
		return true;
	}

	/**
	 * Have all attempts been used up?.
	 *
	 * @return true if no further write is allowed
	 */
	public boolean isExhausted() {
		return this.attempts >= this.maxAttempts;
	}

	/**
	 * Forgets the used attempts, e.g. after the setting was confirmed by the
	 * device.
	 */
	public void reset() {
		this.attempts = 0;
	}
}
