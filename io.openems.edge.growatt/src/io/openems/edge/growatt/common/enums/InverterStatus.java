package io.openems.edge.growatt.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of Input-Register 0 'Inverter Status'.
 */
public enum InverterStatus implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	WAITING(0, "Waiting"), //
	NORMAL(1, "Normal"), //
	FAULT(3, "Fault");

	private final int value;
	private final String option;

	private InverterStatus(int value, String option) {
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
