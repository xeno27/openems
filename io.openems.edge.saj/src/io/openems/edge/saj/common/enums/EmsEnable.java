package io.openems.edge.saj.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Value of register 0x8400 'EMSEnable'; selects which remote Set-Point the
 * inverter follows.
 */
public enum EmsEnable implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	/**
	 * Remote EMS control is off; the inverter follows its own application mode.
	 */
	INVALID(0, "Invalid"), //
	/**
	 * The inverter follows 'EMSINVPRef' (register 0x8402), i.e. a target power for
	 * the AC side of the inverter.
	 */
	INVERTER_POWER_DISPATCH(1, "Inverter power dispatch mode"), //
	/**
	 * The inverter follows 'EMSBATPRef' (register 0x8405), i.e. a target power for
	 * the battery.
	 */
	BATTERY_POWER_DISPATCH(2, "Battery power scheduling mode");

	private final int value;
	private final String option;

	private EmsEnable(int value, String option) {
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
