package io.openems.edge.growatt.common;

import static io.openems.common.utils.IntUtils.fitWithin;

import io.openems.edge.growatt.common.enums.PriorityMode;

/**
 * Translates an OpenEMS Active-Power Set-Point into the Growatt SPH remote
 * control parameters.
 *
 * <p>
 * The SPH inverters have no register for an absolute power Set-Point. Instead
 * the energy flow is controlled by the 'Priority Mode' (Holding-Register 1044)
 * in combination with a power rate in percent of the nominal battery power:
 *
 * <ul>
 * <li>{@link PriorityMode#BATTERY_FIRST} charges the battery with the
 * 'Battery-First Charge Power Rate' (Holding-Register 1090). 'AC-Charge'
 * (Holding-Register 1092) additionally allows charging from grid.
 * <li>{@link PriorityMode#GRID_FIRST} discharges the battery with the
 * 'Grid-First Discharge Power Rate' (Holding-Register 1070) and feeds the
 * energy to grid.
 * <li>{@link PriorityMode#LOAD_FIRST} lets the inverter balance the local
 * loads on its own.
 * </ul>
 *
 * <p>
 * Growatt stores these settings in non-volatile memory, so the calculated power
 * rate is quantized to avoid a write on every Cycle.
 *
 * <p>
 * This class is intentionally free of any Modbus or OpenEMS Channel
 * dependencies so that the translation can be unit tested on its own.
 */
public final class ApplyPowerHandler {

	/**
	 * Result of {@link ApplyPowerHandler#calculate}.
	 *
	 * @param priorityMode     the {@link PriorityMode} to be written to the
	 *                         inverter
	 * @param powerRatePercent the charge or discharge power rate in [%] of the
	 *                         nominal battery power; always in range [0;100]
	 * @param acChargeEnabled  true to allow charging the battery from grid
	 */
	public record Result(PriorityMode priorityMode, int powerRatePercent, boolean acChargeEnabled) {
	}

	private static final Result LOAD_FIRST = new Result(PriorityMode.LOAD_FIRST, 0, false);

	private ApplyPowerHandler() {
	}

	/**
	 * Calculates the Growatt remote control parameters for an Active-Power
	 * Set-Point.
	 *
	 * <p>
	 * The Set-Point refers to the AC side of the Energy Storage System, i.e. it
	 * includes the DC PV production. The battery therefore has to provide
	 * {@code activePowerSetPoint - pvProduction}.
	 *
	 * @param activePowerSetPoint the AC Active-Power Set-Point in [W]; negative
	 *                            for charge, positive for discharge
	 * @param pvProduction        the actual DC PV production in [W]; negative
	 *                            values are treated as zero
	 * @param maxChargePower      the nominal battery charge power in [W]; must be
	 *                            positive
	 * @param maxDischargePower   the nominal battery discharge power in [W]; must
	 *                            be positive
	 * @param powerRateStep       quantization of the power rate in [%]; must be at
	 *                            least 1
	 * @return the {@link Result}
	 */
	public static Result calculate(int activePowerSetPoint, int pvProduction, int maxChargePower, int maxDischargePower,
			int powerRateStep) {
		var batteryPower = activePowerSetPoint - Math.max(0, pvProduction);

		if (batteryPower < 0) {
			return new Result(PriorityMode.BATTERY_FIRST, //
					toPowerRate(-batteryPower, maxChargePower, powerRateStep), //
					true /* allow charging from grid */);
		}
		if (batteryPower > 0) {
			return new Result(PriorityMode.GRID_FIRST, //
					toPowerRate(batteryPower, maxDischargePower, powerRateStep), //
					false);
		}
		return LOAD_FIRST;
	}

	/**
	 * Converts an absolute power in [W] to a quantized power rate in [%] of the
	 * given nominal power.
	 *
	 * <p>
	 * The value is rounded up to the next step, so that a small power request is
	 * never quantized down to zero.
	 *
	 * @param power        the absolute power in [W]; never negative
	 * @param nominalPower the nominal power in [W]
	 * @param step         quantization of the result in [%]
	 * @return the power rate in range [0;100]
	 */
	protected static int toPowerRate(int power, int nominalPower, int step) {
		if (nominalPower <= 0 || power <= 0) {
			return 0;
		}
		var quantization = Math.max(1, step);
		var percent = (int) Math.ceil(power * 100.0 / nominalPower / quantization) * quantization;
		return fitWithin(0, 100, percent);
	}
}
