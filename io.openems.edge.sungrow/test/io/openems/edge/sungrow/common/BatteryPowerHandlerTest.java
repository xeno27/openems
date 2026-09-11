package io.openems.edge.sungrow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class BatteryPowerHandlerTest {

	/**
	 * 'Power flow status' with bit 1 set means 'battery is charging'.
	 */
	private static final int CHARGING = 0x02;
	private static final int DISCHARGING = 0x04;

	@Test
	public void testUnsignedPowerIsNegatedWhileCharging() {
		assertEquals(-3000,
				BatteryPowerHandler.calculateDcDischargePower(3000, null, CHARGING).intValue());
	}

	@Test
	public void testUnsignedPowerStaysPositiveWhileDischarging() {
		assertEquals(3000,
				BatteryPowerHandler.calculateDcDischargePower(3000, null, DISCHARGING).intValue());
	}

	@Test
	public void testNegativeBatteryCurrentMeansCharging() {
		assertEquals(-3000, BatteryPowerHandler.calculateDcDischargePower(3000, -1200, null).intValue());
	}

	@Test
	public void testAlreadySignedPowerIsKept() {
		assertEquals(-3000, BatteryPowerHandler.calculateDcDischargePower(-3000, -1200, CHARGING).intValue());
	}

	@Test
	public void testWithoutBatteryPower() {
		assertNull(BatteryPowerHandler.calculateDcDischargePower(null, -1200, CHARGING));
	}

	@Test
	public void testIsCharging() {
		assertTrue(BatteryPowerHandler.isCharging(null, CHARGING));
		assertTrue(BatteryPowerHandler.isCharging(-1, null));
		assertFalse(BatteryPowerHandler.isCharging(1, DISCHARGING));
		assertFalse(BatteryPowerHandler.isCharging(null, null));
	}
}
