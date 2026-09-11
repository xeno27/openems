package io.openems.edge.growatt.gridmeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.TestUtils;

public class GrowattGridMeterImplTest {

	private static final String METER_ID = "meter0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createMeter(GrowattGridMeterImpl meter) throws Exception {
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
		var meter = new GrowattGridMeterImpl();
		final var test = createMeter(meter);

		assertEquals(MeterType.GRID, meter.getMeterType());
		assertNotNull(meter.debugLog());

		test.deactivate();
	}

	@Test
	public void testBuyFromGridIsPositive() throws Exception {
		var meter = new GrowattGridMeterImpl();
		final var test = createMeter(meter);

		TestUtils.withValue(meter, GrowattGridMeter.ChannelId.POWER_TO_USER, 2500);
		TestUtils.withValue(meter, GrowattGridMeter.ChannelId.POWER_TO_GRID, 0);
		test.next(new TestCase());

		assertEquals(2500, meter.getActivePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testSellToGridIsNegative() throws Exception {
		var meter = new GrowattGridMeterImpl();
		final var test = createMeter(meter);

		TestUtils.withValue(meter, GrowattGridMeter.ChannelId.POWER_TO_USER, 0);
		TestUtils.withValue(meter, GrowattGridMeter.ChannelId.POWER_TO_GRID, 4000);
		test.next(new TestCase());

		assertEquals(-4000, meter.getActivePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}
}
