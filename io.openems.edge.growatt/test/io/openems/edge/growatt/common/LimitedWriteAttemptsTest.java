package io.openems.edge.growatt.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class LimitedWriteAttemptsTest {

	@Test
	public void testGivesUpAfterTheLimit() {
		var attempts = new LimitedWriteAttempts(3);

		assertTrue(attempts.tryAttempt());
		assertTrue(attempts.tryAttempt());
		assertTrue(attempts.tryAttempt());
		assertFalse(attempts.tryAttempt());
		assertTrue(attempts.isExhausted());
	}

	@Test
	public void testResetAllowsFurtherAttempts() {
		var attempts = new LimitedWriteAttempts(1);

		assertTrue(attempts.tryAttempt());
		assertFalse(attempts.tryAttempt());

		attempts.reset();
		assertFalse(attempts.isExhausted());
		assertTrue(attempts.tryAttempt());
	}

	@Test
	public void testZeroAttemptsNeverWrites() {
		var attempts = new LimitedWriteAttempts(0);

		assertFalse(attempts.tryAttempt());
		assertTrue(attempts.isExhausted());
	}
}
