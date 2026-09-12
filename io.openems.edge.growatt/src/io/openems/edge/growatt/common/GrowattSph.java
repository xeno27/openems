package io.openems.edge.growatt.common;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.growatt.charger.GrowattCharger;
import io.openems.edge.growatt.common.enums.BatteryStatus;
import io.openems.edge.growatt.common.enums.BatteryType;
import io.openems.edge.growatt.common.enums.InverterStatus;
import io.openems.edge.growatt.common.enums.PriorityMode;
import io.openems.edge.growatt.common.enums.SystemWorkMode;
import io.openems.edge.growatt.common.enums.VppBatteryWorkingState;
import io.openems.edge.growatt.common.enums.VppWorkingState;
import io.openems.edge.growatt.ess.statemachine.StateMachine.State;

/**
 * Channels and helper methods that are common to all Growatt SPH Components.
 */
public interface GrowattSph extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/*
		 * Input-Registers: inverter values.
		 */
		INVERTER_STATUS(Doc.of(InverterStatus.values()) //
				.persistencePriority(PersistencePriority.HIGH)), //
		PV_TOTAL_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //
		AC_OUTPUT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		GRID_FREQUENCY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIHERTZ)), //
		INVERTER_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //
		IPM_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //
		BOOST_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //
		ACTUAL_PRIORITY_MODE(Doc.of(PriorityMode.values())), //
		BATTERY_TYPE(Doc.of(BatteryType.values())), //

		/*
		 * Input-Registers: storage values.
		 */
		SYSTEM_WORK_MODE(Doc.of(SystemWorkMode.values()) //
				.persistencePriority(PersistencePriority.HIGH)), //
		BATTERY_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		BATTERY_CHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT)), //
		BATTERY_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //
		BATTERY_STATUS(Doc.of(BatteryStatus.values())), //
		P_AC_TO_USER_TOTAL(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		P_AC_TO_GRID_TOTAL(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		P_LOCAL_LOAD_TOTAL(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //

		/*
		 * Holding-Registers: remote control.
		 */
		POWER_ON_OFF(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE)), //
		ACTIVE_POWER_RATE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		REACTIVE_POWER_RATE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		EXPORT_LIMIT_ENABLE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		EXPORT_LIMIT_POWER_RATE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.THOUSANDTH) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_PRIORITY_MODE(Doc.of(PriorityMode.values()) //
				.accessMode(AccessMode.READ_WRITE)), //
		GRID_FIRST_DISCHARGE_POWER_RATE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		GRID_FIRST_STOP_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		GRID_FIRST_SLOT_START(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		GRID_FIRST_SLOT_STOP(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		GRID_FIRST_SLOT_ENABLED(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE)), //
		BATTERY_FIRST_CHARGE_POWER_RATE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		BATTERY_FIRST_STOP_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		BATTERY_FIRST_AC_CHARGE(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE)), //
		BATTERY_FIRST_SLOT_START(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		BATTERY_FIRST_SLOT_STOP(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		BATTERY_FIRST_SLOT_ENABLED(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE)), //

		/*
		 * VPP protocol (register bank 30000-32099): device information.
		 */
		VPP_DEVICE_TYPE_CODE(Doc.of(OpenemsType.INTEGER) //
				.text("Growatt DTC code; 3502 = SPH 3000-6000TL BL, 3601 = SPH 4000-10000TL3 BH-UP")), //
		VPP_PROTOCOL_VERSION(Doc.of(OpenemsType.INTEGER) //
				.text("201 represents VPP protocol V2.01")), //
		VPP_RATED_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		VPP_MAX_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		VPP_BDC_RATED_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Rated charge/discharge power of the battery DC/DC converter; "
						+ "reference for the remote power percentage")), //

		/*
		 * VPP protocol: remote control.
		 */
		VPP_CONTROL_AUTHORITY(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Master switch for remote control; stored in non-volatile memory")), //
		VPP_EMS_FAILURE_TIME(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.SECONDS) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Watchdog: time without EMS communication until the inverter falls back")), //
		VPP_EMS_FAILURE_ENABLE(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE)), //
		VPP_CHARGE_CUT_OFF_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		VPP_DISCHARGE_CUT_OFF_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		VPP_REMOTE_POWER_ENABLE(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Remote power control enable; not stored in non-volatile memory")), //
		VPP_REMOTE_POWER_DURATION(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MINUTE) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("0 = unlimited; not stored in non-volatile memory")), //
		VPP_REMOTE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Remote charge/discharge power; positive is charge, negative is discharge. "
						+ "Not stored in non-volatile memory")), //
		VPP_ACTUAL_CONTROL_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.text("Charge/discharge power the inverter actually applies")), //

		/*
		 * VPP protocol: measurements.
		 */
		VPP_WORKING_STATE(Doc.of(VppWorkingState.values()) //
				.persistencePriority(PersistencePriority.HIGH)), //
		VPP_BATTERY_WORKING_STATE(Doc.of(VppBatteryWorkingState.values())), //
		VPP_PRIORITY(Doc.of(PriorityMode.values())), //
		VPP_FAULT_CODE(Doc.of(OpenemsType.INTEGER)), //
		VPP_FAULT_SUB_CODE(Doc.of(OpenemsType.INTEGER)), //
		VPP_ALARM_CODE(Doc.of(OpenemsType.INTEGER)), //
		VPP_ALARM_SUB_CODE(Doc.of(OpenemsType.INTEGER)), //
		VPP_PV_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		VPP_AC_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("AC power of the inverter; positive is export to grid")), //
		VPP_AC_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE)), //
		VPP_INVERTER_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //
		VPP_BATTERY_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Battery power; positive is charge, negative is discharge")), //
		VPP_BATTERY_MAX_CHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		VPP_BATTERY_MAX_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		VPP_BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT)), //
		VPP_BATTERY_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.text("Battery current; positive is charge, negative is discharge")), //
		VPP_BATTERY_STATE_OF_HEALTH(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT)), //
		VPP_BATTERY_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //

		/*
		 * State-Channels.
		 */
		VPP_SET_POINT_NOT_APPLIED(Doc.of(Level.WARNING) //
				.text("The inverter does not apply the VPP Set-Point: register 30474 does not follow "
						+ "register 30409. Falling back to the priority and time-slot control.")), //
		VPP_SETTINGS_NOT_APPLIED(Doc.of(Level.WARNING) //
				.text("The inverter did not accept 'Control authority' or the EMS watchdog registers. "
						+ "Those registers were added in later versions of the VPP protocol; the "
						+ "Set-Point itself is unaffected.")), //
		VPP_NOT_AVAILABLE(Doc.of(Level.WARNING) //
				.text("Control mode REMOTE_VPP is configured, but the inverter does not answer on the "
						+ "VPP register bank. Falling back to the priority and time-slot control.")), //
		STATE_MACHINE(Doc.of(State.values()) //
				.text("Current State of State-Machine")), //
		RUN_FAILED(Doc.of(Level.FAULT) //
				.text("Running the Logic failed"));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Registers a {@link GrowattCharger} with this inverter. The PV production of
	 * all registered Chargers is subtracted from the Active-Power Set-Point to get
	 * the battery power.
	 *
	 * @param charger the {@link GrowattCharger}
	 */
	public void addCharger(GrowattCharger charger);

	/**
	 * Unregisters a {@link GrowattCharger} from this inverter.
	 *
	 * @param charger the {@link GrowattCharger}
	 */
	public void removeCharger(GrowattCharger charger);

	/**
	 * Gets the sum of the actual power of all registered {@link GrowattCharger}s.
	 *
	 * @return the PV production in [W]; null if no value is available
	 */
	public Integer calculatePvProduction();

	/**
	 * Gets the Channel for {@link ChannelId#SET_PRIORITY_MODE}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetPriorityModeChannel() {
		return this.channel(ChannelId.SET_PRIORITY_MODE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_FIRST_DISCHARGE_POWER_RATE}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getGridFirstDischargePowerRateChannel() {
		return this.channel(ChannelId.GRID_FIRST_DISCHARGE_POWER_RATE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_FIRST_CHARGE_POWER_RATE}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getBatteryFirstChargePowerRateChannel() {
		return this.channel(ChannelId.BATTERY_FIRST_CHARGE_POWER_RATE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_FIRST_AC_CHARGE}.
	 *
	 * @return the Channel
	 */
	public default BooleanWriteChannel getBatteryFirstAcChargeChannel() {
		return this.channel(ChannelId.BATTERY_FIRST_AC_CHARGE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_FIRST_SLOT_START}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getGridFirstSlotStartChannel() {
		return this.channel(ChannelId.GRID_FIRST_SLOT_START);
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_FIRST_SLOT_STOP}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getGridFirstSlotStopChannel() {
		return this.channel(ChannelId.GRID_FIRST_SLOT_STOP);
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_FIRST_SLOT_ENABLED}.
	 *
	 * @return the Channel
	 */
	public default BooleanWriteChannel getGridFirstSlotEnabledChannel() {
		return this.channel(ChannelId.GRID_FIRST_SLOT_ENABLED);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_FIRST_SLOT_START}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getBatteryFirstSlotStartChannel() {
		return this.channel(ChannelId.BATTERY_FIRST_SLOT_START);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_FIRST_SLOT_STOP}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getBatteryFirstSlotStopChannel() {
		return this.channel(ChannelId.BATTERY_FIRST_SLOT_STOP);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_FIRST_SLOT_ENABLED}.
	 *
	 * @return the Channel
	 */
	public default BooleanWriteChannel getBatteryFirstSlotEnabledChannel() {
		return this.channel(ChannelId.BATTERY_FIRST_SLOT_ENABLED);
	}

	/**
	 * Gets the Channel for {@link ChannelId#POWER_ON_OFF}.
	 *
	 * @return the Channel
	 */
	public default BooleanWriteChannel getPowerOnOffChannel() {
		return this.channel(ChannelId.POWER_ON_OFF);
	}

	/**
	 * Gets the Channel for {@link ChannelId#INVERTER_STATUS}.
	 *
	 * @return the Channel
	 */
	public default Channel<InverterStatus> getInverterStatusChannel() {
		return this.channel(ChannelId.INVERTER_STATUS);
	}

	/**
	 * Gets the {@link InverterStatus}. See
	 * {@link ChannelId#INVERTER_STATUS}.
	 *
	 * @return the {@link InverterStatus}
	 */
	public default InverterStatus getInverterStatus() {
		return this.getInverterStatusChannel().value().asEnum();
	}

	/**
	 * Gets the Channel for {@link ChannelId#VPP_REMOTE_POWER_ENABLE}.
	 *
	 * @return the Channel
	 */
	public default BooleanWriteChannel getVppRemotePowerEnableChannel() {
		return this.channel(ChannelId.VPP_REMOTE_POWER_ENABLE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#VPP_REMOTE_POWER_DURATION}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getVppRemotePowerDurationChannel() {
		return this.channel(ChannelId.VPP_REMOTE_POWER_DURATION);
	}

	/**
	 * Gets the Channel for {@link ChannelId#VPP_REMOTE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getVppRemotePowerChannel() {
		return this.channel(ChannelId.VPP_REMOTE_POWER);
	}

	/**
	 * Gets the Channel for {@link ChannelId#VPP_CONTROL_AUTHORITY}.
	 *
	 * @return the Channel
	 */
	public default BooleanWriteChannel getVppControlAuthorityChannel() {
		return this.channel(ChannelId.VPP_CONTROL_AUTHORITY);
	}

	/**
	 * Gets the Channel for {@link ChannelId#VPP_EMS_FAILURE_TIME}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getVppEmsFailureTimeChannel() {
		return this.channel(ChannelId.VPP_EMS_FAILURE_TIME);
	}

	/**
	 * Gets the Channel for {@link ChannelId#VPP_EMS_FAILURE_ENABLE}.
	 *
	 * @return the Channel
	 */
	public default BooleanWriteChannel getVppEmsFailureEnableChannel() {
		return this.channel(ChannelId.VPP_EMS_FAILURE_ENABLE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_VOLTAGE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getBatteryVoltageChannel() {
		return this.channel(ChannelId.BATTERY_VOLTAGE);
	}

	/**
	 * Gets the Battery Voltage in [mV]. See {@link ChannelId#BATTERY_VOLTAGE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getBatteryVoltage() {
		return this.getBatteryVoltageChannel().value();
	}
}
