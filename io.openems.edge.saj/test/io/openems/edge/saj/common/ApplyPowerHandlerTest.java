package io.openems.edge.saj.common;

import static io.openems.edge.saj.common.enums.ControlMode.INTERNAL;
import static io.openems.edge.saj.common.enums.ControlMode.REMOTE_BATTERY;
import static io.openems.edge.saj.common.enums.ControlMode.REMOTE_INVERTER;
import static io.openems.edge.saj.common.enums.EmsEnable.BATTERY_POWER_DISPATCH;
import static io.openems.edge.saj.common.enums.EmsEnable.INVALID;
import static io.openems.edge.saj.common.enums.EmsEnable.INVERTER_POWER_DISPATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ApplyPowerHandlerTest {

	private static final int RATED_POWER = 50_000;

	@Test
	public void testInternalModeDispatchesNothing() {
		var result = ApplyPowerHandler.calculate(INTERNAL, 25_000, 0, RATED_POWER);

		assertEquals(INVALID, result.emsEnable());
		assertEquals(0, result.powerRefPerUnit());
	}

	@Test
	public void testInverterModePassesTheSetPointThrough() {
		var result = ApplyPowerHandler.calculate(REMOTE_INVERTER, 25_000, 0, RATED_POWER);

		assertEquals(INVERTER_POWER_DISPATCH, result.emsEnable());
		// 25 kW of 50 kW is 50.00 %
		assertEquals(5000, result.powerRefPerUnit());
	}

	@Test
	public void testInverterModeIgnoresPvProduction() {
		// The CH2 'Inverter target power' refers to the AC side and already includes
		// the PV production
		var result = ApplyPowerHandler.calculate(REMOTE_INVERTER, 25_000, 10_000, RATED_POWER);

		assertEquals(5000, result.powerRefPerUnit());
	}

	@Test
	public void testChargeIsNegative() {
		var result = ApplyPowerHandler.calculate(REMOTE_INVERTER, -50_000, 0, RATED_POWER);

		assertEquals(INVERTER_POWER_DISPATCH, result.emsEnable());
		assertEquals(-10_000, result.powerRefPerUnit());
	}

	@Test
	public void testBatteryModeSubtractsPvProduction() {
		var result = ApplyPowerHandler.calculate(REMOTE_BATTERY, 25_000, 10_000, RATED_POWER);

		assertEquals(BATTERY_POWER_DISPATCH, result.emsEnable());
		// 25 kW minus 10 kW PV = 15 kW of 50 kW is 30.00 %
		assertEquals(3000, result.powerRefPerUnit());
	}

	@Test
	public void testBatteryModeIgnoresNegativePvProduction() {
		var result = ApplyPowerHandler.calculate(REMOTE_BATTERY, 25_000, -5000, RATED_POWER);

		assertEquals(5000, result.powerRefPerUnit());
	}

	@Test
	public void testSetPointIsClampedTo110Percent() {
		assertEquals(11_000, ApplyPowerHandler.toPerUnit(999_999, RATED_POWER));
		assertEquals(-11_000, ApplyPowerHandler.toPerUnit(-999_999, RATED_POWER));
	}

	@Test
	public void testWithoutRatedPower() {
		assertEquals(0, ApplyPowerHandler.toPerUnit(25_000, 0));
	}
}
