package io.openems.edge.growatt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class VppPowerHandlerTest {

	@Test
	public void testBatteryPowerSignIsInverted() {
		// Growatt reports positive for charge
		assertEquals(-3000, VppPowerHandler.toDcDischargePower(3000).intValue());
		assertEquals(3000, VppPowerHandler.toDcDischargePower(-3000).intValue());
		assertNull(VppPowerHandler.toDcDischargePower(null));
	}

	@Test
	public void testMeasuredAcPowerWins() {
		assertEquals(4200, VppPowerHandler.selectAcActivePower(4200, 1500, 2000).intValue());
	}

	@Test
	public void testFallsBackToCalculatedAcPower() {
		assertEquals(3500, VppPowerHandler.selectAcActivePower(null, 1500, 2000).intValue());
	}

	@Test
	public void testFallbackWithoutAnyValue() {
		assertNull(VppPowerHandler.selectAcActivePower(null, null, null));
	}
}
