package io.openems.edge.growatt.ess;

import static io.openems.common.utils.IntUtils.sumInteger;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.channel.ChannelUtils.setWriteValueIfNotRead;
import static io.openems.edge.common.type.TypeUtils.subtract;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
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
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.growatt.charger.GrowattCharger;
import io.openems.edge.growatt.common.AllowedPowerHandler;
import io.openems.edge.growatt.common.ApplyPowerHandler;
import io.openems.edge.growatt.common.GrowattSph;
import io.openems.edge.growatt.common.WriteThrottle;
import io.openems.edge.growatt.common.enums.ControlMode;
import io.openems.edge.growatt.common.enums.SystemWorkMode;
import io.openems.edge.growatt.ess.statemachine.Context;
import io.openems.edge.growatt.ess.statemachine.StateMachine;
import io.openems.edge.growatt.ess.statemachine.StateMachine.State;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Implements the Growatt SPH hybrid inverter (e.g. SPH4600) as a fully
 * controllable Energy Storage System.
 *
 * <p>
 * Register addresses are taken from the Growatt Inverter Modbus RTU Protocol_II
 * (storage section); see readme.adoc for details on which addresses are
 * verified in the field.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Growatt.SPH.ESS", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class GrowattSphEssImpl extends AbstractOpenemsModbusComponent
		implements GrowattSph, HybridEss, ManagedSymmetricEss, SymmetricEss, ModbusComponent, OpenemsComponent,
		TimedataProvider, StartStoppable, EventHandler, ModbusSlave {

	/**
	 * Time-slot that is reserved for OpenEMS; 00:00 until 23:59.
	 */
	private static final int SLOT_START = 0x0000;
	private static final int SLOT_STOP = 0x173B;

	private final Logger log = LoggerFactory.getLogger(GrowattSphEssImpl.class);
	private final StateMachine stateMachine = new StateMachine(State.UNDEFINED);
	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);
	private final Set<GrowattCharger> chargers = new CopyOnWriteArraySet<>();

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
	private WriteThrottle writeThrottle;

	public GrowattSphEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				GrowattSph.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		this.writeThrottle = new WriteThrottle(this.componentManager.getClock(),
				Duration.ofSeconds(Math.max(0, config.minimumWriteInterval())));
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
				 * Input-Registers: inverter and PV values.
				 */
				new FC4ReadInputRegistersTask(0, Priority.HIGH, //
						m(GrowattSph.ChannelId.INVERTER_STATUS, new UnsignedWordElement(0)), //
						m(GrowattSph.ChannelId.PV_TOTAL_POWER, new UnsignedDoublewordElement(1),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(35, Priority.LOW, //
						m(GrowattSph.ChannelId.AC_OUTPUT_POWER, new UnsignedDoublewordElement(35),
								SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.GRID_FREQUENCY, new UnsignedWordElement(37), SCALE_FACTOR_1)), //

				new FC4ReadInputRegistersTask(93, Priority.LOW, //
						m(GrowattSph.ChannelId.INVERTER_TEMPERATURE, new UnsignedWordElement(93),
								SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.IPM_TEMPERATURE, new UnsignedWordElement(94), SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.BOOST_TEMPERATURE, new UnsignedWordElement(95),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(118, Priority.LOW, //
						m(GrowattSph.ChannelId.ACTUAL_PRIORITY_MODE, new UnsignedWordElement(118)), //
						m(GrowattSph.ChannelId.BATTERY_TYPE, new UnsignedWordElement(119))), //

				/*
				 * Input-Registers: storage values.
				 */
				new FC4ReadInputRegistersTask(1000, Priority.LOW, //
						m(GrowattSph.ChannelId.SYSTEM_WORK_MODE, new UnsignedWordElement(1000))), //

				new FC4ReadInputRegistersTask(1009, Priority.HIGH, //
						m(GrowattSph.ChannelId.BATTERY_DISCHARGE_POWER, new UnsignedDoublewordElement(1009),
								SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.BATTERY_CHARGE_POWER, new UnsignedDoublewordElement(1011),
								SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(1013), SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(1014))), //

				new FC4ReadInputRegistersTask(1021, Priority.HIGH, //
						m(GrowattSph.ChannelId.P_AC_TO_USER_TOTAL, new UnsignedDoublewordElement(1021),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(1029, Priority.HIGH, //
						m(GrowattSph.ChannelId.P_AC_TO_GRID_TOTAL, new UnsignedDoublewordElement(1029),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(1037, Priority.LOW, //
						m(GrowattSph.ChannelId.P_LOCAL_LOAD_TOTAL, new UnsignedDoublewordElement(1037),
								SCALE_FACTOR_MINUS_1), //
						new DummyRegisterElement(1039), //
						m(GrowattSph.ChannelId.BATTERY_TEMPERATURE, new UnsignedWordElement(1040),
								SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.BATTERY_STATUS, new UnsignedWordElement(1041))), //

				new FC4ReadInputRegistersTask(1054, Priority.LOW, //
						m(HybridEss.ChannelId.DC_DISCHARGE_ENERGY, new UnsignedDoublewordElement(1054),
								SCALE_FACTOR_2), //
						new DummyRegisterElement(1056, 1057), //
						m(HybridEss.ChannelId.DC_CHARGE_ENERGY, new UnsignedDoublewordElement(1058), SCALE_FACTOR_2)), //

				/*
				 * Holding-Registers: read back the remote control settings, so that they are
				 * only written when they actually change.
				 */
				new FC3ReadRegistersTask(0, Priority.LOW, //
						m(GrowattSph.ChannelId.POWER_ON_OFF, new UnsignedWordElement(0))), //

				new FC3ReadRegistersTask(3, Priority.LOW, //
						m(GrowattSph.ChannelId.ACTIVE_POWER_RATE, new UnsignedWordElement(3)), //
						m(GrowattSph.ChannelId.REACTIVE_POWER_RATE, new UnsignedWordElement(4))), //

				new FC3ReadRegistersTask(122, Priority.LOW, //
						m(GrowattSph.ChannelId.EXPORT_LIMIT_ENABLE, new UnsignedWordElement(122)), //
						m(GrowattSph.ChannelId.EXPORT_LIMIT_POWER_RATE, new UnsignedWordElement(123))), //

				new FC3ReadRegistersTask(1044, Priority.HIGH, //
						m(GrowattSph.ChannelId.SET_PRIORITY_MODE, new UnsignedWordElement(1044))), //

				new FC3ReadRegistersTask(1070, Priority.HIGH, //
						m(GrowattSph.ChannelId.GRID_FIRST_DISCHARGE_POWER_RATE, new UnsignedWordElement(1070)), //
						m(GrowattSph.ChannelId.GRID_FIRST_STOP_SOC, new UnsignedWordElement(1071))), //

				new FC3ReadRegistersTask(1080, Priority.LOW, //
						m(GrowattSph.ChannelId.GRID_FIRST_SLOT_START, new UnsignedWordElement(1080)), //
						m(GrowattSph.ChannelId.GRID_FIRST_SLOT_STOP, new UnsignedWordElement(1081)), //
						m(GrowattSph.ChannelId.GRID_FIRST_SLOT_ENABLED, new UnsignedWordElement(1082))), //

				new FC3ReadRegistersTask(1090, Priority.HIGH, //
						m(GrowattSph.ChannelId.BATTERY_FIRST_CHARGE_POWER_RATE, new UnsignedWordElement(1090)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_STOP_SOC, new UnsignedWordElement(1091)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_AC_CHARGE, new UnsignedWordElement(1092))), //

				new FC3ReadRegistersTask(1100, Priority.LOW, //
						m(GrowattSph.ChannelId.BATTERY_FIRST_SLOT_START, new UnsignedWordElement(1100)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_SLOT_STOP, new UnsignedWordElement(1101)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_SLOT_ENABLED, new UnsignedWordElement(1102))), //

				/*
				 * Holding-Registers: write. Growatt requires FC16 even for single registers.
				 */
				new FC16WriteRegistersTask(0, //
						m(GrowattSph.ChannelId.POWER_ON_OFF, new UnsignedWordElement(0))), //

				new FC16WriteRegistersTask(3, //
						m(GrowattSph.ChannelId.ACTIVE_POWER_RATE, new UnsignedWordElement(3)), //
						m(GrowattSph.ChannelId.REACTIVE_POWER_RATE, new UnsignedWordElement(4))), //

				new FC16WriteRegistersTask(122, //
						m(GrowattSph.ChannelId.EXPORT_LIMIT_ENABLE, new UnsignedWordElement(122)), //
						m(GrowattSph.ChannelId.EXPORT_LIMIT_POWER_RATE, new UnsignedWordElement(123))), //

				new FC16WriteRegistersTask(1044, //
						m(GrowattSph.ChannelId.SET_PRIORITY_MODE, new UnsignedWordElement(1044))), //

				new FC16WriteRegistersTask(1070, //
						m(GrowattSph.ChannelId.GRID_FIRST_DISCHARGE_POWER_RATE, new UnsignedWordElement(1070)), //
						m(GrowattSph.ChannelId.GRID_FIRST_STOP_SOC, new UnsignedWordElement(1071))), //

				new FC16WriteRegistersTask(1080, //
						m(GrowattSph.ChannelId.GRID_FIRST_SLOT_START, new UnsignedWordElement(1080)), //
						m(GrowattSph.ChannelId.GRID_FIRST_SLOT_STOP, new UnsignedWordElement(1081)), //
						m(GrowattSph.ChannelId.GRID_FIRST_SLOT_ENABLED, new UnsignedWordElement(1082))), //

				new FC16WriteRegistersTask(1090, //
						m(GrowattSph.ChannelId.BATTERY_FIRST_CHARGE_POWER_RATE, new UnsignedWordElement(1090)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_STOP_SOC, new UnsignedWordElement(1091)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_AC_CHARGE, new UnsignedWordElement(1092))), //

				new FC16WriteRegistersTask(1100, //
						m(GrowattSph.ChannelId.BATTERY_FIRST_SLOT_START, new UnsignedWordElement(1100)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_SLOT_STOP, new UnsignedWordElement(1101)), //
						m(GrowattSph.ChannelId.BATTERY_FIRST_SLOT_ENABLED, new UnsignedWordElement(1102))) //
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
	 * Calculates AC- and DC-side power and energy from the battery power values
	 * and the PV production of the registered Chargers.
	 */
	private void updatePowerAndEnergyChannels() {
		final var pvProduction = this.calculatePvProduction();
		final Integer dcDischargePower = subtract(//
				this.<IntegerReadChannel>channel(GrowattSph.ChannelId.BATTERY_DISCHARGE_POWER).getNextValue().get(), //
				this.<IntegerReadChannel>channel(GrowattSph.ChannelId.BATTERY_CHARGE_POWER).getNextValue().get());
		final var acActivePower = sumInteger(pvProduction, dcDischargePower);

		this._setDcDischargePower(dcDischargePower);
		this._setActivePower(acActivePower);
		setValue(this, SymmetricEss.ChannelId.GRID_MODE, mapGridMode(
				this.<EnumReadChannel>channel(GrowattSph.ChannelId.SYSTEM_WORK_MODE).getNextValue().asEnum()));

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
		this.channel(GrowattSph.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());
		try {
			this.stateMachine.run(new Context(this, this.componentManager.getClock()));
			this.channel(GrowattSph.ChannelId.RUN_FAILED).setNextValue(false);

		} catch (OpenemsNamedException e) {
			this.channel(GrowattSph.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		if (this.config.controlMode() == ControlMode.INTERNAL) {
			// The inverter follows its own schedule
			return;
		}
		var result = ApplyPowerHandler.calculate(activePower, //
				this.calculatePvProduction() == null ? 0 : this.calculatePvProduction(), //
				this.config.maxBatteryChargePower(), this.config.maxBatteryDischargePower(),
				this.config.powerRateStep());

		this.applySetPoint(result);
	}

	/**
	 * Writes the calculated Set-Point to the inverter.
	 *
	 * <p>
	 * All values are written via
	 * {@link io.openems.edge.common.channel.ChannelUtils#setWriteValueIfNotRead},
	 * i.e. only if they differ from the value that was read back from the device.
	 * Additionally a {@link WriteThrottle} enforces a minimum interval between two
	 * writes, because the Growatt inverter stores these settings in non-volatile
	 * memory.
	 *
	 * @param result the {@link ApplyPowerHandler.Result}
	 * @throws OpenemsNamedException on error
	 */
	private void applySetPoint(ApplyPowerHandler.Result result) throws OpenemsNamedException {
		if (!this.writeThrottle.tryRelease()) {
			return;
		}

		// Make sure the time-slots that are reserved for OpenEMS cover the whole day
		setWriteValueIfNotRead(this.getGridFirstSlotStartChannel(), SLOT_START);
		setWriteValueIfNotRead(this.getGridFirstSlotStopChannel(), SLOT_STOP);
		setWriteValueIfNotRead(this.getBatteryFirstSlotStartChannel(), SLOT_START);
		setWriteValueIfNotRead(this.getBatteryFirstSlotStopChannel(), SLOT_STOP);

		switch (result.priorityMode()) {
		case BATTERY_FIRST -> {
			setWriteValueIfNotRead(this.getBatteryFirstChargePowerRateChannel(), result.powerRatePercent());
			setWriteValueIfNotRead(this.getBatteryFirstAcChargeChannel(), result.acChargeEnabled());
			this.setSlotEnabled(true, false);
		}
		case GRID_FIRST -> {
			setWriteValueIfNotRead(this.getGridFirstDischargePowerRateChannel(), result.powerRatePercent());
			setWriteValueIfNotRead(this.getBatteryFirstAcChargeChannel(), false);
			this.setSlotEnabled(false, true);
		}
		case LOAD_FIRST, UNDEFINED -> {
			setWriteValueIfNotRead(this.getBatteryFirstAcChargeChannel(), false);
			this.setSlotEnabled(false, false);
		}
		}

		setWriteValueIfNotRead(this.getSetPriorityModeChannel(), result.priorityMode());
	}

	/**
	 * Enables exactly one of the two time-slots that are reserved for OpenEMS.
	 *
	 * @param batteryFirst true to activate the 'Battery-First' time-slot
	 * @param gridFirst    true to activate the 'Grid-First' time-slot
	 * @throws OpenemsNamedException on error
	 */
	private void setSlotEnabled(boolean batteryFirst, boolean gridFirst) throws OpenemsNamedException {
		setWriteValueIfNotRead(this.getBatteryFirstSlotEnabledChannel(), batteryFirst);
		setWriteValueIfNotRead(this.getGridFirstSlotEnabledChannel(), gridFirst);
	}

	/**
	 * Switches the inverter on or off; called by the State-Machine.
	 *
	 * @param on true to switch the inverter on
	 * @throws OpenemsNamedException on error
	 */
	public void setPowerOn(boolean on) throws OpenemsNamedException {
		setWriteValueIfNotRead(this.getPowerOnOffChannel(), on);
	}

	@Override
	public void addCharger(GrowattCharger charger) {
		this.chargers.add(charger);
	}

	@Override
	public void removeCharger(GrowattCharger charger) {
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
			result = this.<IntegerReadChannel>channel(GrowattSph.ChannelId.PV_TOTAL_POWER).getNextValue().get();
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
		// The power rate is set in percent of the nominal battery power
		return Math.max(1, this.config.maxBatteryDischargePower() * Math.max(1, this.config.powerRateStep()) / 100);
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
	 * Maps the Growatt 'System Work Mode' to the OpenEMS {@link GridMode}.
	 *
	 * @param systemWorkMode the {@link SystemWorkMode}
	 * @return the {@link GridMode}
	 */
	protected static GridMode mapGridMode(SystemWorkMode systemWorkMode) {
		return switch (systemWorkMode) {
		case PV_AND_BATTERY_ONLINE, BATTERY_ONLINE -> GridMode.ON_GRID;
		case PV_OFFLINE, BATTERY_OFFLINE -> GridMode.OFF_GRID;
		case WAITING, SELF_TEST, RESERVED, FAULT, FLASH, UNDEFINED -> GridMode.UNDEFINED;
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
				ModbusSlaveNatureTable.of(GrowattSph.class, accessMode, 100) //
						.build());
	}
}
