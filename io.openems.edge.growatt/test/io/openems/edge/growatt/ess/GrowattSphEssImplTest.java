package io.openems.edge.growatt.ess;

import static io.openems.edge.growatt.common.enums.SystemWorkMode.BATTERY_OFFLINE;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.BATTERY_ONLINE;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.FAULT;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.PV_AND_BATTERY_ONLINE;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.UNDEFINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.test.DummyPower;
import io.openems.edge.growatt.common.GrowattSph;
import io.openems.edge.growatt.common.enums.ControlMode;

public class GrowattSphEssImplTest {

	private static final String ESS_ID = "ess0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createEss(GrowattSphEssImpl ess, ControlMode controlMode) throws Exception {
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
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.INTERNAL);

		assertEquals(6000, ess.getCapacityChannel().getNextValue().get().intValue());
		assertEquals(4600, ess.getMaxApparentPowerChannel().getNextValue().get().intValue());
		assertNotNull(ess.debugLog());

		test.deactivate();
	}

	@Test
	public void testCalculatesAcAndDcPower() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE);

		TestUtils.withValue(ess, GrowattSph.ChannelId.BATTERY_DISCHARGE_POWER, 2000);
		TestUtils.withValue(ess, GrowattSph.ChannelId.BATTERY_CHARGE_POWER, 0);
		TestUtils.withValue(ess, GrowattSph.ChannelId.PV_TOTAL_POWER, 1500);

		test.next(new TestCase());

		// AC = PV + DC discharge
		assertEquals(3500, ess.getActivePowerChannel().getNextValue().get().intValue());
		assertEquals(2000, ess.getDcDischargePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testCalculatesAcPowerWhileCharging() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE);

		TestUtils.withValue(ess, GrowattSph.ChannelId.BATTERY_DISCHARGE_POWER, 0);
		TestUtils.withValue(ess, GrowattSph.ChannelId.BATTERY_CHARGE_POWER, 3000);
		TestUtils.withValue(ess, GrowattSph.ChannelId.PV_TOTAL_POWER, 1000);

		test.next(new TestCase());

		// 1000 W PV minus 3000 W charge -> 2000 W are taken from the AC side
		assertEquals(-2000, ess.getActivePowerChannel().getNextValue().get().intValue());
		assertEquals(-3000, ess.getDcDischargePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testAllowedPowerFollowsStateOfCharge() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE);

		TestUtils.withValue(ess, SymmetricEss.ChannelId.SOC, 100);
		test.next(new TestCase());

		assertEquals(0, ess.getAllowedChargePowerChannel().getNextValue().get().intValue());
		assertEquals(4600, ess.getAllowedDischargePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testInternalControlModeWritesNothing() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.INTERNAL);

		ess.applyPower(-2300, 0);

		final IntegerWriteChannel chargePowerRate = ess
				.channel(GrowattSph.ChannelId.BATTERY_FIRST_CHARGE_POWER_RATE);
		assertTrue(chargePowerRate.getNextWriteValue().isEmpty());
		assertEquals(ManagedSymmetricEss.class.isInstance(ess), true);

		test.deactivate();
	}

	@Test
	public void testRemoteControlModeWritesSetPoint() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE);

		TestUtils.withValue(ess, GrowattSph.ChannelId.PV_TOTAL_POWER, 0);
		ess.applyPower(-2300, 0);

		final IntegerWriteChannel chargePowerRate = ess
				.channel(GrowattSph.ChannelId.BATTERY_FIRST_CHARGE_POWER_RATE);
		assertEquals(50, chargePowerRate.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testStartStopTargetFollowsConfig() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE);

		assertEquals(StartStop.START, ess.getStartStopTarget());

		test.deactivate();
	}

	@Test
	public void testMapGridMode() {
		assertEquals(GridMode.ON_GRID, GrowattSphEssImpl.mapGridMode(PV_AND_BATTERY_ONLINE));
		assertEquals(GridMode.ON_GRID, GrowattSphEssImpl.mapGridMode(BATTERY_ONLINE));
		assertEquals(GridMode.OFF_GRID, GrowattSphEssImpl.mapGridMode(BATTERY_OFFLINE));
		assertEquals(GridMode.UNDEFINED, GrowattSphEssImpl.mapGridMode(FAULT));
		assertEquals(GridMode.UNDEFINED, GrowattSphEssImpl.mapGridMode(UNDEFINED));
	}

	@Test
	public void testSurplusPower() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE);

		// No PV production -> no surplus
		assertNull(ess.getSurplusPower());

		TestUtils.withValue(ess, GrowattSph.ChannelId.PV_TOTAL_POWER, 3000);
		TestUtils.withValue(ess, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, -1000);
		assertEquals(2000, ess.getSurplusPower().intValue());

		test.deactivate();
	}
}
