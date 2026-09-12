package io.openems.edge.growatt.ess;

import static io.openems.common.utils.IntUtils.sumInteger;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ModbusUtils.abortAfterNthErrors;
import static io.openems.edge.bridge.modbus.api.ModbusUtils.readElementsUntil;
import static io.openems.edge.bridge.modbus.api.ModbusUtils.restartAfterChannelChange;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.channel.ChannelUtils.setWriteValueIfNotRead;
import static io.openems.edge.common.type.TypeUtils.subtract;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
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

import com.google.common.annotations.VisibleForTesting;

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
import io.openems.edge.bridge.modbus.api.task.Task;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.WriteChannel;
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
import io.openems.edge.growatt.common.LimitedWriteAttempts;
import io.openems.edge.growatt.common.VppAllowedPowerHandler;
import io.openems.edge.growatt.common.VppApplyPowerHandler;
import io.openems.edge.growatt.common.VppPowerHandler;
import io.openems.edge.growatt.common.VppSetPointVerifier;
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

	/**
	 * Number of failed reads on the VPP register bank until the probe gives up.
	 */
	private static final int VPP_PROBE_ATTEMPTS = 5;

	/**
	 * 'Remote power control charging time' 0 means: control until revoked.
	 */
	private static final int VPP_UNLIMITED_DURATION = 0;

	/**
	 * How often an optional VPP setting is written before the Component gives up.
	 */
	private static final int VPP_SETTING_WRITE_ATTEMPTS = 3;

	/**
	 * How many Cycles the Set-Point read-back may disagree before the VPP control
	 * is considered ineffective.
	 */
	private static final int VPP_SET_POINT_MISMATCHES = 5;

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

	private final AtomicBoolean vppAvailable = new AtomicBoolean(false);
	private final LimitedWriteAttempts vppSettingAttempts = new LimitedWriteAttempts(
			VPP_SETTING_WRITE_ATTEMPTS);
	private final VppSetPointVerifier vppSetPointVerifier = new VppSetPointVerifier(VPP_SET_POINT_MISMATCHES);
	private final AtomicReference<Integer> lastVppSetPoint = new AtomicReference<>(null);

	private Config config;
	private WriteThrottle writeThrottle;
	private ModbusProtocol modbusProtocol;

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
		this.modbusProtocol = new ModbusProtocol(this, //
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

		/*
		 * The VPP register bank only exists on firmware that implements the Growatt
		 * VPP protocol. Probe it with the equipment type code (30000): it is the first
		 * register of the bank and documented as a single register, so it cannot fail
		 * because of a partial read of a multi-register group. If the inverter
		 * answers, the VPP Tasks are added; otherwise the probe gives up after a few
		 * errors and the priority and time-slot control is kept. The probe is
		 * restarted whenever the Modbus connection recovers, so a device that was
		 * temporarily unreachable is not permanently treated as 'without VPP'.
		 */
		readElementsUntil(this.modbusProtocol, abortAfterNthErrors(VPP_PROBE_ATTEMPTS),
				restartAfterChannelChange(this.getModbusCommunicationFailedChannel()),
				executeStateConsumer -> new FC3ReadRegistersTask(executeStateConsumer, 30000, Priority.LOW,
						m(GrowattSph.ChannelId.VPP_DEVICE_TYPE_CODE, new UnsignedWordElement(30000)) //
								.onUpdateCallback(this::onVppProbeResult)));

		return this.modbusProtocol;
	}

	/**
	 * Is this a plausible answer of the VPP probe?.
	 *
	 * <p>
	 * Some inverters answer a read on the VPP register bank with zeros instead of
	 * rejecting it, even though they do not implement the protocol. Table 3-1 of
	 * the protocol document has no equipment type code zero, so a zero means that
	 * the register bank is answering but empty.
	 *
	 * @param deviceTypeCode the value of Holding-Register 30000
	 * @return true if the inverter reported an equipment type code
	 */
	protected static boolean isVppProbeSuccessful(Object deviceTypeCode) {
		return deviceTypeCode instanceof Integer dtc && dtc > 0;
	}

	/**
	 * Evaluates the answer of the VPP probe and adds the VPP Tasks on success.
	 *
	 * <p>
	 * A missing answer only raises the warning as long as the VPP register bank
	 * has never answered; once it is established, a single failed read does not
	 * revoke it.
	 *
	 * @param deviceTypeCode the value of Holding-Register 30000; null if the
	 *                       inverter did not answer
	 */
	protected synchronized void onVppProbeResult(Object deviceTypeCode) {
		if (!isVppProbeSuccessful(deviceTypeCode)) {
			this.channel(GrowattSph.ChannelId.VPP_NOT_AVAILABLE)
					.setNextValue(!this.vppAvailable.get() && this.config.controlMode().isVpp());
			return;
		}
		this.channel(GrowattSph.ChannelId.VPP_NOT_AVAILABLE).setNextValue(false);
		if (this.vppAvailable.getAndSet(true)) {
			// Tasks have been added before
			return;
		}
		this.modbusProtocol.addTasks(this.createVppTasks());
	}

	/**
	 * Creates the Modbus Tasks of the Growatt VPP protocol (register bank
	 * 30000-32099).
	 *
	 * <p>
	 * These Tasks are only added if the inverter answered on the VPP register
	 * bank, see {@link #defineModbusProtocol()}.
	 *
	 * @return the {@link Task}s
	 */
	private Task[] createVppTasks() {
		return new Task[] { //
				/*
				 * Hold-Registers: device information.
				 */
				// 30099 is documented with a length of 15, which collides with 30100; the
				// contiguous layout of the register bank shows that it is a single register
				new FC3ReadRegistersTask(30099, Priority.LOW, //
						m(GrowattSph.ChannelId.VPP_PROTOCOL_VERSION, new UnsignedWordElement(30099))), //

				new FC3ReadRegistersTask(30016, Priority.LOW, //
						m(GrowattSph.ChannelId.VPP_RATED_POWER, new UnsignedDoublewordElement(30016),
								SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.VPP_MAX_ACTIVE_POWER, new UnsignedDoublewordElement(30018),
								SCALE_FACTOR_MINUS_1), //
						new DummyRegisterElement(30020, 30025), //
						m(GrowattSph.ChannelId.VPP_BDC_RATED_POWER, new UnsignedDoublewordElement(30026),
								SCALE_FACTOR_MINUS_1)), //

				/*
				 * Hold-Registers: remote control. Read back, so that the settings that are
				 * stored in non-volatile memory are only written when they actually change.
				 */
				new FC3ReadRegistersTask(30100, Priority.LOW, //
						m(GrowattSph.ChannelId.VPP_CONTROL_AUTHORITY, new UnsignedWordElement(30100))), //

				new FC3ReadRegistersTask(30203, Priority.LOW, //
						m(GrowattSph.ChannelId.VPP_EMS_FAILURE_TIME, new UnsignedWordElement(30203)), //
						m(GrowattSph.ChannelId.VPP_EMS_FAILURE_ENABLE, new UnsignedWordElement(30204))), //

				new FC3ReadRegistersTask(30404, Priority.LOW, //
						m(GrowattSph.ChannelId.VPP_CHARGE_CUT_OFF_SOC, new UnsignedWordElement(30404)), //
						m(GrowattSph.ChannelId.VPP_DISCHARGE_CUT_OFF_SOC, new UnsignedWordElement(30405))), //

				new FC3ReadRegistersTask(30407, Priority.HIGH, //
						m(GrowattSph.ChannelId.VPP_REMOTE_POWER_ENABLE, new UnsignedWordElement(30407)), //
						m(GrowattSph.ChannelId.VPP_REMOTE_POWER_DURATION, new UnsignedWordElement(30408)), //
						m(GrowattSph.ChannelId.VPP_REMOTE_POWER, new SignedWordElement(30409))), //

				new FC3ReadRegistersTask(30474, Priority.HIGH, //
						m(GrowattSph.ChannelId.VPP_ACTUAL_CONTROL_POWER, new SignedWordElement(30474))), //

				/*
				 * Hold-Registers: write.
				 */
				new FC16WriteRegistersTask(30100, //
						m(GrowattSph.ChannelId.VPP_CONTROL_AUTHORITY, new UnsignedWordElement(30100))), //

				new FC16WriteRegistersTask(30203, //
						m(GrowattSph.ChannelId.VPP_EMS_FAILURE_TIME, new UnsignedWordElement(30203)), //
						m(GrowattSph.ChannelId.VPP_EMS_FAILURE_ENABLE, new UnsignedWordElement(30204))), //

				new FC16WriteRegistersTask(30404, //
						m(GrowattSph.ChannelId.VPP_CHARGE_CUT_OFF_SOC, new UnsignedWordElement(30404)), //
						m(GrowattSph.ChannelId.VPP_DISCHARGE_CUT_OFF_SOC, new UnsignedWordElement(30405))), //

				new FC16WriteRegistersTask(30407, //
						m(GrowattSph.ChannelId.VPP_REMOTE_POWER_ENABLE, new UnsignedWordElement(30407)), //
						m(GrowattSph.ChannelId.VPP_REMOTE_POWER_DURATION, new UnsignedWordElement(30408)), //
						m(GrowattSph.ChannelId.VPP_REMOTE_POWER, new SignedWordElement(30409))), //

				/*
				 * Input-Registers: working status.
				 */
				new FC4ReadInputRegistersTask(31000, Priority.HIGH, //
						m(GrowattSph.ChannelId.VPP_WORKING_STATE, new UnsignedWordElement(31000)), //
						m(GrowattSph.ChannelId.VPP_BATTERY_WORKING_STATE, new UnsignedWordElement(31001)), //
						m(GrowattSph.ChannelId.VPP_PRIORITY, new UnsignedWordElement(31002)), //
						new DummyRegisterElement(31003, 31004), //
						m(GrowattSph.ChannelId.VPP_FAULT_CODE, new UnsignedWordElement(31005)), //
						m(GrowattSph.ChannelId.VPP_FAULT_SUB_CODE, new UnsignedWordElement(31006)), //
						m(GrowattSph.ChannelId.VPP_ALARM_CODE, new UnsignedWordElement(31007)), //
						m(GrowattSph.ChannelId.VPP_ALARM_SUB_CODE, new UnsignedWordElement(31008))), //

				new FC4ReadInputRegistersTask(31058, Priority.HIGH, //
						m(GrowattSph.ChannelId.VPP_PV_POWER, new SignedDoublewordElement(31058),
								SCALE_FACTOR_MINUS_1)), //

				/*
				 * Input-Registers: AC information.
				 */
				new FC4ReadInputRegistersTask(31100, Priority.HIGH, //
						m(GrowattSph.ChannelId.VPP_AC_ACTIVE_POWER, new SignedDoublewordElement(31100),
								SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.VPP_AC_REACTIVE_POWER, new SignedDoublewordElement(31102),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(31114, Priority.LOW, //
						m(GrowattSph.ChannelId.VPP_INVERTER_TEMPERATURE, new SignedWordElement(31114),
								SCALE_FACTOR_MINUS_1)), //

				/*
				 * Input-Registers: battery information.
				 */
				new FC4ReadInputRegistersTask(31200, Priority.HIGH, //
						m(GrowattSph.ChannelId.VPP_BATTERY_POWER, new SignedDoublewordElement(31200),
								SCALE_FACTOR_MINUS_1), //
						new DummyRegisterElement(31202, 31203), //
						m(HybridEss.ChannelId.DC_CHARGE_ENERGY, new UnsignedDoublewordElement(31204),
								SCALE_FACTOR_2), //
						new DummyRegisterElement(31206, 31207), //
						m(HybridEss.ChannelId.DC_DISCHARGE_ENERGY, new UnsignedDoublewordElement(31208),
								SCALE_FACTOR_2), //
						m(GrowattSph.ChannelId.VPP_BATTERY_MAX_CHARGE_POWER,
								new UnsignedDoublewordElement(31210), SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.VPP_BATTERY_MAX_DISCHARGE_POWER,
								new UnsignedDoublewordElement(31212), SCALE_FACTOR_MINUS_1), //
						m(GrowattSph.ChannelId.VPP_BATTERY_VOLTAGE, new SignedWordElement(31214),
								SCALE_FACTOR_2), //
						m(GrowattSph.ChannelId.VPP_BATTERY_CURRENT, new SignedDoublewordElement(31215),
								SCALE_FACTOR_2), //
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(31217)), //
						m(GrowattSph.ChannelId.VPP_BATTERY_STATE_OF_HEALTH, new UnsignedWordElement(31218))), //

				new FC4ReadInputRegistersTask(31223, Priority.LOW, //
						m(GrowattSph.ChannelId.VPP_BATTERY_TEMPERATURE, new SignedWordElement(31223),
								SCALE_FACTOR_MINUS_1)) //
		};
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
			this.verifyVppSetPoint();
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
		final var vppBatteryPower = this.<IntegerReadChannel>channel(GrowattSph.ChannelId.VPP_BATTERY_POWER)
				.getNextValue().get();
		final Integer dcDischargePower = vppBatteryPower != null //
				// The VPP register bank reports the battery power in a single register
				? VppPowerHandler.toDcDischargePower(vppBatteryPower)
				: subtract(//
						this.<IntegerReadChannel>channel(GrowattSph.ChannelId.BATTERY_DISCHARGE_POWER).getNextValue()
								.get(), //
						this.<IntegerReadChannel>channel(GrowattSph.ChannelId.BATTERY_CHARGE_POWER).getNextValue()
								.get());
		final var acActivePower = VppPowerHandler.selectAcActivePower(//
				this.<IntegerReadChannel>channel(GrowattSph.ChannelId.VPP_AC_ACTIVE_POWER).getNextValue().get(), //
				pvProduction, dcDischargePower);

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
	 * Checks whether the inverter applies the VPP Set-Point.
	 *
	 * <p>
	 * If the read-back in register 30474 does not follow register 30409, the
	 * remote control has no effect - which a plain Modbus write cannot detect,
	 * because an inverter without the VPP register bank acknowledges the write
	 * anyway. In that case the warning is raised and
	 * {@link #applyPower(int, int)} falls back to the priority and time-slot
	 * control.
	 */
	private void verifyVppSetPoint() {
		if (!this.vppAvailable.get() || !this.config.controlMode().isVpp()) {
			return;
		}
		var applied = this.vppSetPointVerifier.verify(this.lastVppSetPoint.get(), //
				this.<IntegerReadChannel>channel(GrowattSph.ChannelId.VPP_ACTUAL_CONTROL_POWER).getNextValue().get());
		this.channel(GrowattSph.ChannelId.VPP_SET_POINT_NOT_APPLIED).setNextValue(!applied);
	}

	/**
	 * Publishes the allowed charge and discharge power for the Power solver.
	 */
	private void updateAllowedPowerChannels() {
		final var allowed = this.vppAvailable.get() //
				// The VPP register bank reports the dynamic limits of the battery
				? VppAllowedPowerHandler.calculate(this.getSoc().get(), this.config.minSoc(), this.config.maxSoc(), //
						this.<IntegerReadChannel>channel(GrowattSph.ChannelId.VPP_BATTERY_MAX_CHARGE_POWER)
								.getNextValue().get(), //
						this.<IntegerReadChannel>channel(GrowattSph.ChannelId.VPP_BATTERY_MAX_DISCHARGE_POWER)
								.getNextValue().get(), //
						this.config.maxBatteryChargePower(), this.config.maxBatteryDischargePower())
				: toVppResult(AllowedPowerHandler.calculate(this.getSoc().get(), this.config.minSoc(),
						this.config.maxSoc(), this.config.maxBatteryChargePower(),
						this.config.maxBatteryDischargePower()));

		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, allowed.allowedChargePower());
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, allowed.allowedDischargePower());
	}

	private static VppAllowedPowerHandler.Result toVppResult(AllowedPowerHandler.Result result) {
		return new VppAllowedPowerHandler.Result(result.allowedChargePower(), result.allowedDischargePower());
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
		if (!this.config.controlMode().isRemote()) {
			// The inverter follows its own schedule
			return;
		}
		final var pvProduction = this.calculatePvProduction();
		final var pv = pvProduction == null ? 0 : pvProduction;

		if (this.config.controlMode().isVpp() && this.vppAvailable.get()
				&& !this.vppSetPointVerifier.hasFailed()) {
			this.applyVppSetPoint(VppApplyPowerHandler.calculate(activePower, pv, this.getBdcRatedPower()));
			return;
		}
		this.applySetPoint(ApplyPowerHandler.calculate(activePower, pv, //
				this.config.maxBatteryChargePower(), this.config.maxBatteryDischargePower(),
				this.config.powerRateStep()));
	}

	/**
	 * Gets the reference power for the VPP remote power percentage.
	 *
	 * <p>
	 * The configured value takes precedence; if it is zero, the rated
	 * charge/discharge power of the battery DC/DC converter that the inverter
	 * reports in Holding-Register 30026 is used.
	 *
	 * @return the reference power in [W]
	 */
	protected int getBdcRatedPower() {
		if (this.config.bdcRatedPower() > 0) {
			return this.config.bdcRatedPower();
		}
		var reported = this.<IntegerReadChannel>channel(GrowattSph.ChannelId.VPP_BDC_RATED_POWER).getNextValue().get();
		if (reported != null && reported > 0) {
			return reported;
		}
		return this.config.maxBatteryDischargePower();
	}

	/**
	 * Writes the calculated Set-Point through the VPP register bank.
	 *
	 * <p>
	 * 'Remote power control enable' (30407), 'Remote power control charging time'
	 * (30408) and 'Remote charge and discharge power' (30409) are explicitly not
	 * stored in non-volatile memory, so they are written in every Cycle without a
	 * throttle.
	 *
	 * @param result the {@link VppApplyPowerHandler.Result}
	 * @throws OpenemsNamedException on error
	 */
	private void applyVppSetPoint(VppApplyPowerHandler.Result result) throws OpenemsNamedException {
		this.applyOptionalVppSettings();

		this.getVppRemotePowerEnableChannel().setNextWriteValue(result.remoteControlEnabled());
		this.getVppRemotePowerDurationChannel().setNextWriteValue(VPP_UNLIMITED_DURATION);
		this.getVppRemotePowerChannel().setNextWriteValue(result.powerPercent());
		this.lastVppSetPoint.set(result.powerPercent());
	}

	/**
	 * Writes the VPP settings that are not part of every protocol version.
	 *
	 * <p>
	 * The VPP register bank grew over several protocol versions, and the inverters
	 * answer a read on a register they do not implement with zero instead of
	 * rejecting it. A setting whose read-back never reaches the desired value is
	 * therefore indistinguishable from a missing register, so the number of write
	 * attempts is limited. Once they are used up, the Component stops trying and
	 * raises {@link GrowattSph.ChannelId#VPP_SETTINGS_NOT_APPLIED}; the Set-Point
	 * registers keep working.
	 *
	 * @throws OpenemsNamedException on error
	 */
	private void applyOptionalVppSettings() throws OpenemsNamedException {
		final var desired = List.<Map.Entry<WriteChannel<?>, Object>>of(//
				Map.entry(this.getVppControlAuthorityChannel(), true), //
				Map.entry(this.getVppEmsFailureTimeChannel(), this.config.emsFailureTime()), //
				Map.entry(this.getVppEmsFailureEnableChannel(), this.config.emsFailureTime() > 0));

		if (desired.stream().allMatch(e -> Objects.equals(e.getKey().value().get(), e.getValue()))) {
			// Everything is already as configured
			this.vppSettingAttempts.reset();
			this.channel(GrowattSph.ChannelId.VPP_SETTINGS_NOT_APPLIED).setNextValue(false);
			return;
		}
		if (!this.vppSettingAttempts.tryAttempt()) {
			this.channel(GrowattSph.ChannelId.VPP_SETTINGS_NOT_APPLIED).setNextValue(true);
			return;
		}
		for (var entry : desired) {
			writeUnchecked(entry.getKey(), entry.getValue());
		}
	}

	@SuppressWarnings("unchecked")
	private static void writeUnchecked(WriteChannel<?> channel, Object value) throws OpenemsNamedException {
		((WriteChannel<Object>) channel).setNextWriteValue(value);
	}

	/**
	 * Hands control back to the inverter; called when the ESS is stopped.
	 *
	 * @throws OpenemsNamedException on error
	 */
	public void releaseVppControl() throws OpenemsNamedException {
		if (!this.vppAvailable.get()) {
			return;
		}
		this.getVppRemotePowerEnableChannel().setNextWriteValue(false);
		this.getVppRemotePowerChannel().setNextWriteValue(0);
		this.lastVppSetPoint.set(0);
	}

	/**
	 * Does the inverter answer on the VPP register bank?.
	 *
	 * @return true if the VPP protocol is available
	 */
	public boolean isVppAvailable() {
		return this.vppAvailable.get();
	}

	/**
	 * Simulates the result of the VPP probe.
	 *
	 * @param available true if the VPP register bank answers
	 */
	@VisibleForTesting
	protected void setVppAvailable(boolean available) {
		this.vppAvailable.set(available);
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
		if (this.config.controlMode().isVpp() && this.vppAvailable.get()) {
			// The VPP Set-Point has a resolution of 1 % of the nominal battery power
			return Math.max(1, this.getBdcRatedPower() / 100);
		}
		// The legacy power rate is quantized to avoid writes to non-volatile memory
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
