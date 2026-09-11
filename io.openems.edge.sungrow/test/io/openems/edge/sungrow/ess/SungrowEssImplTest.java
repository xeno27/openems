package io.openems.edge.sungrow.ess;

import static io.openems.edge.sungrow.common.enums.RunningState.COMPULSORY_MODE;
import static io.openems.edge.sungrow.common.enums.RunningState.FAULT;
import static io.openems.edge.sungrow.common.enums.RunningState.RUNNING_OFF_GRID;
import static io.openems.edge.sungrow.common.enums.RunningState.RUNNING_ON_GRID;
import static io.openems.edge.sungrow.common.enums.RunningState.STANDBY;
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
import io.openems.edge.sungrow.common.Sungrow;
import io.openems.edge.sungrow.common.enums.ChargeDischargeCommand;
import io.openems.edge.sungrow.common.enums.ControlMode;
import io.openems.edge.sungrow.common.enums.EmsMode;

public class SungrowEssImplTest {

	private static final String ESS_ID = "ess0";
	private static final String MODBUS_ID = "modbus0";

	private static ComponentTest createEss(SungrowEssImpl ess, ControlMode controlMode) throws Exception {
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
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.INTERNAL);

		assertEquals(25_000, ess.getCapacityChannel().getNextValue().get().intValue());
		assertEquals(20_000, ess.getMaxApparentPowerChannel().getNextValue().get().intValue());
		assertEquals(1, ess.getPowerPrecision());
		assertNotNull(ess.debugLog());

		test.deactivate();
	}

	@Test
	public void testUnsignedBatteryPowerIsSignedByPowerFlowStatus() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_COMPULSORY);

		TestUtils.withValue(ess, Sungrow.ChannelId.BATTERY_POWER_RAW, 4000);
		// bit 1 set -> battery is charging
		TestUtils.withValue(ess, Sungrow.ChannelId.POWER_FLOW_STATUS, 0x02);

		test.next(new TestCase());

		assertEquals(-4000, ess.getDcDischargePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testAllowedPowerFollowsStateOfCharge() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_COMPULSORY);

		TestUtils.withValue(ess, SymmetricEss.ChannelId.SOC, 100);
		test.next(new TestCase());

		assertEquals(0, ess.getAllowedChargePowerChannel().getNextValue().get().intValue());
		assertEquals(20_000, ess.getAllowedDischargePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testInternalControlModeWritesNothing() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.INTERNAL);

		ess.applyPower(-5000, 0);

		final IntegerWriteChannel power = ess.channel(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_POWER);
		assertTrue(power.getNextWriteValue().isEmpty());
		assertFalse(ess.isManaged());

		test.deactivate();
	}

	@Test
	public void testRemoteControlModeWritesChargeCommand() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_COMPULSORY);

		ess.applyPower(-5000, 0);

		final EnumWriteChannel command = ess.channel(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_COMMAND);
		final IntegerWriteChannel power = ess.channel(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_POWER);
		assertEquals(ChargeDischargeCommand.CHARGE.getValue(), command.getNextWriteValue().get().intValue());
		assertEquals(5000, power.getNextWriteValue().get().intValue());
		assertTrue(ess.isManaged());

		test.deactivate();
	}

	@Test
	public void testCompulsoryModeNeedsNoHeartbeat() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_COMPULSORY);

		ess.applyEmsMode();

		final EnumWriteChannel emsMode = ess.channel(Sungrow.ChannelId.SET_EMS_MODE);
		final IntegerWriteChannel heartbeat = ess.channel(Sungrow.ChannelId.SET_EXTERNAL_EMS_HEARTBEAT);
		assertEquals(EmsMode.COMPULSORY.getValue(), emsMode.getNextWriteValue().get().intValue());
		assertTrue(heartbeat.getNextWriteValue().isEmpty());

		test.deactivate();
	}

	@Test
	public void testVppModeWritesHeartbeat() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);

		ess.applyEmsMode();

		final EnumWriteChannel emsMode = ess.channel(Sungrow.ChannelId.SET_EMS_MODE);
		final IntegerWriteChannel heartbeat = ess.channel(Sungrow.ChannelId.SET_EXTERNAL_EMS_HEARTBEAT);
		assertEquals(EmsMode.VPP.getValue(), emsMode.getNextWriteValue().get().intValue());
		assertEquals(60, heartbeat.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testResetToSelfConsumption() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);

		ess.resetToSelfConsumption();

		final EnumWriteChannel emsMode = ess.channel(Sungrow.ChannelId.SET_EMS_MODE);
		final EnumWriteChannel command = ess.channel(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_COMMAND);
		assertEquals(EmsMode.SELF_CONSUMPTION.getValue(), emsMode.getNextWriteValue().get().intValue());
		assertEquals(ChargeDischargeCommand.STOP.getValue(), command.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testStartStopTargetFollowsConfig() throws Exception {
		var ess = new SungrowEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_COMPULSORY);

		assertEquals(StartStop.START, ess.getStartStopTarget());

		test.deactivate();
	}

	@Test
	public void testMapGridMode() {
		assertEquals(GridMode.ON_GRID, SungrowEssImpl.mapGridMode(RUNNING_ON_GRID));
		assertEquals(GridMode.ON_GRID, SungrowEssImpl.mapGridMode(COMPULSORY_MODE));
		assertEquals(GridMode.OFF_GRID, SungrowEssImpl.mapGridMode(RUNNING_OFF_GRID));
		assertEquals(GridMode.UNDEFINED, SungrowEssImpl.mapGridMode(FAULT));
		assertEquals(GridMode.UNDEFINED, SungrowEssImpl.mapGridMode(STANDBY));
	}
}
