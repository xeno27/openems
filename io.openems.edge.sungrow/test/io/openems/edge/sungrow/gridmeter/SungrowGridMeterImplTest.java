package io.openems.edge.sungrow.gridmeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.meter.api.ElectricityMeter;

public class SungrowGridMeterImplTest {

	private static final String METER_ID = "meter0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createMeter(SungrowGridMeterImpl meter) throws Exception {
		return new ComponentTest(meter) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(METER_ID) //
						.setModbusId(MODBUS_ID) //
						.build());
	}

	@Test
	public void testMeterType() throws Exception {
		var meter = new SungrowGridMeterImpl();
		final var test = createMeter(meter);

		assertEquals(MeterType.GRID, meter.getMeterType());
		assertNotNull(meter.debugLog());

		test.deactivate();
	}

	@Test
	public void testMeterActivePowerKeepsTheSungrowSign() throws Exception {
		var meter = new SungrowGridMeterImpl();
		final var test = createMeter(meter);

		// Sungrow: > 0 buy from grid, < 0 sell to grid - same as OpenEMS
		TestUtils.withValue(meter, ElectricityMeter.ChannelId.ACTIVE_POWER, 2500);
		assertEquals(2500, meter.getActivePower().get().intValue());

		TestUtils.withValue(meter, ElectricityMeter.ChannelId.ACTIVE_POWER, -4000);
		assertEquals(-4000, meter.getActivePower().get().intValue());

		test.deactivate();
	}
}
