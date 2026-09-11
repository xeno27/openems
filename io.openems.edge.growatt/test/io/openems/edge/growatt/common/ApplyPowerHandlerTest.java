package io.openems.edge.growatt.common;

import static io.openems.edge.growatt.common.enums.PriorityMode.BATTERY_FIRST;
import static io.openems.edge.growatt.common.enums.PriorityMode.GRID_FIRST;
import static io.openems.edge.growatt.common.enums.PriorityMode.LOAD_FIRST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ApplyPowerHandlerTest {

	private static final int MAX_CHARGE = 4600;
	private static final int MAX_DISCHARGE = 4600;
	private static final int STEP = 5;

	@Test
	public void testZeroSetPointKeepsLoadFirst() {
		var result = ApplyPowerHandler.calculate(0, 0, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(LOAD_FIRST, result.priorityMode());
		assertEquals(0, result.powerRatePercent());
		assertFalse(result.acChargeEnabled());
	}

	@Test
	public void testChargeFromGrid() {
		var result = ApplyPowerHandler.calculate(-2300, 0, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(BATTERY_FIRST, result.priorityMode());
		assertEquals(50, result.powerRatePercent());
		assertTrue(result.acChargeEnabled());
	}

	@Test
	public void testDischargeToGrid() {
		var result = ApplyPowerHandler.calculate(4600, 0, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(GRID_FIRST, result.priorityMode());
		assertEquals(100, result.powerRatePercent());
		assertFalse(result.acChargeEnabled());
	}

	@Test
	public void testPvProductionIsSubtractedFromSetPoint() {
		// 1000 W are requested on AC, 3000 W come from PV -> charge the battery with
		// the remaining 2000 W
		var result = ApplyPowerHandler.calculate(1000, 3000, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(BATTERY_FIRST, result.priorityMode());
		assertEquals(45, result.powerRatePercent());
	}

	@Test
	public void testPvProductionCoveredBySetPointKeepsLoadFirst() {
		var result = ApplyPowerHandler.calculate(2000, 2000, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(LOAD_FIRST, result.priorityMode());
	}

	@Test
	public void testNegativePvProductionIsIgnored() {
		var result = ApplyPowerHandler.calculate(2300, -500, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(GRID_FIRST, result.priorityMode());
		assertEquals(50, result.powerRatePercent());
	}

	@Test
	public void testSetPointIsClampedToNominalPower() {
		var result = ApplyPowerHandler.calculate(99_999, 0, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(100, result.powerRatePercent());
	}

	@Test
	public void testSmallSetPointIsNotQuantizedToZero() {
		var result = ApplyPowerHandler.calculate(10, 0, MAX_CHARGE, MAX_DISCHARGE, STEP);

		assertEquals(GRID_FIRST, result.priorityMode());
		assertEquals(STEP, result.powerRatePercent());
	}

	@Test
	public void testPowerRateIsQuantized() {
		// 1100 W of 4600 W is 23.9 % -> rounded up to the next 5 % step
		assertEquals(25, ApplyPowerHandler.toPowerRate(1100, MAX_DISCHARGE, 5));
		assertEquals(30, ApplyPowerHandler.toPowerRate(1100, MAX_DISCHARGE, 10));
		assertEquals(24, ApplyPowerHandler.toPowerRate(1100, MAX_DISCHARGE, 1));
	}

	@Test
	public void testPowerRateWithoutNominalPower() {
		assertEquals(0, ApplyPowerHandler.toPowerRate(1000, 0, STEP));
	}
}
