package io.openems.edge.saj.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of register 0x7B04 'MPVMode'.
 */
public enum InverterWorkMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	INITIALIZATION(0, "Initialization"), //
	WAITING(1, "Waiting"), //
	GRID_CONNECTED(2, "Grid-connected mode"), //
	OFF_GRID(3, "Off-grid mode, for energy storage"), //
	GRID_LOADED(4, "Grid-loaded mode, for energy storage"), //
	FAULT(5, "Fault"), //
	UPGRADE(6, "Upgrade"), //
	DEBUG(7, "Debug"), //
	SELF_TEST(8, "Self-test"), //
	RESET(9, "Reset");

	private final int value;
	private final String option;

	private InverterWorkMode(int value, String option) {
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
