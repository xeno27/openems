package io.openems.edge.growatt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class VppApplyPowerHandlerTest {

	private static final int BDC_RATED_POWER = 5000;

	@Test
	public void testDischargeIsNegativeForGrowatt() {
		// OpenEMS: positive is discharge; Growatt: positive is charge
		var result = VppApplyPowerHandler.calculate(2500, 0, BDC_RATED_POWER);

		assertTrue(result.remoteControlEnabled());
		assertEquals(-50, result.powerPercent());
	}

	@Test
	public void testChargeIsPositiveForGrowatt() {
		var result = VppApplyPowerHandler.calculate(-2500, 0, BDC_RATED_POWER);

		assertEquals(50, result.powerPercent());
	}

	@Test
	public void testPvProductionIsSubtractedFromSetPoint() {
		// 1000 W requested on AC, 3500 W from PV -> charge the battery with 2500 W
		var result = VppApplyPowerHandler.calculate(1000, 3500, BDC_RATED_POWER);

		assertEquals(50, result.powerPercent());
	}

	@Test
	public void testSetPointEqualToPvProductionKeepsTheBatteryIdle() {
		var result = VppApplyPowerHandler.calculate(3000, 3000, BDC_RATED_POWER);

		// Remote control stays enabled, so the inverter does not fall back to its own
		// schedule while OpenEMS asks for zero battery power
		assertTrue(result.remoteControlEnabled());
		assertEquals(0, result.powerPercent());
	}

	@Test
	public void testNegativePvProductionIsIgnored() {
		var result = VppApplyPowerHandler.calculate(2500, -1000, BDC_RATED_POWER);

		assertEquals(-50, result.powerPercent());
	}

	@Test
	public void testSetPointIsClampedToTheAllowedRange() {
		assertEquals(-100, VppApplyPowerHandler.calculate(999_999, 0, BDC_RATED_POWER).powerPercent());
		assertEquals(100, VppApplyPowerHandler.calculate(-999_999, 0, BDC_RATED_POWER).powerPercent());
	}

	@Test
	public void testWithoutReferencePowerNothingIsDispatched() {
		var result = VppApplyPowerHandler.calculate(2500, 0, 0);

		assertFalse(result.remoteControlEnabled());
		assertEquals(0, result.powerPercent());
	}
}
