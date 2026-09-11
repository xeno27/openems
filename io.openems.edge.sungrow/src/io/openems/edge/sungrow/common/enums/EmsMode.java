package io.openems.edge.sungrow.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of Holding-Register 13049 'EMS mode selection'.
 */
public enum EmsMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	/**
	 * The inverter dispatches itself; no remote Set-Point is accepted.
	 */
	SELF_CONSUMPTION(0, "Self-consumption mode"), //
	/**
	 * The inverter charges or discharges the battery according to the
	 * charge/discharge command and power.
	 */
	COMPULSORY(2, "Compulsory mode"), //
	/**
	 * The inverter is scheduled by an external energy management system; requires a
	 * heartbeat.
	 */
	EXTERNAL_EMS(3, "External EMS mode"), //
	/**
	 * Same as {@link #EXTERNAL_EMS}, but reported as Virtual-Power-Plant control.
	 */
	VPP(4, "VPP");

	private final int value;
	private final String option;

	private EmsMode(int value, String option) {
		this.value = value;
		this.option = option;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.option;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}

	/**
	 * Does this mode require a cyclic heartbeat on Holding-Register 13079?.
	 *
	 * @return true for {@link #EXTERNAL_EMS} and {@link #VPP}
	 */
	public boolean requiresHeartbeat() {
		return this == EXTERNAL_EMS || this == VPP;
	}
}
