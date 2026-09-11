package io.openems.edge.growatt.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of Input-Register 119 'Battery Type'.
 */
public enum BatteryType implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	LEAD_ACID(0, "Lead acid"), //
	LITHIUM(1, "Lithium");

	private final int value;
	private final String option;

	private BatteryType(int value, String option) {
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
