package io.openems.edge.sungrow.common;

import static io.openems.common.utils.IntUtils.fitWithin;

import io.openems.edge.sungrow.common.enums.ChargeDischargeCommand;
import io.openems.edge.sungrow.common.enums.ControlMode;

/**
 * Translates an OpenEMS Active-Power Set-Point into the Sungrow forced
 * charge/discharge command.
 *
 * <p>
 * Sungrow controls the battery with a command (Holding-Register 13050) and an
 * unsigned power value in [W] (Holding-Register 13051). The power refers to the
 * battery, so the DC PV production has to be subtracted from the AC Set-Point.
 *
 * <p>
 * This class is intentionally free of any Modbus or OpenEMS Channel
 * dependencies so that the translation can be unit tested on its own.
 */
public final class ApplyPowerHandler {

	/**
	 * Result of {@link ApplyPowerHandler#calculate}.
	 *
	 * @param command the value for Holding-Register 13050
	 * @param power   the value for Holding-Register 13051 in [W]; never negative
	 */
	public record Result(ChargeDischargeCommand command, int power) {
	}

	private static final Result STOP = new Result(ChargeDischargeCommand.STOP, 0);

	private ApplyPowerHandler() {
	}

	/**
	 * Calculates the Sungrow charge/discharge command for an Active-Power
	 * Set-Point.
	 *
	 * @param controlMode         the configured {@link ControlMode}
	 * @param activePowerSetPoint the AC Active-Power Set-Point in [W]; negative
	 *                            for charge, positive for discharge
	 * @param pvProduction        the actual DC PV production in [W]; negative
	 *                            values are treated as zero
	 * @param maxChargePower      the maximum battery charge power in [W]; positive
	 * @param maxDischargePower   the maximum battery discharge power in [W];
	 *                            positive
	 * @return the {@link Result}
	 */
	public static Result calculate(ControlMode controlMode, int activePowerSetPoint, int pvProduction,
			int maxChargePower, int maxDischargePower) {
		if (controlMode == ControlMode.INTERNAL) {
			return STOP;
		}
		var batteryPower = activePowerSetPoint - Math.max(0, pvProduction);

		if (batteryPower < 0) {
			return new Result(ChargeDischargeCommand.CHARGE, //
					fitWithin(0, Math.abs(maxChargePower), -batteryPower));
		}
		if (batteryPower > 0) {
			return new Result(ChargeDischargeCommand.DISCHARGE, //
					fitWithin(0, Math.abs(maxDischargePower), batteryPower));
		}
		return STOP;
	}
}
