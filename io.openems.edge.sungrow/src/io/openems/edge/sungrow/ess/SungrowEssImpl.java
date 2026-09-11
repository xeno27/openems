package io.openems.edge.sungrow.ess;

import static io.openems.common.utils.IntUtils.sumInteger;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.element.WordOrder.LSWMSW;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.channel.ChannelUtils.setWriteValueIfNotRead;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.sungrow.charger.SungrowCharger;
import io.openems.edge.sungrow.common.AllowedPowerHandler;
import io.openems.edge.sungrow.common.ApplyPowerHandler;
import io.openems.edge.sungrow.common.BatteryPowerHandler;
import io.openems.edge.sungrow.common.Sungrow;
import io.openems.edge.sungrow.common.enums.ChargeDischargeCommand;
import io.openems.edge.sungrow.common.enums.ControlMode;
import io.openems.edge.sungrow.common.enums.EmsMode;
import io.openems.edge.sungrow.common.enums.RunningState;
import io.openems.edge.sungrow.common.enums.StartStopCommand;
import io.openems.edge.sungrow.ess.statemachine.Context;
import io.openems.edge.sungrow.ess.statemachine.StateMachine;
import io.openems.edge.sungrow.ess.statemachine.StateMachine.State;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Implements the Sungrow SH series of three-phase hybrid inverters (e.g.
 * SH20T) as a fully controllable Energy Storage System.
 *
 * <p>
 * Register addresses are the protocol addresses, i.e. the addresses of the
 * Sungrow 'Open Communication Protocol of Residential Hybrid Inverter' minus
 * one, because that document counts from 1. All 32-bit values are transmitted
 * low word first.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Sungrow.SH.ESS", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class SungrowEssImpl extends AbstractOpenemsModbusComponent
		implements Sungrow, HybridEss, ManagedSymmetricEss, SymmetricEss, ModbusComponent, OpenemsComponent,
		TimedataProvider, StartStoppable, EventHandler, ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(SungrowEssImpl.class);
	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);
	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);
	private final Set<SungrowCharger> chargers = new CopyOnWriteArraySet<>();

	private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private Power power;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata = null;

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config;

	public SungrowEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				Sungrow.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this._setCapacity(config.capacity());
		this._setMaxApparentPower(config.maxApparentPower());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				/*
				 * Input-Registers: device information.
				 */
				new FC4ReadInputRegistersTask(4999, Priority.LOW, //
						m(Sungrow.ChannelId.DEVICE_TYPE_CODE, new UnsignedWordElement(4999)), //
						m(Sungrow.ChannelId.NOMINAL_OUTPUT_POWER, new UnsignedWordElement(5000), SCALE_FACTOR_2)), //

				new FC4ReadInputRegistersTask(5007, Priority.LOW, //
						m(Sungrow.ChannelId.INSIDE_TEMPERATURE, new SignedWordElement(5007),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(5016, Priority.HIGH, //
						m(Sungrow.ChannelId.TOTAL_DC_POWER, new UnsignedDoublewordElement(5016).wordOrder(LSWMSW))), //

				new FC4ReadInputRegistersTask(5627, Priority.LOW, //
						m(Sungrow.ChannelId.BDC_RATED_POWER, new UnsignedWordElement(5627), SCALE_FACTOR_2)), //

				/*
				 * Input-Registers: operating values.
				 */
				new FC4ReadInputRegistersTask(12999, Priority.HIGH, //
						m(Sungrow.ChannelId.RUNNING_STATE, new UnsignedWordElement(12999)), //
						m(Sungrow.ChannelId.POWER_FLOW_STATUS, new UnsignedWordElement(13000))), //

				new FC4ReadInputRegistersTask(13007, Priority.HIGH, //
						m(Sungrow.ChannelId.LOAD_POWER, new SignedDoublewordElement(13007).wordOrder(LSWMSW))), //

				new FC4ReadInputRegistersTask(13019, Priority.HIGH, //
						m(Sungrow.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(13019), SCALE_FACTOR_2), //
						m(Sungrow.ChannelId.BATTERY_CURRENT, new SignedWordElement(13020), SCALE_FACTOR_2), //
						m(Sungrow.ChannelId.BATTERY_POWER_RAW, new SignedWordElement(13021)), //
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(13022), SCALE_FACTOR_MINUS_1), //
						m(Sungrow.ChannelId.BATTERY_STATE_OF_HEALTH, new UnsignedWordElement(13023),
								SCALE_FACTOR_MINUS_1), //
						m(Sungrow.ChannelId.BATTERY_TEMPERATURE, new SignedWordElement(13024),
								SCALE_FACTOR_MINUS_1), //
						new DummyRegisterElement(13025), //
						m(HybridEss.ChannelId.DC_DISCHARGE_ENERGY,
								new UnsignedDoublewordElement(13026).wordOrder(LSWMSW), SCALE_FACTOR_2)), //

				new FC4ReadInputRegistersTask(13033, Priority.HIGH, //
						m(SymmetricEss.ChannelId.ACTIVE_POWER,
								new SignedDoublewordElement(13033).wordOrder(LSWMSW))), //

				new FC4ReadInputRegistersTask(13040, Priority.LOW, //
						m(HybridEss.ChannelId.DC_CHARGE_ENERGY,
								new UnsignedDoublewordElement(13040).wordOrder(LSWMSW), SCALE_FACTOR_2)), //

				/*
				 * Holding-Registers: read back the remote control settings, so that they are
				 * only written when they actually change.
				 */
				new FC3ReadRegistersTask(12999, Priority.LOW, //
						m(Sungrow.ChannelId.SET_START_STOP, new UnsignedWordElement(12999))), //

				new FC3ReadRegistersTask(13049, Priority.HIGH, //
						m(Sungrow.ChannelId.SET_EMS_MODE, new UnsignedWordElement(13049)), //
						m(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_COMMAND, new UnsignedWordElement(13050)), //
						m(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_POWER, new UnsignedWordElement(13051))), //

				new FC3ReadRegistersTask(13057, Priority.LOW, //
						m(Sungrow.ChannelId.SET_MAX_SOC, new UnsignedWordElement(13057), SCALE_FACTOR_MINUS_1), //
						m(Sungrow.ChannelId.SET_MIN_SOC, new UnsignedWordElement(13058), SCALE_FACTOR_MINUS_1)), //

				new FC3ReadRegistersTask(13073, Priority.LOW, //
						m(Sungrow.ChannelId.SET_FEED_IN_LIMITATION_VALUE, new UnsignedWordElement(13073))), //

				new FC3ReadRegistersTask(13079, Priority.LOW, //
						m(Sungrow.ChannelId.SET_EXTERNAL_EMS_HEARTBEAT, new UnsignedWordElement(13079))), //

				new FC3ReadRegistersTask(33046, Priority.LOW, //
						m(Sungrow.ChannelId.SET_MAX_CHARGING_POWER, new UnsignedWordElement(33046), SCALE_FACTOR_1), //
						m(Sungrow.ChannelId.SET_MAX_DISCHARGING_POWER, new UnsignedWordElement(33047),
								SCALE_FACTOR_1)), //

				/*
				 * Holding-Registers: write.
				 */
				new FC16WriteRegistersTask(12999, //
						m(Sungrow.ChannelId.SET_START_STOP, new UnsignedWordElement(12999))), //

				new FC16WriteRegistersTask(13049, //
						m(Sungrow.ChannelId.SET_EMS_MODE, new UnsignedWordElement(13049)), //
						m(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_COMMAND, new UnsignedWordElement(13050)), //
						m(Sungrow.ChannelId.SET_CHARGE_DISCHARGE_POWER, new UnsignedWordElement(13051))), //

				new FC16WriteRegistersTask(13057, //
						m(Sungrow.ChannelId.SET_MAX_SOC, new UnsignedWordElement(13057), SCALE_FACTOR_MINUS_1), //
						m(Sungrow.ChannelId.SET_MIN_SOC, new UnsignedWordElement(13058), SCALE_FACTOR_MINUS_1)), //

				new FC16WriteRegistersTask(13073, //
						m(Sungrow.ChannelId.SET_FEED_IN_LIMITATION_VALUE, new UnsignedWordElement(13073))), //

				new FC16WriteRegistersTask(13079, //
						m(Sungrow.ChannelId.SET_EXTERNAL_EMS_HEARTBEAT, new UnsignedWordElement(13079))), //

				new FC16WriteRegistersTask(33046, //
						m(Sungrow.ChannelId.SET_MAX_CHARGING_POWER, new UnsignedWordElement(33046), SCALE_FACTOR_1), //
						m(Sungrow.ChannelId.SET_MAX_DISCHARGING_POWER, new UnsignedWordElement(33047),
								SCALE_FACTOR_1)) //
		);
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled() || this.config == null) {
			// Not activated yet
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> {
			this.updatePowerChannels();
			this.updateAllowedPowerChannels();
			this.runStateMachine();
		}
		}
	}

	/**
	 * Derives the signed DC battery power and the Grid-Mode.
	 */
	private void updatePowerChannels() {
		this._setDcDischargePower(BatteryPowerHandler.calculateDcDischargePower(//
				this.<IntegerReadChannel>channel(Sungrow.ChannelId.BATTERY_POWER_RAW).getNextValue().get(), //
				this.<IntegerReadChannel>channel(Sungrow.ChannelId.BATTERY_CURRENT).getNextValue().get(), //
				this.<IntegerReadChannel>channel(Sungrow.ChannelId.POWER_FLOW_STATUS).getNextValue().get()));
		setValue(this, SymmetricEss.ChannelId.GRID_MODE, mapGridMode(this.getRunningState()));

		final var acActivePower = this.getActivePowerChannel().getNextValue().get();
		if (acActivePower == null) {
			this.calculateAcChargeEnergy.update(null);
			this.calculateAcDischargeEnergy.update(null);
		} else if (acActivePower > 0) {
			this.calculateAcChargeEnergy.update(0);
			this.calculateAcDischargeEnergy.update(acActivePower);
		} else {
			this.calculateAcChargeEnergy.update(acActivePower * -1);
			this.calculateAcDischargeEnergy.update(0);
		}
	}

	/**
	 * Publishes the allowed charge and discharge power for the Power solver.
	 */
	private void updateAllowedPowerChannels() {
		var allowed = AllowedPowerHandler.calculate(this.getSoc().get(), this.config.minSoc(), this.config.maxSoc(),
				this.config.maxBatteryChargePower(), this.config.maxBatteryDischargePower());
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, allowed.allowedChargePower());
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, allowed.allowedDischargePower());
	}

	/**
	 * Executes the Start/Stop State-Machine.
	 */
	private void runStateMachine() {
		this.channel(Sungrow.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());
		try {
			this.stateMachine.run(new Context(this, this.componentManager.getClock()));
			this.channel(Sungrow.ChannelId.RUN_FAILED).setNextValue(false);

		} catch (OpenemsNamedException e) {
			this.channel(Sungrow.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		if (this.config.controlMode() == ControlMode.INTERNAL) {
			// The inverter dispatches itself
			return;
		}
		var pvProduction = this.calculatePvProduction();
		var result = ApplyPowerHandler.calculate(this.config.controlMode(), activePower, //
				pvProduction == null ? 0 : pvProduction, //
				this.config.maxBatteryChargePower(), this.config.maxBatteryDischargePower());

		this.getSetChargeDischargeCommandChannel().setNextWriteValue(result.command());
		this.getSetChargeDischargePowerChannel().setNextWriteValue(result.power());
	}

	/**
	 * Applies the configured EMS mode and the battery power limits; called by the
	 * State-Machine.
	 *
	 * <p>
	 * For 'External EMS mode' and 'VPP' the heartbeat has to be refreshed
	 * regularly, otherwise the inverter returns to self-consumption mode.
	 *
	 * @throws OpenemsNamedException on error
	 */
	public void applyEmsMode() throws OpenemsNamedException {
		final var emsMode = this.config.controlMode().getEmsMode();
		setWriteValueIfNotRead(this.getSetEmsModeChannel(), emsMode);

		if (this.config.controlMode() == ControlMode.INTERNAL) {
			return;
		}
		setWriteValueIfNotRead(this.getSetMaxChargingPowerChannel(), this.config.maxBatteryChargePower());
		setWriteValueIfNotRead(this.getSetMaxDischargingPowerChannel(), this.config.maxBatteryDischargePower());

		if (emsMode.requiresHeartbeat()) {
			// Written in every Cycle on purpose: this is the keep-alive of the session
			this.getSetExternalEmsHeartbeatChannel().setNextWriteValue(this.config.externalEmsHeartbeat());
		}
	}

	/**
	 * Hands control back to the inverter: stop the battery and return to
	 * self-consumption mode.
	 *
	 * @throws OpenemsNamedException on error
	 */
	public void resetToSelfConsumption() throws OpenemsNamedException {
		setWriteValueIfNotRead(this.getSetChargeDischargeCommandChannel(), ChargeDischargeCommand.STOP);
		setWriteValueIfNotRead(this.getSetEmsModeChannel(), EmsMode.SELF_CONSUMPTION);
	}

	/**
	 * Starts or shuts down the inverter; called by the State-Machine.
	 *
	 * @param start true to boot the inverter
	 * @throws OpenemsNamedException on error
	 */
	public void setStartStopCommand(boolean start) throws OpenemsNamedException {
		setWriteValueIfNotRead(this.getSetStartStopChannel(), //
				start ? StartStopCommand.START : StartStopCommand.STOP);
	}

	@Override
	public void addCharger(SungrowCharger charger) {
		this.chargers.add(charger);
	}

	@Override
	public void removeCharger(SungrowCharger charger) {
		this.chargers.remove(charger);
	}

	@Override
	public Integer calculatePvProduction() {
		Integer result = null;
		for (var charger : this.chargers) {
			result = sumInteger(result, charger.getActualPower().get());
		}
		if (result == null) {
			// Fall back to the total DC power that is reported by the inverter
			result = this.<IntegerReadChannel>channel(Sungrow.ChannelId.TOTAL_DC_POWER).getNextValue().get();
		}
		return result;
	}

	@Override
	public Integer getSurplusPower() {
		var pvProduction = this.calculatePvProduction();
		if (pvProduction == null || pvProduction < 100) {
			return null;
		}
		// Allowed charge power is negative by convention
		var surplus = pvProduction + this.getAllowedChargePower().orElse(0);
		if (surplus < 0) {
			return null;
		}
		return surplus;
	}

	@Override
	public boolean isManaged() {
		return this.config.controlMode() != ControlMode.INTERNAL;
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public int getPowerPrecision() {
		// The charge/discharge power register has a resolution of 1 W
		return 1;
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public void setStartStop(StartStop value) {
		if (this.startStopTarget.getAndSet(value) != value) {
			// Set only if value changed
			this.stateMachine.forceNextState(State.UNDEFINED);
		}
	}

	/**
	 * Gets the target Start/Stop state, considering the configuration.
	 *
	 * @return the {@link StartStop}
	 */
	public StartStop getStartStopTarget() {
		return switch (this.config.startStop()) {
		case AUTO -> this.startStopTarget.get();
		case START -> StartStop.START;
		case STOP -> StartStop.STOP;
		};
	}

	/**
	 * Maps the Sungrow 'Running state' to the OpenEMS {@link GridMode}.
	 *
	 * @param runningState the {@link RunningState}
	 * @return the {@link GridMode}
	 */
	protected static GridMode mapGridMode(RunningState runningState) {
		return switch (runningState) {
		case RUNNING_ON_GRID, MAINTAIN_MODE, COMPULSORY_MODE, EXTERNAL_EMS_MODE, EMERGENCY_CHARGING,
				DERATING_RUNNING, DISPATCH_RUNNING, WARN_RUNNING ->
			GridMode.ON_GRID;
		case RUNNING_OFF_GRID, MICROGRID_OPERATION, OFF_GRID_CHARGE -> GridMode.OFF_GRID;
		case EMERGENCY_STOP, FAULT, INITIAL_STANDBY, KEY_STOP, STANDBY, STARTING, STOP, UNDEFINED, UNINITIALIZED ->
			GridMode.UNDEFINED;
		};
	}

	@Override
	public String debugLog() {
		return new StringBuilder() //
				.append("SoC:").append(this.getSoc().asString()) //
				.append("|L:").append(this.getActivePower().asString()) //
				.append("|").append(this.stateMachine.debugLog()) //
				.append("|Allowed:").append(this.getAllowedChargePower().asStringWithoutUnit()) //
				.append(";").append(this.getAllowedDischargePower().asString()) //
				.toString();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode), //
				HybridEss.getModbusSlaveNatureTable(accessMode), //
				StartStoppable.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(Sungrow.class, accessMode, 100) //
						.build());
	}
}
