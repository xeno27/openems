package io.openems.edge.saj.ess;

import static io.openems.edge.saj.common.enums.InverterWorkMode.FAULT;
import static io.openems.edge.saj.common.enums.InverterWorkMode.GRID_CONNECTED;
import static io.openems.edge.saj.common.enums.InverterWorkMode.GRID_LOADED;
import static io.openems.edge.saj.common.enums.InverterWorkMode.OFF_GRID;
import static io.openems.edge.saj.common.enums.InverterWorkMode.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.test.DummyPower;
import io.openems.edge.saj.common.SajCh2;
import io.openems.edge.saj.common.enums.ControlMode;
import io.openems.edge.saj.common.enums.EmsEnable;

public class SajCh2EssImplTest {

	private static final String ESS_ID = "ess0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createEss(SajCh2EssImpl ess, ControlMode controlMode) throws Exception {
		return new ComponentTest(ess) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("power", new DummyPower()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(ESS_ID) //
						.setModbusId(MODBUS_ID) //
						.setControlMode(controlMode) //
						.setStartStop(StartStopConfig.START) //
						.build());
	}

	@Test
	public void testActivate() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.INTERNAL);

		assertEquals(100_000, ess.getCapacityChannel().getNextValue().get().intValue());
		assertEquals(50_000, ess.getMaxApparentPowerChannel().getNextValue().get().intValue());
		assertNotNull(ess.debugLog());

		test.deactivate();
	}

	@Test
	public void testBatteryDirectionSignsTheDcPower() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_INVERTER);

		TestUtils.withValue(ess, SajCh2.ChannelId.BATTERY_POWER, 20_000);
		TestUtils.withValue(ess, SajCh2.ChannelId.BATTERY_DIRECTION, -1);
		TestUtils.withValue(ess, SajCh2.ChannelId.TOTAL_INVERTER_POWER, -15_000);

		test.next(new TestCase());

		assertEquals(-20_000, ess.getDcDischargePowerChannel().getNextValue().get().intValue());
		assertEquals(-15_000, ess.getActivePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testAllowedPowerFollowsStateOfCharge() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_INVERTER);

		TestUtils.withValue(ess, SymmetricEss.ChannelId.SOC, 5);
		test.next(new TestCase());

		assertEquals(-50_000, ess.getAllowedChargePowerChannel().getNextValue().get().intValue());
		assertEquals(0, ess.getAllowedDischargePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testInternalControlModeWritesNothing() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.INTERNAL);

		ess.applyPower(25_000, 0);

		final IntegerWriteChannel powerRef = ess.channel(SajCh2.ChannelId.EMS_INVERTER_POWER_REF);
		assertTrue(powerRef.getNextWriteValue().isEmpty());
		assertFalse(ess.isManaged());

		test.deactivate();
	}

	@Test
	public void testRemoteInverterModeWritesSetPoint() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_INVERTER);

		ess.applyPower(25_000, 0);

		final IntegerWriteChannel powerRef = ess.channel(SajCh2.ChannelId.EMS_INVERTER_POWER_REF);
		assertEquals(5000, powerRef.getNextWriteValue().get().intValue());
		assertTrue(ess.isManaged());

		test.deactivate();
	}

	@Test
	public void testRemoteBatteryModeWritesBatterySetPoint() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_BATTERY);

		TestUtils.withValue(ess, SajCh2.ChannelId.TOTAL_PV_POWER, 10_000);
		ess.applyPower(25_000, 0);

		final IntegerWriteChannel powerRef = ess.channel(SajCh2.ChannelId.EMS_BATTERY_POWER_REF);
		assertEquals(3000, powerRef.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testRemoteEmsSessionIsEnabledAndDisabled() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_INVERTER);

		final EnumWriteChannel emsEnable = ess.channel(SajCh2.ChannelId.EMS_ENABLE);
		final IntegerWriteChannel keepTime = ess.channel(SajCh2.ChannelId.EMS_KEEP_TIME);

		ess.setRemoteEmsEnabled(true);
		assertEquals(EmsEnable.INVERTER_POWER_DISPATCH.getValue(), emsEnable.getNextWriteValue().get().intValue());
		assertEquals(60, keepTime.getNextWriteValue().get().intValue());

		ess.setRemoteEmsEnabled(false);
		assertEquals(EmsEnable.INVALID.getValue(), emsEnable.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testStartStopTargetFollowsConfig() throws Exception {
		var ess = new SajCh2EssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_INVERTER);

		assertEquals(StartStop.START, ess.getStartStopTarget());

		test.deactivate();
	}

	@Test
	public void testMapGridMode() {
		assertEquals(GridMode.ON_GRID, SajCh2EssImpl.mapGridMode(GRID_CONNECTED));
		assertEquals(GridMode.ON_GRID, SajCh2EssImpl.mapGridMode(GRID_LOADED));
		assertEquals(GridMode.OFF_GRID, SajCh2EssImpl.mapGridMode(OFF_GRID));
		assertEquals(GridMode.UNDEFINED, SajCh2EssImpl.mapGridMode(FAULT));
		assertEquals(GridMode.UNDEFINED, SajCh2EssImpl.mapGridMode(WAITING));
	}
}
