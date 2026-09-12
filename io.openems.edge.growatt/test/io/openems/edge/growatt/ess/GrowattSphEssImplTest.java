package io.openems.edge.growatt.ess;

import static io.openems.edge.growatt.common.enums.SystemWorkMode.BATTERY_OFFLINE;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.BATTERY_ONLINE;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.FAULT;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.PV_AND_BATTERY_ONLINE;
import static io.openems.edge.growatt.common.enums.SystemWorkMode.UNDEFINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StateChannel;
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
		return createEss(ess, controlMode, 0);
	}

	private static ComponentTest createEss(GrowattSphEssImpl ess, ControlMode controlMode, int bdcRatedPower)
			throws Exception {
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
						.setBdcRatedPower(bdcRatedPower) //
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
	public void testVppIsNotAvailableWithoutAProbeResponse() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);

		// The DummyModbusBridge never answers, so the VPP register bank stays unknown
		assertFalse(ess.isVppAvailable());

		test.deactivate();
	}

	@Test
	public void testProbeWithoutAnswerRaisesTheWarningOnlyInVppMode() throws Exception {
		var vppEss = new GrowattSphEssImpl();
		final var vppTest = createEss(vppEss, ControlMode.REMOTE_VPP);
		final StateChannel vppWarning = vppEss.channel(GrowattSph.ChannelId.VPP_NOT_AVAILABLE);

		vppEss.onVppProbeResult(null);
		assertTrue(vppWarning.getNextValue().get());
		vppTest.deactivate();

		var legacyEss = new GrowattSphEssImpl();
		final var legacyTest = createEss(legacyEss, ControlMode.REMOTE);
		final StateChannel legacyWarning = legacyEss.channel(GrowattSph.ChannelId.VPP_NOT_AVAILABLE);

		legacyEss.onVppProbeResult(null);
		assertFalse(legacyWarning.getNextValue().get());
		legacyTest.deactivate();
	}

	@Test
	public void testProbeNeedsAnEquipmentTypeCode() {
		// Table 3-1 of the VPP protocol has no equipment type code zero, so an
		// inverter that answers with zeros does not implement the protocol
		assertFalse(GrowattSphEssImpl.isVppProbeSuccessful(null));
		assertFalse(GrowattSphEssImpl.isVppProbeSuccessful(0));
		assertTrue(GrowattSphEssImpl.isVppProbeSuccessful(3502));
		assertTrue(GrowattSphEssImpl.isVppProbeSuccessful(3601));
	}

	@Test
	public void testProbeWithZeroKeepsTheLegacyPath() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);
		final StateChannel warning = ess.channel(GrowattSph.ChannelId.VPP_NOT_AVAILABLE);

		ess.onVppProbeResult(0);

		assertFalse(ess.isVppAvailable());
		assertTrue(warning.getNextValue().get());

		test.deactivate();
	}

	@Test
	public void testASingleFailedProbeDoesNotRevokeAnEstablishedVpp() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);
		final StateChannel warning = ess.channel(GrowattSph.ChannelId.VPP_NOT_AVAILABLE);

		ess.setVppAvailable(true);
		ess.onVppProbeResult(null);

		assertTrue(ess.isVppAvailable());
		assertFalse(warning.getNextValue().get());

		test.deactivate();
	}

	@Test
	public void testVppControlModeFallsBackToTheLegacyPath() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);

		TestUtils.withValue(ess, GrowattSph.ChannelId.PV_TOTAL_POWER, 0);
		ess.applyPower(-2300, 0);

		// VPP is not available -> the priority and time-slot control is used
		final IntegerWriteChannel vppRemotePower = ess.channel(GrowattSph.ChannelId.VPP_REMOTE_POWER);
		final IntegerWriteChannel chargePowerRate = ess
				.channel(GrowattSph.ChannelId.BATTERY_FIRST_CHARGE_POWER_RATE);
		assertTrue(vppRemotePower.getNextWriteValue().isEmpty());
		assertEquals(50, chargePowerRate.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testBdcRatedPowerPrefersTheConfiguredValue() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP, 5000);

		assertEquals(5000, ess.getBdcRatedPower());

		test.deactivate();
	}

	@Test
	public void testBdcRatedPowerFallsBackToTheReportedValue() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);

		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_BDC_RATED_POWER, 3600);
		assertEquals(3600, ess.getBdcRatedPower());

		test.deactivate();
	}

	@Test
	public void testBdcRatedPowerFallsBackToTheConfiguredBatteryPower() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);

		assertEquals(4600, ess.getBdcRatedPower());

		test.deactivate();
	}

	@Test
	public void testVppBatteryPowerWinsOverTheLegacyRegisters() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);

		// Legacy registers say discharge, the VPP register says charge
		TestUtils.withValue(ess, GrowattSph.ChannelId.BATTERY_DISCHARGE_POWER, 2000);
		TestUtils.withValue(ess, GrowattSph.ChannelId.BATTERY_CHARGE_POWER, 0);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_BATTERY_POWER, 3000);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_AC_ACTIVE_POWER, -1500);

		test.next(new TestCase());

		assertEquals(-3000, ess.getDcDischargePowerChannel().getNextValue().get().intValue());
		assertEquals(-1500, ess.getActivePowerChannel().getNextValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testVppControlModeWritesTheRemoteSetPoint() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP, 5000);
		ess.setVppAvailable(true);

		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_BATTERY_POWER, 0);
		// The inverter exposes 'Control authority' and reports it as not enabled
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_CONTROL_AUTHORITY, false);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_EMS_FAILURE_TIME, 0);
		ess.applyPower(-2500, 0);

		final BooleanWriteChannel remoteEnable = ess.channel(GrowattSph.ChannelId.VPP_REMOTE_POWER_ENABLE);
		final IntegerWriteChannel remoteDuration = ess.channel(GrowattSph.ChannelId.VPP_REMOTE_POWER_DURATION);
		final IntegerWriteChannel remotePower = ess.channel(GrowattSph.ChannelId.VPP_REMOTE_POWER);
		final BooleanWriteChannel controlAuthority = ess.channel(GrowattSph.ChannelId.VPP_CONTROL_AUTHORITY);

		assertTrue(remoteEnable.getNextWriteValue().get());
		assertEquals(0, remoteDuration.getNextWriteValue().get().intValue());
		// Charging 2500 W of 5000 W, inverted to the Growatt sign convention
		assertEquals(50, remotePower.getNextWriteValue().get().intValue());
		assertTrue(controlAuthority.getNextWriteValue().get());

		// The legacy path must stay untouched
		final IntegerWriteChannel chargePowerRate = ess
				.channel(GrowattSph.ChannelId.BATTERY_FIRST_CHARGE_POWER_RATE);
		assertTrue(chargePowerRate.getNextWriteValue().isEmpty());

		test.deactivate();
	}

	@Test
	public void testOptionalVppSettingsGiveUpOnAnInverterThatAnswersZero() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP, 5000);
		ess.setVppAvailable(true);
		final StateChannel notApplied = ess.channel(GrowattSph.ChannelId.VPP_SETTINGS_NOT_APPLIED);
		final IntegerWriteChannel failureTime = ess.channel(GrowattSph.ChannelId.VPP_EMS_FAILURE_TIME);

		// Firmware without the watchdog registers answers zero instead of rejecting
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_EMS_FAILURE_TIME, 0);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_CONTROL_AUTHORITY, false);

		for (var i = 0; i < 3; i++) {
			ess.applyPower(-2500, 0);
			assertEquals(60, failureTime.getNextWriteValueAndReset().get().intValue());
		}

		// After three attempts the Component stops writing
		ess.applyPower(-2500, 0);
		assertTrue(failureTime.getNextWriteValueAndReset().isEmpty());
		assertTrue(notApplied.getNextValue().get());

		// The Set-Point itself keeps being written
		final IntegerWriteChannel remotePower = ess.channel(GrowattSph.ChannelId.VPP_REMOTE_POWER);
		assertEquals(50, remotePower.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testOptionalVppSettingsAreNotRewrittenOnceTheyMatch() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP, 5000);
		ess.setVppAvailable(true);
		final StateChannel notApplied = ess.channel(GrowattSph.ChannelId.VPP_SETTINGS_NOT_APPLIED);

		// The inverter accepted the settings
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_EMS_FAILURE_TIME, 60);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_EMS_FAILURE_ENABLE, true);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_CONTROL_AUTHORITY, true);

		ess.applyPower(-2500, 0);

		final IntegerWriteChannel failureTime = ess.channel(GrowattSph.ChannelId.VPP_EMS_FAILURE_TIME);
		final BooleanWriteChannel controlAuthority = ess.channel(GrowattSph.ChannelId.VPP_CONTROL_AUTHORITY);
		assertTrue(failureTime.getNextWriteValue().isEmpty());
		assertTrue(controlAuthority.getNextWriteValue().isEmpty());
		assertFalse(notApplied.getNextValue().get());

		test.deactivate();
	}

	@Test
	public void testReleaseVppControlHandsControlBack() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP, 5000);
		ess.setVppAvailable(true);

		ess.releaseVppControl();

		final BooleanWriteChannel remoteEnable = ess.channel(GrowattSph.ChannelId.VPP_REMOTE_POWER_ENABLE);
		final IntegerWriteChannel remotePower = ess.channel(GrowattSph.ChannelId.VPP_REMOTE_POWER);
		assertFalse(remoteEnable.getNextWriteValue().get());
		assertEquals(0, remotePower.getNextWriteValue().get().intValue());

		test.deactivate();
	}

	@Test
	public void testVppPowerPrecisionIsOnePercentOfTheReferencePower() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP, 5000);

		// Without VPP the legacy quantization applies: 4600 W * 5 %
		assertEquals(230, ess.getPowerPrecision());

		ess.setVppAvailable(true);
		assertEquals(50, ess.getPowerPrecision());

		test.deactivate();
	}

	@Test
	public void testVppAllowedPowerUsesTheBatteryLimits() throws Exception {
		var ess = new GrowattSphEssImpl();
		final var test = createEss(ess, ControlMode.REMOTE_VPP);
		ess.setVppAvailable(true);

		TestUtils.withValue(ess, SymmetricEss.ChannelId.SOC, 50);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_BATTERY_MAX_CHARGE_POWER, 2800);
		TestUtils.withValue(ess, GrowattSph.ChannelId.VPP_BATTERY_MAX_DISCHARGE_POWER, 3200);

		test.next(new TestCase());

		assertEquals(-2800, ess.getAllowedChargePowerChannel().getNextValue().get().intValue());
		assertEquals(3200, ess.getAllowedDischargePowerChannel().getNextValue().get().intValue());

		test.deactivate();
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
