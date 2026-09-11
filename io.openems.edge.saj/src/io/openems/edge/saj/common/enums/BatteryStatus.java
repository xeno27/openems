package io.openems.edge.saj.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of register 0x7B2D 'BatStatusDisp'.
 */
public enum BatteryStatus implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	NORMAL(0, "Normal"), //
	NO_CHARGING(1, "No charging"), //
	NO_DISCHARGING(2, "No discharging"), //
	NO_CHARGING_AND_NO_DISCHARGING(3, "No charging and no discharging"), //
	FORCED_CHARGING(4, "Forced charging"), //
	WAKE_UP(5, "Wake up");

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
