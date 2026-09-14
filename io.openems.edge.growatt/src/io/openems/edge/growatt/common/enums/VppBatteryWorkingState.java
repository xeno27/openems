package io.openems.edge.growatt.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of VPP Input-Register 31001 'Battery working status'.
 */
public enum VppBatteryWorkingState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	STANDBY(0, "Battery standby"), //
	DISCONNECTED(1, "Battery disconnected"), //
	CHARGING(2, "Battery charging operation"), //
	DISCHARGING(3, "Battery discharge operation"), //
	FAULT(4, "Fault"), //
	UPGRADE(5, "Upgrade");

	private final int value;
	private final String option;

	private VppBatteryWorkingState(int value, String option) {
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
