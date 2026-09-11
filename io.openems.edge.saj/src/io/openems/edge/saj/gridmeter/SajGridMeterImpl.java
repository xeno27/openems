package io.openems.edge.saj.gridmeter;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

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

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.saj.common.SignedPower;

/**
 * Reads the grid connection point from a SAJ CH2 hybrid inverter.
 *
 * <p>
 * SAJ reports the energy flow direction in a dedicated register. The OpenEMS
 * convention 'positive for buy-from-grid' is the inverse of the SAJ direction
 * (1 = sell to grid).
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "SAJ.CH2.Grid-Meter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=GRID" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class SajGridMeterImpl extends AbstractOpenemsModbusComponent
		implements SajGridMeter, ElectricityMeter, ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

	@Reference
	private ConfigurationAdmin cm;

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public SajGridMeterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				SajGridMeter.ChannelId.values() //
		);
		ElectricityMeter.calculateAverageVoltageFromPhases(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.GRID;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				/*
				 * Per-phase grid values; each phase occupies nine registers.
				 */
				new FC3ReadRegistersTask(0x7B37, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(0x7B37), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.CURRENT_L1, new SignedWordElement(0x7B38), SCALE_FACTOR_1), //
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(0x7B39), SCALE_FACTOR_1), //
						new DummyRegisterElement(0x7B3A), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new SignedDoublewordElement(0x7B3B)), //
						new DummyRegisterElement(0x7B3D, 0x7B3F), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(0x7B40), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.CURRENT_L2, new SignedWordElement(0x7B41), SCALE_FACTOR_1), //
						new DummyRegisterElement(0x7B42, 0x7B43), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new SignedDoublewordElement(0x7B44)), //
						new DummyRegisterElement(0x7B46, 0x7B48), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(0x7B49), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.CURRENT_L3, new SignedWordElement(0x7B4A), SCALE_FACTOR_1), //
						new DummyRegisterElement(0x7B4B, 0x7B4C), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new SignedDoublewordElement(0x7B4D))), //

				/*
				 * Total grid power and energy flow direction.
				 */
				new FC3ReadRegistersTask(0x7BF0, Priority.HIGH, //
						m(SajGridMeter.ChannelId.GRID_DIRECTION, new SignedWordElement(0x7BF0))), //

				new FC3ReadRegistersTask(0x7C00, Priority.HIGH, //
						m(SajGridMeter.ChannelId.TOTAL_GRID_POWER, new SignedDoublewordElement(0x7C00))), //

				/*
				 * Energy counters, vector sum over all three phases.
				 */
				new FC3ReadRegistersTask(0x7C68, Priority.LOW, //
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, new UnsignedDoublewordElement(0x7C68),
								SCALE_FACTOR_1)), //

				new FC3ReadRegistersTask(0x7C88, Priority.LOW, //
						m(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, new UnsignedDoublewordElement(0x7C88),
								SCALE_FACTOR_1)) //
		);
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.calculateActivePower();
		}
	}

	/**
	 * Derives the grid Active-Power in the OpenEMS convention from the SAJ power
	 * and direction registers.
	 */
	private void calculateActivePower() {
		this._setActivePower(toOpenemsActivePower(//
				this.<IntegerReadChannel>channel(SajGridMeter.ChannelId.TOTAL_GRID_POWER).getNextValue().get(), //
				this.<IntegerReadChannel>channel(SajGridMeter.ChannelId.GRID_DIRECTION).getNextValue().get()));
	}

	/**
	 * Converts the SAJ grid power and direction into the OpenEMS convention, where
	 * buy-from-grid is positive.
	 *
	 * @param totalGridPower the value of register 0x7C00
	 * @param gridDirection  the value of register 0x7BF0: 1 = sell, 0 = idle, -1 =
	 *                       buy
	 * @return the Active-Power in [W]; null if one of the inputs is null
	 */
	protected static Integer toOpenemsActivePower(Integer totalGridPower, Integer gridDirection) {
		var signed = SignedPower.applyDirection(totalGridPower, gridDirection);
		return signed == null ? null : -signed;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(SajGridMeter.class, accessMode, 100) //
						.build());
	}
}
