package io.openems.edge.sungrow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class AllowedPowerHandlerTest {

	private static final int MAX_CHARGE = 20_000;
	private static final int MAX_DISCHARGE = 20_000;

	@Test
	public void testNoPowerWhileStateOfChargeIsUnknown() {
		var result = AllowedPowerHandler.calculate(null, 10, 100, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(0, result.allowedChargePower());
		assertEquals(0, result.allowedDischargePower());
	}

	@Test
	public void testFullPowerInsideTheStateOfChargeWindow() {
		var result = AllowedPowerHandler.calculate(50, 10, 100, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(-MAX_CHARGE, result.allowedChargePower());
		assertEquals(MAX_DISCHARGE, result.allowedDischargePower());
	}

	@Test
	public void testNoDischargeBelowMinimumStateOfCharge() {
		var result = AllowedPowerHandler.calculate(10, 10, 100, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(0, result.allowedDischargePower());
	}

	@Test
	public void testNoChargeAboveMaximumStateOfCharge() {
		var result = AllowedPowerHandler.calculate(100, 10, 100, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(0, result.allowedChargePower());
	}
}
