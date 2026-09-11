package io.openems.edge.sungrow.common;

import static io.openems.edge.sungrow.common.enums.ChargeDischargeCommand.CHARGE;
import static io.openems.edge.sungrow.common.enums.ChargeDischargeCommand.DISCHARGE;
import static io.openems.edge.sungrow.common.enums.ChargeDischargeCommand.STOP;
import static io.openems.edge.sungrow.common.enums.ControlMode.INTERNAL;
import static io.openems.edge.sungrow.common.enums.ControlMode.REMOTE_COMPULSORY;
import static io.openems.edge.sungrow.common.enums.ControlMode.REMOTE_VPP;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ApplyPowerHandlerTest {

	private static final int MAX_CHARGE = 20_000;
	private static final int MAX_DISCHARGE = 20_000;

	@Test
	public void testInternalModeStopsTheBattery() {
		var result = ApplyPowerHandler.calculate(INTERNAL, -5000, 0, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(STOP, result.command());
		assertEquals(0, result.power());
	}

	@Test
	public void testChargeIsUnsigned() {
		var result = ApplyPowerHandler.calculate(REMOTE_COMPULSORY, -5000, 0, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(CHARGE, result.command());
		assertEquals(5000, result.power());
	}

	@Test
	public void testDischarge() {
		var result = ApplyPowerHandler.calculate(REMOTE_VPP, 7000, 0, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(DISCHARGE, result.command());
		assertEquals(7000, result.power());
	}

	@Test
	public void testPvProductionIsSubtractedFromSetPoint() {
		// 2 kW are requested on AC, 6 kW come from PV -> charge the battery with the
		// remaining 4 kW
		var result = ApplyPowerHandler.calculate(REMOTE_COMPULSORY, 2000, 6000, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(CHARGE, result.command());
		assertEquals(4000, result.power());
	}

	@Test
	public void testPvProductionCoveredBySetPointStopsTheBattery() {
		var result = ApplyPowerHandler.calculate(REMOTE_COMPULSORY, 6000, 6000, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(STOP, result.command());
		assertEquals(0, result.power());
	}

	@Test
	public void testNegativePvProductionIsIgnored() {
		var result = ApplyPowerHandler.calculate(REMOTE_COMPULSORY, 3000, -1000, MAX_CHARGE, MAX_DISCHARGE);

		assertEquals(DISCHARGE, result.command());
		assertEquals(3000, result.power());
	}

	@Test
	public void testPowerIsClampedToTheConfiguredLimits() {
		assertEquals(MAX_DISCHARGE,
				ApplyPowerHandler.calculate(REMOTE_COMPULSORY, 999_999, 0, MAX_CHARGE, MAX_DISCHARGE).power());
		assertEquals(MAX_CHARGE,
				ApplyPowerHandler.calculate(REMOTE_COMPULSORY, -999_999, 0, MAX_CHARGE, MAX_DISCHARGE).power());
	}
}
