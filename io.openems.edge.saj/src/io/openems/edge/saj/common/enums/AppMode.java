package io.openems.edge.saj.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of register 0x7B28 'SetAppMode'.
 */
public enum AppMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	SELF_USE(0, "Self-use"), //
	TIME_SHARING(1, "Time-sharing"), //
	BACKUP(2, "Backup power"), //
	PASSIVE(3, "Passive");

	private final int value;
	private final String option;

	private AppMode(int value, String option) {
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
