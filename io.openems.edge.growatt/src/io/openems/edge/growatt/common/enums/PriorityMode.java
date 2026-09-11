package io.openems.edge.growatt.common.enums;

import io.openems.common.types.OptionsEnum;

/**
 * Energy priority of the SPH inverter; Holding-Register 1044.
 */
public enum PriorityMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	/**
	 * PV covers the local loads first; the battery is only used for the remaining
	 * load.
	 */
	LOAD_FIRST(0, "Load first"), //
	/**
	 * The battery is charged with the configured power rate; charging from grid is
	 * possible if 'AC-Charge' is enabled.
	 */
	BATTERY_FIRST(1, "Battery first"), //
	/**
	 * The battery is discharged with the configured power rate; the energy is fed
	 * to the grid.
	 */
	GRID_FIRST(2, "Grid first");

	private final int value;
	private final String option;

	private PriorityMode(int value, String option) {
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
