package io.openems.edge.growatt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class VppAllowedPowerHandlerTest {

	private static final int CONFIGURED_CHARGE = 4600;
	private static final int CONFIGURED_DISCHARGE = 4600;

	@Test
	public void testNoPowerWhileStateOfChargeIsUnknown() {
		var result = VppAllowedPowerHandler.calculate(null, 10, 100, 3000, 3000, CONFIGURED_CHARGE,
				CONFIGURED_DISCHARGE);

		assertEquals(0, result.allowedChargePower());
		assertEquals(0, result.allowedDischargePower());
	}

	@Test
	public void testBatteryLimitsAreUsedWhenStricter() {
		var result = VppAllowedPowerHandler.calculate(50, 10, 100, 3000, 2500, CONFIGURED_CHARGE,
				CONFIGURED_DISCHARGE);

		assertEquals(-3000, result.allowedChargePower());
		assertEquals(2500, result.allowedDischargePower());
	}

	@Test
	public void testConfiguredLimitsAreNeverExceeded() {
		var result = VppAllowedPowerHandler.calculate(50, 10, 100, 99_000, 99_000, CONFIGURED_CHARGE,
				CONFIGURED_DISCHARGE);

		assertEquals(-CONFIGURED_CHARGE, result.allowedChargePower());
		assertEquals(CONFIGURED_DISCHARGE, result.allowedDischargePower());
	}

	@Test
	public void testFallsBackToConfiguredLimits() {
		var result = VppAllowedPowerHandler.calculate(50, 10, 100, null, null, CONFIGURED_CHARGE,
				CONFIGURED_DISCHARGE);

		assertEquals(-CONFIGURED_CHARGE, result.allowedChargePower());
		assertEquals(CONFIGURED_DISCHARGE, result.allowedDischargePower());
	}

	@Test
	public void testStateOfChargeWindowStillApplies() {
		var atMaximum = VppAllowedPowerHandler.calculate(100, 10, 100, 3000, 3000, CONFIGURED_CHARGE,
				CONFIGURED_DISCHARGE);
		assertEquals(0, atMaximum.allowedChargePower());
		assertEquals(3000, atMaximum.allowedDischargePower());

		var atMinimum = VppAllowedPowerHandler.calculate(10, 10, 100, 3000, 3000, CONFIGURED_CHARGE,
				CONFIGURED_DISCHARGE);
		assertEquals(-3000, atMinimum.allowedChargePower());
		assertEquals(0, atMinimum.allowedDischargePower());
	}
}
