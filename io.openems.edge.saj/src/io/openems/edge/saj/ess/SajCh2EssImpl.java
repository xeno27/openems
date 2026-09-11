package io.openems.edge.saj.ess;

import static io.openems.common.utils.IntUtils.sumInteger;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
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
import io.openems.edge.saj.charger.SajCharger;
import io.openems.edge.saj.common.AllowedPowerHandler;
import io.openems.edge.saj.common.ApplyPowerHandler;
import io.openems.edge.saj.common.SajCh2;
import io.openems.edge.saj.common.SignedPower;
import io.openems.edge.saj.common.enums.ControlMode;
import io.openems.edge.saj.common.enums.EmsEnable;
import io.openems.edge.saj.common.enums.InverterWorkMode;
import io.openems.edge.saj.ess.statemachine.Context;
import io.openems.edge.saj.ess.statemachine.StateMachine;
import io.openems.edge.saj.ess.statemachine.StateMachine.State;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Implements the SAJ CH2 commercial hybrid inverter as a fully controllable
 * Energy Storage System.
 *
 * <p>
 * Register addresses are taken from 'Protocol for CH2 remote EMS control' of
 * Guangzhou Sanjing Electric Co., Ltd.; see readme.adoc.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "SAJ.CH2.ESS", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class SajCh2EssImpl extends AbstractOpenemsModbusComponent
		implements SajCh2, HybridEss, ManagedSymmetricEss, SymmetricEss, ModbusComponent, OpenemsComponent,
		TimedataProvider, StartStoppable, EventHandler, ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(SajCh2EssImpl.class);
	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);
	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);
	private final Set<SajCharger> chargers = new CopyOnWriteArraySet<>();

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

	public SajCh2EssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				SajCh2.ChannelId.values() //
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
		this._setMaxApparentPower(config.ratedPower());
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
				 * Monitoring: inverter state and battery settings.
				 */
				new FC3ReadRegistersTask(0x7B04, Priority.HIGH, //
						m(SajCh2.ChannelId.INVERTER_WORK_MODE, new UnsignedWordElement(0x7B04))), //

				new FC3ReadRegistersTask(0x7B28, Priority.LOW, //
						m(SajCh2.ChannelId.APP_MODE, new SignedWordElement(0x7B28)), //
						new DummyRegisterElement(0x7B29, 0x7B2C), //
						m(SajCh2.ChannelId.BATTERY_STATUS, new UnsignedWordElement(0x7B2D)), //
						new DummyRegisterElement(0x7B2E), //
						m(SajCh2.ChannelId.BATTERY_CHARGE_SOC_UPPER_LIMIT, new SignedWordElement(0x7B2F)), //
						m(SajCh2.ChannelId.BATTERY_DISCHARGE_SOC_LOWER_LIMIT, new SignedWordElement(0x7B30))), //

				/*
				 * Monitoring: battery.
				 */
				new FC3ReadRegistersTask(0x7B81, Priority.HIGH, //
						m(SajCh2.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(0x7B81), SCALE_FACTOR_2), //
						m(SajCh2.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x7B82), SCALE_FACTOR_1), //
						new DummyRegisterElement(0x7B83, 0x7B84), //
						m(SajCh2.ChannelId.BATTERY_POWER, new SignedDoublewordElement(0x7B85)), //
						m(SajCh2.ChannelId.BATTERY_TEMPERATURE, new SignedWordElement(0x7B87),
								SCALE_FACTOR_MINUS_1), //
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(0x7B88), SCALE_FACTOR_MINUS_2)), //

				/*
				 * Monitoring: energy flow directions and system power values.
				 */
				new FC3ReadRegistersTask(0x7BEE, Priority.HIGH, //
						m(SajCh2.ChannelId.PV_DIRECTION, new UnsignedWordElement(0x7BEE)), //
						m(SajCh2.ChannelId.BATTERY_DIRECTION, new SignedWordElement(0x7BEF)), //
						m(SajCh2.ChannelId.GRID_DIRECTION, new SignedWordElement(0x7BF0)), //
						new DummyRegisterElement(0x7BF1), //
						m(SajCh2.ChannelId.SYSTEM_TOTAL_LOAD_POWER, new SignedDoublewordElement(0x7BF2)), //
						new DummyRegisterElement(0x7BF4, 0x7BFB), //
						m(SajCh2.ChannelId.TOTAL_PV_POWER, new SignedDoublewordElement(0x7BFC))), //

				new FC3ReadRegistersTask(0x7C04, Priority.HIGH, //
						m(SajCh2.ChannelId.TOTAL_INVERTER_POWER, new SignedDoublewordElement(0x7C04))), //

				/*
				 * Monitoring: energy counters.
				 */
				new FC3ReadRegistersTask(0x7C28, Priority.LOW, //
						m(HybridEss.ChannelId.DC_CHARGE_ENERGY, new UnsignedDoublewordElement(0x7C28),
								SCALE_FACTOR_1)), //

				new FC3ReadRegistersTask(0x7C30, Priority.LOW, //
						m(HybridEss.ChannelId.DC_DISCHARGE_ENERGY, new UnsignedDoublewordElement(0x7C30),
								SCALE_FACTOR_1)), //

				/*
				 * Remote EMS control: read back, so that the settings are only written when
				 * they actually change.
				 */
				new FC3ReadRegistersTask(0x8306, Priority.LOW, //
						m(SajCh2.ChannelId.EMS_SELL_LIMIT, new SignedWordElement(0x8306)), //
						m(SajCh2.ChannelId.EMS_BUY_LIMIT, new SignedWordElement(0x8307))), //

				new FC3ReadRegistersTask(0x8400, Priority.HIGH, //
						m(SajCh2.ChannelId.EMS_ENABLE, new UnsignedWordElement(0x8400)), //
						m(SajCh2.ChannelId.EMS_KEEP_TIME, new UnsignedWordElement(0x8401)), //
						m(SajCh2.ChannelId.EMS_INVERTER_POWER_REF, new SignedWordElement(0x8402)), //
						m(SajCh2.ChannelId.EMS_BATTERY_CHARGE_CURRENT_LIMIT, new UnsignedWordElement(0x8403),
								SCALE_FACTOR_2), //
						m(SajCh2.ChannelId.EMS_BATTERY_DISCHARGE_CURRENT_LIMIT, new UnsignedWordElement(0x8404),
								SCALE_FACTOR_2), //
						m(SajCh2.ChannelId.EMS_BATTERY_POWER_REF, new SignedWordElement(0x8405))), //

				/*
				 * Remote EMS control: write.
				 */
				new FC16WriteRegistersTask(0x8306, //
						m(SajCh2.ChannelId.EMS_SELL_LIMIT, new SignedWordElement(0x8306)), //
						m(SajCh2.ChannelId.EMS_BUY_LIMIT, new SignedWordElement(0x8307))), //

				new FC16WriteRegistersTask(0x8400, //
						m(SajCh2.ChannelId.EMS_ENABLE, new UnsignedWordElement(0x8400)), //
						m(SajCh2.ChannelId.EMS_KEEP_TIME, new UnsignedWordElement(0x8401)), //
						m(SajCh2.ChannelId.EMS_INVERTER_POWER_REF, new SignedWordElement(0x8402)), //
						m(SajCh2.ChannelId.EMS_BATTERY_CHARGE_CURRENT_LIMIT, new UnsignedWordElement(0x8403),
								SCALE_FACTOR_2), //
						m(SajCh2.ChannelId.EMS_BATTERY_DISCHARGE_CURRENT_LIMIT, new UnsignedWordElement(0x8404),
								SCALE_FACTOR_2), //
						m(SajCh2.ChannelId.EMS_BATTERY_POWER_REF, new SignedWordElement(0x8405))) //
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
			this.updatePowerAndEnergyChannels();
			this.updateAllowedPowerChannels();
			this.runStateMachine();
		}
		}
	}

	/**
	 * Calculates AC- and DC-side power and energy from the monitoring registers.
	 */
	private void updatePowerAndEnergyChannels() {
		final var dcDischargePower = SignedPower.applyDirection(//
				this.<IntegerReadChannel>channel(SajCh2.ChannelId.BATTERY_POWER).getNextValue().get(), //
				this.<IntegerReadChannel>channel(SajCh2.ChannelId.BATTERY_DIRECTION).getNextValue().get());
		final var acActivePower = this.<IntegerReadChannel>channel(SajCh2.ChannelId.TOTAL_INVERTER_POWER)
				.getNextValue().get();

		this._setDcDischargePower(dcDischargePower);
		this._setActivePower(acActivePower);
		setValue(this, SymmetricEss.ChannelId.GRID_MODE, mapGridMode(this.getInverterWorkMode()));

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
		this.channel(SajCh2.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());
		try {
			this.stateMachine.run(new Context(this, this.componentManager.getClock()));
			this.channel(SajCh2.ChannelId.RUN_FAILED).setNextValue(false);

		} catch (OpenemsNamedException e) {
			this.channel(SajCh2.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		if (this.config.controlMode() == ControlMode.INTERNAL) {
			// The inverter follows its own application mode
			return;
		}
		var pvProduction = this.calculatePvProduction();
		var result = ApplyPowerHandler.calculate(this.config.controlMode(), activePower, //
				pvProduction == null ? 0 : pvProduction, this.config.ratedPower());

		switch (result.emsEnable()) {
		case INVERTER_POWER_DISPATCH -> //
			this.getEmsInverterPowerRefChannel().setNextWriteValue(result.powerRefPerUnit());
		case BATTERY_POWER_DISPATCH -> //
			this.getEmsBatteryPowerRefChannel().setNextWriteValue(result.powerRefPerUnit());
		case INVALID, UNDEFINED -> {
			// nothing to dispatch
		}
		}
	}

	/**
	 * Enables or disables the remote EMS session; called by the State-Machine.
	 *
	 * <p>
	 * 'EMSKeepTime' makes the inverter fall back to its own application mode if
	 * OpenEMS stops writing, so the remote session is safe against a loss of
	 * communication.
	 *
	 * @param enabled true to hand control to OpenEMS
	 * @throws OpenemsNamedException on error
	 */
	public void setRemoteEmsEnabled(boolean enabled) throws OpenemsNamedException {
		if (!enabled || this.config.controlMode() == ControlMode.INTERNAL) {
			setWriteValueIfNotRead(this.getEmsEnableChannel(), EmsEnable.INVALID);
			return;
		}
		setWriteValueIfNotRead(this.getEmsKeepTimeChannel(), this.config.emsKeepTime());
		setWriteValueIfNotRead(this.getEmsEnableChannel(), switch (this.config.controlMode()) {
		case REMOTE_INVERTER -> EmsEnable.INVERTER_POWER_DISPATCH;
		case REMOTE_BATTERY -> EmsEnable.BATTERY_POWER_DISPATCH;
		case INTERNAL -> EmsEnable.INVALID;
		});
	}

	@Override
	public void addCharger(SajCharger charger) {
		this.chargers.add(charger);
	}

	@Override
	public void removeCharger(SajCharger charger) {
		this.chargers.remove(charger);
	}

	@Override
	public Integer calculatePvProduction() {
		Integer result = null;
		for (var charger : this.chargers) {
			result = sumInteger(result, charger.getActualPower().get());
		}
		if (result == null) {
			// Fall back to the total PV power that is reported by the inverter
			result = this.<IntegerReadChannel>channel(SajCh2.ChannelId.TOTAL_PV_POWER).getNextValue().get();
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
		// One step of the remote EMS Set-Point is 0.01 % of the nominal power
		return Math.max(1, this.config.ratedPower() / ApplyPowerHandler.PER_UNIT);
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
	 * Maps the SAJ 'MPVMode' to the OpenEMS {@link GridMode}.
	 *
	 * @param workMode the {@link InverterWorkMode}
	 * @return the {@link GridMode}
	 */
	protected static GridMode mapGridMode(InverterWorkMode workMode) {
		return switch (workMode) {
		case GRID_CONNECTED, GRID_LOADED -> GridMode.ON_GRID;
		case OFF_GRID -> GridMode.OFF_GRID;
		case DEBUG, FAULT, INITIALIZATION, RESET, SELF_TEST, UNDEFINED, UPGRADE, WAITING -> GridMode.UNDEFINED;
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
				ModbusSlaveNatureTable.of(SajCh2.class, accessMode, 100) //
						.build());
	}
}
