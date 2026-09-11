package io.openems.edge.growatt.charger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.growatt.ess.GrowattSphEssImpl;

public class GrowattChargerImplTest {

	private static final String CHARGER_ID = "charger0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createCharger(GrowattChargerImpl charger, PvPort pvPort) throws Exception {
		return new ComponentTest(charger) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new GrowattSphEssImpl()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(CHARGER_ID) //
						.setModbusId(MODBUS_ID) //
						.setPvPort(pvPort) //
						.build());
	}

	@Test
	public void testActivate() throws Exception {
		var charger = new GrowattChargerImpl();
		final var test = createCharger(charger, PvPort.PV_1);

		assertNotNull(charger.debugLog());

		test.deactivate();
	}

	@Test
	public void testTracksMaximumActualPower() throws Exception {
		var charger = new GrowattChargerImpl();
		final var test = createCharger(charger, PvPort.PV_2);

		TestUtils.withValue(charger, EssDcCharger.ChannelId.ACTUAL_POWER, 1234);
		assertEquals(1234, charger.getActualPower().get().intValue());

		// Max-Actual-Power is rounded away from zero to a precision of 100
		assertEquals(1300, charger.getMaxActualPowerChannel().getNextValue().get().intValue());

		test.next(new TestCase());

		test.deactivate();
	}

	@Test
	public void testPvPortStartAddresses() {
		assertEquals(3, PvPort.PV_1.getStartAddress());
		assertEquals(7, PvPort.PV_2.getStartAddress());
	}
}
