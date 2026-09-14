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
	 * and AC-charge are written in every Cycle. Use this on firmware that does not
	 * implement the Growatt VPP protocol; the settings are stored in non-volatile
	 * memory, so writes are quantized and throttled.
	 */
	REMOTE,
	/**
	 * Full remote control via the dedicated Growatt VPP protocol (register bank
	 * 30000-32099): the Active-Power Set-Point is written as a signed percentage
	 * to 'Remote charge and discharge power' (30409). Those registers are not
	 * stored in non-volatile memory, so they can be written in every Cycle.
	 *
	 * <p>
	 * Requires firmware that implements the VPP protocol. If the inverter does not
	 * answer on the VPP register bank, the Component falls back to
	 * {@link #REMOTE}.
	 */
	REMOTE_VPP;

	/**
	 * Does this mode control the inverter through the VPP register bank?.
	 *
	 * @return true for {@link #REMOTE_VPP}
	 */
	public boolean isVpp() {
		return this == REMOTE_VPP;
	}

	/**
	 * Does this mode write Set-Points to the inverter at all?.
	 *
	 * @return false only for {@link #INTERNAL}
	 */
	public boolean isRemote() {
		return this != INTERNAL;
	}
}
