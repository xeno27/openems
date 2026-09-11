package io.openems.edge.growatt.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of Input-Register 1041 'Battery charge/discharge state'.
 */
public enum BatteryStatus implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	STANDBY(0, "Standby"), //
	DISCHARGE(1, "Discharge"), //
	CHARGE(2, "Charge");

	private final int value;
	private final String option;

	private BatteryStatus(int value, String option) {
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
}
