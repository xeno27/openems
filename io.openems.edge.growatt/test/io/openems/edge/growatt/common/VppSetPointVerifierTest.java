package io.openems.edge.growatt.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class VppSetPointVerifierTest {

	@Test
	public void testAppliedSetPointPasses() {
		var verifier = new VppSetPointVerifier(3);

		assertTrue(verifier.verify(-50, -50));
		assertFalse(verifier.hasFailed());
	}

	@Test
	public void testRoundingDifferenceIsTolerated() {
		var verifier = new VppSetPointVerifier(3);

		assertTrue(verifier.verify(-50, -49));
		assertTrue(verifier.verify(50, 51));
	}

	@Test
	public void testAnInverterThatIgnoresTheSetPointIsDetected() {
		var verifier = new VppSetPointVerifier(3);

		// The inverter keeps reporting zero although a Set-Point was written
		assertTrue(verifier.verify(-50, 0));
		assertTrue(verifier.verify(-50, 0));
		assertFalse(verifier.verify(-50, 0));
		assertTrue(verifier.hasFailed());
	}

	@Test
	public void testZeroSetPointKeepsThePreviousVerdict() {
		var verifier = new VppSetPointVerifier(2);

		// A zero Set-Point cannot be told apart from an inverter that ignores it
		assertTrue(verifier.verify(0, 0));
		assertTrue(verifier.verify(null, 0));
		assertFalse(verifier.hasFailed());

		assertTrue(verifier.verify(-50, 0));
		assertFalse(verifier.verify(-50, 0));
		assertTrue(verifier.hasFailed());

		// Still failed while nothing is dispatched
		assertFalse(verifier.verify(0, 0));
		assertTrue(verifier.hasFailed());
	}

	@Test
	public void testRecoveryResetsTheVerdict() {
		var verifier = new VppSetPointVerifier(2);

		assertTrue(verifier.verify(-50, 0));
		assertFalse(verifier.verify(-50, 0));
		assertTrue(verifier.hasFailed());

		assertTrue(verifier.verify(-50, -50));
		assertFalse(verifier.hasFailed());
	}

	@Test
	public void testMissingReadBackCountsAsMismatch() {
		var verifier = new VppSetPointVerifier(2);

		assertTrue(verifier.verify(-50, null));
		assertFalse(verifier.verify(-50, null));
		assertTrue(verifier.hasFailed());
	}
}
