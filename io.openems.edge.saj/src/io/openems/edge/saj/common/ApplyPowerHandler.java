package io.openems.edge.saj.common;

import static io.openems.common.utils.IntUtils.fitWithin;

import io.openems.edge.saj.common.enums.ControlMode;
import io.openems.edge.saj.common.enums.EmsEnable;

/**
 * Translates an OpenEMS Active-Power Set-Point into the SAJ CH2 remote EMS
 * Set-Point.
 *
 * <p>
 * The CH2 remote EMS interface expresses the Set-Point as a percentage of the
 * nominal power of the inverter with a resolution of 0.01 %, so a value of
 * 10000 means 100 %. Positive values discharge to grid, negative values charge
 * the battery - the same sign convention as OpenEMS uses for an Energy Storage
 * System.
 *
 * <p>
 * This class is intentionally free of any Modbus or OpenEMS Channel
 * dependencies so that the translation can be unit tested on its own.
 */
public final class ApplyPowerHandler {

	/**
	 * Scaling of the SAJ power reference registers: 10000 means 100 %.
	 */
	public static final int PER_UNIT = 10_000;

	/**
	 * The CH2 accepts up to 110 % of the nominal power.
	 */
	public static final int MAX_PER_UNIT = 11_000;

	/**
	 * Result of {@link ApplyPowerHandler#calculate}.
	 *
	 * @param emsEnable   the value for register 0x8400 'EMSEnable'
	 * @param powerRefPerUnit the value for register 0x8402 'EMSINVPRef' or 0x8405
	 *                    'EMSBATPRef', in 0.01 % of the nominal power
	 */
	public record Result(EmsEnable emsEnable, int powerRefPerUnit) {
	}

	private ApplyPowerHandler() {
	}

	/**
	 * Calculates the SAJ remote EMS Set-Point for an Active-Power Set-Point.
	 *
	 * <p>
	 * In {@link ControlMode#REMOTE_INVERTER} the Set-Point is passed to the
	 * inverter as it is, because the CH2 'Inverter target power' refers to the AC
	 * side and therefore already includes the DC PV production. In
	 * {@link ControlMode#REMOTE_BATTERY} the PV production is subtracted, because
	 * the 'Battery target power' refers to the battery alone.
	 *
	 * @param controlMode         the configured {@link ControlMode}; must not be
	 *                            {@link ControlMode#INTERNAL}
	 * @param activePowerSetPoint the AC Active-Power Set-Point in [W]; negative
	 *                            for charge, positive for discharge
	 * @param pvProduction        the actual DC PV production in [W]; negative
	 *                            values are treated as zero
	 * @param ratedPower          the nominal power of the inverter in [W]; must be
	 *                            positive
	 * @return the {@link Result}
	 */
	public static Result calculate(ControlMode controlMode, int activePowerSetPoint, int pvProduction,
			int ratedPower) {
		return switch (controlMode) {
		case INTERNAL -> new Result(EmsEnable.INVALID, 0);
		case REMOTE_INVERTER -> new Result(EmsEnable.INVERTER_POWER_DISPATCH, //
				toPerUnit(activePowerSetPoint, ratedPower));
		case REMOTE_BATTERY -> new Result(EmsEnable.BATTERY_POWER_DISPATCH, //
				toPerUnit(activePowerSetPoint - Math.max(0, pvProduction), ratedPower));
		};
	}

	/**
	 * Converts a power in [W] into the SAJ per-unit representation.
	 *
	 * @param power      the power in [W]
	 * @param ratedPower the nominal power of the inverter in [W]
	 * @return the value for a SAJ power reference register, clamped to the
	 *         allowed range
	 */
	protected static int toPerUnit(int power, int ratedPower) {
		if (ratedPower <= 0) {
			return 0;
		}
		return fitWithin(-MAX_PER_UNIT, MAX_PER_UNIT, (int) Math.round(power * (double) PER_UNIT / ratedPower));
	}
}
