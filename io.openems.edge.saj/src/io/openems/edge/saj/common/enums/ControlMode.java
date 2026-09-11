package io.openems.edge.saj.common.enums;

/**
 * Defines how much control OpenEMS takes over the SAJ CH2 inverter.
 */
public enum ControlMode {
	/**
	 * The inverter follows its own application mode. OpenEMS only reads values and
	 * never writes a Set-Point.
	 */
	INTERNAL,
	/**
	 * Remote EMS control of the AC power of the inverter ('EMSINVPRef', register
	 * 0x8402). The Set-Point includes the DC PV production. This is the default
	 * mode for Virtual-Power-Plant operation.
	 */
	REMOTE_INVERTER,
	/**
	 * Remote EMS control of the battery power ('EMSBATPRef', register 0x8405). PV
	 * production is fed to the loads and the grid independently of the Set-Point.
	 */
	REMOTE_BATTERY;
}
