package io.openems.edge.sungrow.common;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.sungrow.charger.SungrowCharger;
import io.openems.edge.sungrow.common.enums.ChargeDischargeCommand;
import io.openems.edge.sungrow.common.enums.EmsMode;
import io.openems.edge.sungrow.common.enums.RunningState;
import io.openems.edge.sungrow.common.enums.StartStopCommand;
import io.openems.edge.sungrow.ess.statemachine.StateMachine.State;

/**
 * Channels and helper methods that are common to all Sungrow SH Components.
 */
public interface Sungrow extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/*
		 * Input-Registers: device information.
		 */
		DEVICE_TYPE_CODE(Doc.of(OpenemsType.INTEGER)), //
		NOMINAL_OUTPUT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		BDC_RATED_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Nominal power of the battery DC/DC converter")), //
		INSIDE_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //

		/*
		 * Input-Registers: operating values.
		 */
		RUNNING_STATE(Doc.of(RunningState.values()) //
				.persistencePriority(PersistencePriority.HIGH)), //
		POWER_FLOW_STATUS(Doc.of(OpenemsType.INTEGER) //
				.text("Bit 0 = PV, bit 1 = battery charging, bit 2 = battery discharging, "
						+ "bit 3 = load, bit 4 = feed-in, bit 5 = import")), //
		TOTAL_DC_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //
		BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT)), //
		BATTERY_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE)), //
		BATTERY_POWER_RAW(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Battery power as reported by register 13021; may be unsigned")), //
		BATTERY_STATE_OF_HEALTH(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT)), //
		BATTERY_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //
		LOAD_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //

		/*
		 * Holding-Registers: remote control.
		 */
		SET_START_STOP(Doc.of(StartStopCommand.values()) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_EMS_MODE(Doc.of(EmsMode.values()) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_CHARGE_DISCHARGE_COMMAND(Doc.of(ChargeDischargeCommand.values()) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_CHARGE_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_MAX_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_MIN_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_FEED_IN_LIMITATION_VALUE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_EXTERNAL_EMS_HEARTBEAT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.SECONDS) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_MAX_CHARGING_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE)), //
		SET_MAX_DISCHARGING_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE)), //

		/*
		 * State-Channels.
		 */
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
	 * Registers a {@link SungrowCharger} with this inverter. The PV production of
	 * all registered Chargers is subtracted from the Active-Power Set-Point to get
	 * the battery power.
	 *
	 * @param charger the {@link SungrowCharger}
	 */
	public void addCharger(SungrowCharger charger);

	/**
	 * Unregisters a {@link SungrowCharger} from this inverter.
	 *
	 * @param charger the {@link SungrowCharger}
	 */
	public void removeCharger(SungrowCharger charger);

	/**
	 * Gets the sum of the actual power of all registered {@link SungrowCharger}s.
	 *
	 * @return the PV production in [W]; null if no value is available
	 */
	public Integer calculatePvProduction();

	/**
	 * Gets the Channel for {@link ChannelId#SET_START_STOP}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetStartStopChannel() {
		return this.channel(ChannelId.SET_START_STOP);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_EMS_MODE}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetEmsModeChannel() {
		return this.channel(ChannelId.SET_EMS_MODE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_CHARGE_DISCHARGE_COMMAND}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetChargeDischargeCommandChannel() {
		return this.channel(ChannelId.SET_CHARGE_DISCHARGE_COMMAND);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_CHARGE_DISCHARGE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetChargeDischargePowerChannel() {
		return this.channel(ChannelId.SET_CHARGE_DISCHARGE_POWER);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_MAX_CHARGING_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetMaxChargingPowerChannel() {
		return this.channel(ChannelId.SET_MAX_CHARGING_POWER);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_MAX_DISCHARGING_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetMaxDischargingPowerChannel() {
		return this.channel(ChannelId.SET_MAX_DISCHARGING_POWER);
	}

	/**
	 * Gets the maximum charge power the inverter reports in Holding-Register
	 * 33046, in [W]. See {@link ChannelId#SET_MAX_CHARGING_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSetMaxChargingPower() {
		return this.getSetMaxChargingPowerChannel().value();
	}

	/**
	 * Gets the maximum discharge power the inverter reports in Holding-Register
	 * 33047, in [W]. See {@link ChannelId#SET_MAX_DISCHARGING_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSetMaxDischargingPower() {
		return this.getSetMaxDischargingPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_EXTERNAL_EMS_HEARTBEAT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetExternalEmsHeartbeatChannel() {
		return this.channel(ChannelId.SET_EXTERNAL_EMS_HEARTBEAT);
	}

	/**
	 * Gets the Channel for {@link ChannelId#RUNNING_STATE}.
	 *
	 * @return the Channel
	 */
	public default Channel<RunningState> getRunningStateChannel() {
		return this.channel(ChannelId.RUNNING_STATE);
	}

	/**
	 * Gets the {@link RunningState}. See {@link ChannelId#RUNNING_STATE}.
	 *
	 * @return the {@link RunningState}
	 */
	public default RunningState getRunningState() {
		return this.getRunningStateChannel().value().asEnum();
	}
}
