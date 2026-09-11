package io.openems.edge.growatt.gridmeter;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.common.type.TypeUtils.subtract;
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
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Reads the grid connection point from a Growatt SPH hybrid inverter.
 *
 * <p>
 * Growatt reports import and export as two separate, always positive values.
 * The OpenEMS convention 'positive for buy-from-grid' is derived as
 * {@code POWER_TO_USER - POWER_TO_GRID}.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Growatt.SPH.Grid-Meter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=GRID" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class GrowattGridMeterImpl extends AbstractOpenemsModbusComponent
		implements GrowattGridMeter, ElectricityMeter, ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

	@Reference
	private ConfigurationAdmin cm;

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public GrowattGridMeterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				GrowattGridMeter.ChannelId.values() //
		);
		ElectricityMeter.calculateSumCurrentFromPhases(this);
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
				new FC4ReadInputRegistersTask(37, Priority.LOW, //
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(37), SCALE_FACTOR_1), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(38), SCALE_FACTOR_2), //
						new DummyRegisterElement(39, 41), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(42), SCALE_FACTOR_2), //
						new DummyRegisterElement(43, 45), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(46), SCALE_FACTOR_2)), //

				new FC4ReadInputRegistersTask(1021, Priority.HIGH, //
						m(GrowattGridMeter.ChannelId.POWER_TO_USER, new UnsignedDoublewordElement(1021),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(1029, Priority.HIGH, //
						m(GrowattGridMeter.ChannelId.POWER_TO_GRID, new UnsignedDoublewordElement(1029),
								SCALE_FACTOR_MINUS_1)), //

				new FC4ReadInputRegistersTask(1046, Priority.LOW, //
						m(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, new UnsignedDoublewordElement(1046),
								SCALE_FACTOR_2), //
						new DummyRegisterElement(1048, 1049), //
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, new UnsignedDoublewordElement(1050),
								SCALE_FACTOR_2)) //
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
	 * Derives the grid Active-Power from the separate import and export values.
	 */
	private void calculateActivePower() {
		this._setActivePower(subtract(//
				this.<IntegerReadChannel>channel(GrowattGridMeter.ChannelId.POWER_TO_USER).getNextValue().get(), //
				this.<IntegerReadChannel>channel(GrowattGridMeter.ChannelId.POWER_TO_GRID).getNextValue().get()));
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
				ModbusSlaveNatureTable.of(GrowattGridMeter.class, accessMode, 100) //
						.build());
	}
}
