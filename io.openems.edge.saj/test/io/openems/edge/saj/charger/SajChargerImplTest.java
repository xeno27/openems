package io.openems.edge.saj.charger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.saj.ess.SajCh2EssImpl;

public class SajChargerImplTest {

	private static final String CHARGER_ID = "charger0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createCharger(SajChargerImpl charger, PvPort pvPort) throws Exception {
		return new ComponentTest(charger) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new SajCh2EssImpl()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(CHARGER_ID) //
						.setModbusId(MODBUS_ID) //
						.setPvPort(pvPort) //
						.build());
	}

	@Test
	public void testActivate() throws Exception {
		var charger = new SajChargerImpl();
		final var test = createCharger(charger, PvPort.PV_1);

		assertNotNull(charger.debugLog());

		test.deactivate();
	}

	@Test
	public void testTracksMaximumActualPower() throws Exception {
		var charger = new SajChargerImpl();
		final var test = createCharger(charger, PvPort.PV_3);

		TestUtils.withValue(charger, EssDcCharger.ChannelId.ACTUAL_POWER, 12_340);
		assertEquals(12_340, charger.getActualPower().get().intValue());
		assertEquals(12_400, charger.getMaxActualPowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testPvPortStartAddressesAreFourRegistersApart() {
		assertEquals(0x7B99, PvPort.PV_1.getStartAddress());
		assertEquals(0x7B9D, PvPort.PV_2.getStartAddress());
		assertEquals(0x7BB5, PvPort.PV_8.getStartAddress());

		var ports = PvPort.values();
		for (var i = 1; i < ports.length; i++) {
			assertEquals(4, ports[i].getStartAddress() - ports[i - 1].getStartAddress());
		}
	}
}
