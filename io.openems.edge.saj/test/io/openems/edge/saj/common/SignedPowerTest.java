package io.openems.edge.saj.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class SignedPowerTest {

	@Test
	public void testPositiveDirection() {
		assertEquals(1500, SignedPower.applyDirection(1500, 1).intValue());
		assertEquals(1500, SignedPower.applyDirection(-1500, 1).intValue());
	}

	@Test
	public void testNegativeDirection() {
		assertEquals(-1500, SignedPower.applyDirection(1500, -1).intValue());
		assertEquals(-1500, SignedPower.applyDirection(-1500, -1).intValue());
	}

	@Test
	public void testNoDirection() {
		assertEquals(0, SignedPower.applyDirection(1500, 0).intValue());
	}

	@Test
	public void testNullInput() {
		assertNull(SignedPower.applyDirection(null, 1));
		assertNull(SignedPower.applyDirection(1500, null));
	}
}
