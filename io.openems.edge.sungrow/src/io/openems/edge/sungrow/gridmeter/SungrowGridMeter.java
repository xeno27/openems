package io.openems.edge.sungrow.gridmeter;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * The grid connection point as it is measured by the smart energy meter of a
 * Sungrow SH hybrid inverter.
 */
public interface SungrowGridMeter extends ElectricityMeter, OpenemsComponent {
}
