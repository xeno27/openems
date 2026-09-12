package io.openems.edge.growatt.common;

import static io.openems.common.utils.IntUtils.fitWithin;

/**
 * Translates an OpenEMS Active-Power Set-Point into the Growatt VPP 'Remote
 * charge and discharge power' Set-Point.
 *
 * <p>
 * The VPP protocol (GROWATT VPP COMMUNICATION PROTOCOL OF INVERTER V2.01)
 * expresses the Set-Point as a signed percentage in Holding-Register 30409.
 * For SPH and SPA the control point is the DC side of the battery, so the
 * percentage refers to the 'Rated charging and discharging power of BDC'
 * (Holding-Register 30026).
 *
 * <p>
 * Note the inverted sign convention: Growatt counts <em>positive as
 * charging</em>, while OpenEMS counts positive as discharging.
 *
 * <p>
 * This class is intentionally free of any Modbus or OpenEMS Channel
 * dependencies so that the translation can be unit tested on its own.
 */
public final class VppApplyPowerHandler {

	/**
	 * Result of {@link VppApplyPowerHandler#calculate}.
	 *
	 * @param remoteControlEnabled the value for Holding-Register 30407
	 * @param powerPercent         the value for Holding-Register 30409 in [%] of
	 *                             the nominal battery power; positive for charge,
	 *                             negative for discharge
	 */
	public record Result(boolean remoteControlEnabled, int powerPercent) {
	}

	/**
	 * Hands control back to the inverter.
	 */
	public static final Result DISABLED = new Result(false, 0);

	private VppApplyPowerHandler() {
	}

	/**
	 * Calculates the VPP remote power Set-Point for an Active-Power Set-Point.
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
	 * @param bdcRatedPower       the rated charge/discharge power of the battery
	 *                            DC/DC converter in [W]; must be positive
	 * @return the {@link Result}
	 */
	public static Result calculate(int activePowerSetPoint, int pvProduction, int bdcRatedPower) {
		if (bdcRatedPower <= 0) {
			return DISABLED;
		}
		var batteryPower = activePowerSetPoint - Math.max(0, pvProduction);

		// OpenEMS: positive is discharge; Growatt: positive is charge
		var percent = (int) Math.round(-batteryPower * 100.0 / bdcRatedPower);
		return new Result(true, fitWithin(-100, 100, percent));
	}
}
