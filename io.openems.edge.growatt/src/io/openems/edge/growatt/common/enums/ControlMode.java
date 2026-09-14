package io.openems.edge.growatt.common.enums;

/**
 * Defines how much control OpenEMS takes over the Growatt inverter.
 */
public enum ControlMode {
	/**
	 * The inverter follows its own priority and time-slot configuration. OpenEMS
	 * only reads values and never writes a Set-Point. Required if the schedule is
	 * managed via ShinePhone.
	 */
	INTERNAL,
	/**
	 * Full remote control by OpenEMS: priority mode, charge/discharge power rate
	 * and AC-charge are written in every Cycle. This is the mode that is required
	 * for Virtual-Power-Plant operation.
	 */
	REMOTE;
}
