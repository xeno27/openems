package io.openems.edge.sungrow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.sungrow.common.enums.ControlMode;
import io.openems.edge.sungrow.common.enums.EmsMode;

public class EmsModeTest {

	@Test
	public void testHeartbeatIsOnlyRequiredForExternalModes() {
		assertFalse(EmsMode.SELF_CONSUMPTION.requiresHeartbeat());
		assertFalse(EmsMode.COMPULSORY.requiresHeartbeat());
		assertTrue(EmsMode.EXTERNAL_EMS.requiresHeartbeat());
		assertTrue(EmsMode.VPP.requiresHeartbeat());
	}

	@Test
	public void testControlModeMapsToEmsMode() {
		assertEquals(EmsMode.SELF_CONSUMPTION, ControlMode.INTERNAL.getEmsMode());
		assertEquals(EmsMode.COMPULSORY, ControlMode.REMOTE_COMPULSORY.getEmsMode());
		assertEquals(EmsMode.EXTERNAL_EMS, ControlMode.REMOTE_EXTERNAL_EMS.getEmsMode());
		assertEquals(EmsMode.VPP, ControlMode.REMOTE_VPP.getEmsMode());
	}

	@Test
	public void testRegisterValues() {
		assertEquals(0, EmsMode.SELF_CONSUMPTION.getValue());
		assertEquals(2, EmsMode.COMPULSORY.getValue());
		assertEquals(3, EmsMode.EXTERNAL_EMS.getValue());
		assertEquals(4, EmsMode.VPP.getValue());
	}
}
