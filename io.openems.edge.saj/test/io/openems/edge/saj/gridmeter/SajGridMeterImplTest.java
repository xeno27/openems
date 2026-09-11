package io.openems.edge.saj.gridmeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.TestUtils;

public class SajGridMeterImplTest {

	private static final String METER_ID = "meter0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createMeter(SajGridMeterImpl meter) throws Exception {
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
		var meter = new SajGridMeterImpl();
		final var test = createMeter(meter);

		assertEquals(MeterType.GRID, meter.getMeterType());
		assertNotNull(meter.debugLog());

		test.deactivate();
	}

	@Test
	public void testBuyFromGridIsPositive() throws Exception {
		var meter = new SajGridMeterImpl();
		final var test = createMeter(meter);

		TestUtils.withValue(meter, SajGridMeter.ChannelId.TOTAL_GRID_POWER, 12_000);
		TestUtils.withValue(meter, SajGridMeter.ChannelId.GRID_DIRECTION, -1);
		test.next(new TestCase());

		assertEquals(12_000, meter.getActivePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testSellToGridIsNegative() throws Exception {
		var meter = new SajGridMeterImpl();
		final var test = createMeter(meter);

		TestUtils.withValue(meter, SajGridMeter.ChannelId.TOTAL_GRID_POWER, 12_000);
		TestUtils.withValue(meter, SajGridMeter.ChannelId.GRID_DIRECTION, 1);
		test.next(new TestCase());

		assertEquals(-12_000, meter.getActivePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testToOpenemsActivePower() {
		assertEquals(5000, SajGridMeterImpl.toOpenemsActivePower(5000, -1).intValue());
		assertEquals(-5000, SajGridMeterImpl.toOpenemsActivePower(5000, 1).intValue());
		assertEquals(0, SajGridMeterImpl.toOpenemsActivePower(5000, 0).intValue());
		assertNull(SajGridMeterImpl.toOpenemsActivePower(null, 1));
	}
}
