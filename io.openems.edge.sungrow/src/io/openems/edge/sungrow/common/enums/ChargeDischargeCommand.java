package io.openems.edge.sungrow.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of Holding-Register 13050 'Charge/discharge command'.
 */
public enum ChargeDischargeCommand implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	CHARGE(0xAA, "Charge"), //
	DISCHARGE(0xBB, "Discharge"), //
	STOP(0xCC, "Stop");

	private final int value;
	private final String option;

	private ChargeDischargeCommand(int value, String option) {
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
