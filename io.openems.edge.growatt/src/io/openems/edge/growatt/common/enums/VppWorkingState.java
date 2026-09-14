package io.openems.edge.growatt.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of VPP Input-Register 31000 'Working state of energy storage machine'.
 */
public enum VppWorkingState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	STANDBY(0, "Standby"), //
	SELF_TEST(1, "Self-test"), //
	RESERVED(2, "Reserved"), //
	FAULT(3, "Fault"), //
	UPGRADE(4, "Upgrade"), //
	PV_ONLINE_BATTERY_OFFLINE(5, "PV online, battery offline"), //
	BATTERY_ONLINE_PV_ONLINE(6, "Battery online, PV online or offline"), //
	PV_AND_BATTERY_OFF_GRID(7, "PV and battery, off-grid operation"), //
	BATTERY_ONLINE_PV_OFFLINE(8, "Battery online, PV offline"), //
	BYPASS(9, "Bypass operation");

	private final int value;
	private final String option;

	private VppWorkingState(int value, String option) {
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
