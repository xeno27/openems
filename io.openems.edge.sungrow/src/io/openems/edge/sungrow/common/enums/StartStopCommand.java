package io.openems.edge.sungrow.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of Holding-Register 12999 'Start/Stop'.
 */
public enum StartStopCommand implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	START(0xCF, "Boot"), //
	STOP(0xCE, "Shutdown");

	private final int value;
	private final String option;

	private StartStopCommand(int value, String option) {
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
