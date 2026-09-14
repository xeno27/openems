package io.openems.edge.growatt.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of Input-Register 1000 'System Work Mode' of the SPH storage section.
 */
public enum SystemWorkMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	WAITING(0, "Waiting"), //
	SELF_TEST(1, "Self test"), //
	RESERVED(2, "Reserved"), //
	FAULT(3, "Fault"), //
	FLASH(4, "Flash"), //
	PV_AND_BATTERY_ONLINE(5, "PV and Battery online"), //
	BATTERY_ONLINE(6, "Battery online"), //
	PV_OFFLINE(7, "PV offline"), //
	BATTERY_OFFLINE(8, "Battery offline");

	private final int value;
	private final String option;

	private SystemWorkMode(int value, String option) {
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
