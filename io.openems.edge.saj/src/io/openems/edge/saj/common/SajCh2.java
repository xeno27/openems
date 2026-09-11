package io.openems.edge.saj.common;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.saj.charger.SajCharger;
import io.openems.edge.saj.common.enums.AppMode;
import io.openems.edge.saj.common.enums.BatteryStatus;
import io.openems.edge.saj.common.enums.EmsEnable;
import io.openems.edge.saj.common.enums.InverterWorkMode;
import io.openems.edge.saj.ess.statemachine.StateMachine.State;

/**
 * Channels and helper methods that are common to all SAJ CH2 Components.
 */
public interface SajCh2 extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/*
		 * Monitoring registers (0x7Bxx / 0x7Cxx).
		 */
		INVERTER_WORK_MODE(Doc.of(InverterWorkMode.values()) //
				.persistencePriority(PersistencePriority.HIGH)), //
		APP_MODE(Doc.of(AppMode.values())), //
		BATTERY_STATUS(Doc.of(BatteryStatus.values())), //
		BATTERY_CHARGE_SOC_UPPER_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT)), //
		BATTERY_DISCHARGE_SOC_LOWER_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT)), //
		BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT)), //
		BATTERY_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE)), //
		BATTERY_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		BATTERY_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //
		BATTERY_DIRECTION(Doc.of(OpenemsType.INTEGER) //
				.text("1 = discharge, 0 = idle, -1 = charge")), //
		PV_DIRECTION(Doc.of(OpenemsType.INTEGER)), //
		GRID_DIRECTION(Doc.of(OpenemsType.INTEGER) //
				.text("1 = sell to grid, 0 = idle, -1 = buy from grid")), //
		TOTAL_PV_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //
		TOTAL_INVERTER_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		SYSTEM_TOTAL_LOAD_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		INVERTER_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS)), //

		/*
		 * Remote EMS control registers (0x83xx / 0x84xx).
		 */
		EMS_ENABLE(Doc.of(EmsEnable.values()) //
				.accessMode(AccessMode.READ_WRITE)), //
		EMS_KEEP_TIME(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.SECONDS) //
				.accessMode(AccessMode.READ_WRITE)), //
		EMS_INVERTER_POWER_REF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.TENTHOUSANDTH) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Inverter target power in 0.01 % of the nominal power")), //
		EMS_BATTERY_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.accessMode(AccessMode.READ_WRITE)), //
		EMS_BATTERY_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.accessMode(AccessMode.READ_WRITE)), //
		EMS_BATTERY_POWER_REF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.TENTHOUSANDTH) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Battery target power in 0.01 % of the nominal power")), //
		EMS_SELL_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.TENTHOUSANDTH) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Maximum export power in 0.01 % of the nominal power")), //
		EMS_BUY_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.TENTHOUSANDTH) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("Maximum import power in 0.01 % of the nominal power")), //

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
	 * Registers a {@link SajCharger} with this inverter. The PV production of all
	 * registered Chargers is used to derive the battery power Set-Point.
	 *
	 * @param charger the {@link SajCharger}
	 */
	public void addCharger(SajCharger charger);

	/**
	 * Unregisters a {@link SajCharger} from this inverter.
	 *
	 * @param charger the {@link SajCharger}
	 */
	public void removeCharger(SajCharger charger);

	/**
	 * Gets the sum of the actual power of all registered {@link SajCharger}s.
	 *
	 * @return the PV production in [W]; null if no value is available
	 */
	public Integer calculatePvProduction();

	/**
	 * Gets the Channel for {@link ChannelId#EMS_ENABLE}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getEmsEnableChannel() {
		return this.channel(ChannelId.EMS_ENABLE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#EMS_KEEP_TIME}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getEmsKeepTimeChannel() {
		return this.channel(ChannelId.EMS_KEEP_TIME);
	}

	/**
	 * Gets the Channel for {@link ChannelId#EMS_INVERTER_POWER_REF}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getEmsInverterPowerRefChannel() {
		return this.channel(ChannelId.EMS_INVERTER_POWER_REF);
	}

	/**
	 * Gets the Channel for {@link ChannelId#EMS_BATTERY_POWER_REF}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getEmsBatteryPowerRefChannel() {
		return this.channel(ChannelId.EMS_BATTERY_POWER_REF);
	}

	/**
	 * Gets the Channel for {@link ChannelId#EMS_SELL_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getEmsSellLimitChannel() {
		return this.channel(ChannelId.EMS_SELL_LIMIT);
	}

	/**
	 * Gets the Channel for {@link ChannelId#EMS_BUY_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getEmsBuyLimitChannel() {
		return this.channel(ChannelId.EMS_BUY_LIMIT);
	}

	/**
	 * Gets the Channel for {@link ChannelId#INVERTER_WORK_MODE}.
	 *
	 * @return the Channel
	 */
	public default Channel<InverterWorkMode> getInverterWorkModeChannel() {
		return this.channel(ChannelId.INVERTER_WORK_MODE);
	}

	/**
	 * Gets the {@link InverterWorkMode}. See
	 * {@link ChannelId#INVERTER_WORK_MODE}.
	 *
	 * @return the {@link InverterWorkMode}
	 */
	public default InverterWorkMode getInverterWorkMode() {
		return this.getInverterWorkModeChannel().value().asEnum();
	}
}
