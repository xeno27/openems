package io.openems.edge.growatt.common;

import static io.openems.common.utils.IntUtils.minInt;

/**
 * Derives the 'Allowed-Charge-Power' and 'Allowed-Discharge-Power' from the
 * dynamic battery limits of the Growatt VPP register bank.
 *
 * <p>
 * Input-Registers 31210 and 31212 report the maximum allowable charge and
 * discharge power of the battery. They are preferred over the configured
 * values, but never exceed them.
 */
public final class VppAllowedPowerHandler {

	/**
	 * Result of {@link VppAllowedPowerHandler#calculate}.
	 *
	 * @param allowedChargePower    the allowed charge power in [W]; negative by
	 *                              OpenEMS convention
	 * @param allowedDischargePower the allowed discharge power in [W]; positive
	 */
	public record Result(int allowedChargePower, int allowedDischargePower) {
	}

	private VppAllowedPowerHandler() {
	}

	/**
	 * Calculates the allowed charge and discharge power.
	 *
	 * <p>
	 * As long as the State-of-Charge is unknown, no power is allowed. This avoids
	 * discharging an empty or charging a full battery during startup.
	 *
	 * @param soc                         the State-of-Charge in [%]; null if not
	 *                                    available
	 * @param minSoc                      the minimum State-of-Charge in [%]
	 * @param maxSoc                      the maximum State-of-Charge in [%]
	 * @param bmsMaxChargePower           the value of Input-Register 31210 in
	 *                                    [W]; null if not available
	 * @param bmsMaxDischargePower        the value of Input-Register 31212 in
	 *                                    [W]; null if not available
	 * @param configuredMaxChargePower    the configured charge power limit in [W]
	 * @param configuredMaxDischargePower the configured discharge power limit in
	 *                                    [W]
	 * @return the {@link Result}
	 */
	public static Result calculate(Integer soc, int minSoc, int maxSoc, Integer bmsMaxChargePower,
			Integer bmsMaxDischargePower, int configuredMaxChargePower, int configuredMaxDischargePower) {
		if (soc == null) {
			return new Result(0, 0);
		}
		var chargeLimit = minInt(Math.abs(configuredMaxChargePower), abs(bmsMaxChargePower));
		var dischargeLimit = minInt(Math.abs(configuredMaxDischargePower), abs(bmsMaxDischargePower));

		return new Result(//
				soc < maxSoc ? -chargeLimit : 0, //
				soc > minSoc ? dischargeLimit : 0);
	}

	private static Integer abs(Integer value) {
		return value == null ? null : Math.abs(value);
	}
}
