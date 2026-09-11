package io.openems.edge.sungrow.charger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.sungrow.ess.SungrowEssImpl;

public class SungrowChargerImplTest {

	private static final String CHARGER_ID = "charger0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createCharger(SungrowChargerImpl charger, MpptPort mpptPort) throws Exception {
		return new ComponentTest(charger) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new SungrowEssImpl()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(CHARGER_ID) //
						.setModbusId(MODBUS_ID) //
						.setMpptPort(mpptPort) //
						.build());
	}

	@Test
	public void testActivate() throws Exception {
		var charger = new SungrowChargerImpl();
		final var test = createCharger(charger, MpptPort.MPPT_1);

		assertNotNull(charger.debugLog());

		test.deactivate();
	}

	@Test
	public void testActualPowerIsCalculatedFromVoltageAndCurrent() throws Exception {
		var charger = new SungrowChargerImpl();
		final var test = createCharger(charger, MpptPort.MPPT_3);

		// 600.0 V and 8.0 A -> 4800 W
		TestUtils.withValue(charger, EssDcCharger.ChannelId.VOLTAGE, 600_000);
		TestUtils.withValue(charger, EssDcCharger.ChannelId.CURRENT, 8000);

		test.next(new TestCase());

		assertEquals(4800, charger.getActualPowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testMpptPortStartAddresses() {
		assertEquals(5010, MpptPort.MPPT_1.getStartAddress());
		assertEquals(5012, MpptPort.MPPT_2.getStartAddress());
		assertEquals(5014, MpptPort.MPPT_3.getStartAddress());
	}
}
