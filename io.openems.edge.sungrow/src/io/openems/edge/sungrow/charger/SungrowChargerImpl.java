package io.openems.edge.sungrow.charger;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
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
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.sungrow.common.Sungrow;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Sungrow.SH.Charger", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class SungrowChargerImpl extends AbstractOpenemsModbusComponent implements SungrowCharger, EssDcCharger,
		ModbusComponent, OpenemsComponent, TimedataProvider, EventHandler, ModbusSlave {

	private final CalculateEnergyFromPower calculateActualEnergy = new CalculateEnergyFromPower(this,
			EssDcCharger.ChannelId.ACTUAL_ENERGY);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	private Sungrow ess;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata = null;

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private MpptPort mpptPort = MpptPort.MPPT_1;

	public SungrowChargerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				EssDcCharger.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.mpptPort = config.mpptPort();
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id())) {
			return;
		}
		if (this.ess != null) {
			this.ess.addCharger(this);
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.ess != null) {
			this.ess.removeCharger(this);
		}
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		final var startAddress = this.mpptPort.getStartAddress();
		return new ModbusProtocol(this, //
				new FC4ReadInputRegistersTask(startAddress, Priority.HIGH, //
						m(EssDcCharger.ChannelId.VOLTAGE, new UnsignedWordElement(startAddress), SCALE_FACTOR_2), //
						m(EssDcCharger.ChannelId.CURRENT, new UnsignedWordElement(startAddress + 1),
								SCALE_FACTOR_2)));
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> {
			this.calculateActualPower();
			this.calculateEnergy();
		}
		}
	}

	/**
	 * Sungrow reports voltage and current per MPPT port, but not the power; it is
	 * calculated from the two values.
	 */
	private void calculateActualPower() {
		var voltage = this.getVoltage().get();
		var current = this.getCurrent().get();
		if (voltage == null || current == null) {
			this._setActualPower(null);
			return;
		}
		// [mV] * [mA] -> [W]
		this._setActualPower((int) Math.round(voltage / 1000d * current / 1000d));
	}

	/**
	 * Calculates the produced energy from the actual power.
	 */
	private void calculateEnergy() {
		var actualPower = this.getActualPowerChannel().getNextValue().get();
		if (actualPower == null) {
			this.calculateActualEnergy.update(null);
		} else {
			this.calculateActualEnergy.update(Math.max(0, actualPower));
		}
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActualPower().asString();
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				EssDcCharger.getModbusSlaveNatureTable(accessMode));
	}
}
