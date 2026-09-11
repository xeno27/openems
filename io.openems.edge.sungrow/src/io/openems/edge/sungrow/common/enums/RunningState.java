package io.openems.edge.sungrow.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Selected values of Input-Register 12999 'Running state'.
 *
 * <p>
 * Only the states that are relevant for the Energy Storage System logic are
 * listed; every other value is reported through the raw Channel.
 */
public enum RunningState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	RUNNING_ON_GRID(0x0000, "Running (on-grid)"), //
	MICROGRID_OPERATION(0x0014, "Microgrid operation"), //
	OFF_GRID_CHARGE(0x0041, "Off-grid charge"), //
	MAINTAIN_MODE(0x0400, "Running in maintain mode"), //
	COMPULSORY_MODE(0x0800, "Running in compulsory mode"), //
	RUNNING_OFF_GRID(0x1000, "Running (off-grid)"), //
	EXTERNAL_EMS_MODE(0x4000, "Running in External EMS mode"), //
	EMERGENCY_CHARGING(0x4001, "Emergency charging operation"), //
	UNINITIALIZED(0x1111, "Uninitialized"), //
	KEY_STOP(0x1300, "Key stop"), //
	INITIAL_STANDBY(0x1200, "Initial standby"), //
	STANDBY(0x1400, "Standby"), //
	EMERGENCY_STOP(0x1500, "Emergency stop"), //
	STARTING(0x1600, "Starting"), //
	FAULT(0x5500, "Fault"), //
	STOP(0x8000, "Stop"), //
	DERATING_RUNNING(0x8100, "Derating running"), //
	DISPATCH_RUNNING(0x8200, "Dispatch running"), //
	WARN_RUNNING(0x9100, "Warn running");

	private final int value;
	private final String option;

	private RunningState(int value, String option) {
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
