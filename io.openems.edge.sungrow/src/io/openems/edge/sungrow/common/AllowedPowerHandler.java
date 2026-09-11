package io.openems.edge.sungrow.common;

/**
 * Derives the 'Allowed-Charge-Power' and 'Allowed-Discharge-Power' of the
 * Energy Storage System from the State-of-Charge and the configured limits.
 */
public final class AllowedPowerHandler {

	/**
	 * Result of {@link AllowedPowerHandler#calculate}.
	 *
	 * @param allowedChargePower    the allowed charge power in [W]; negative by
	 *                              OpenEMS convention
	 * @param allowedDischargePower the allowed discharge power in [W]; positive
	 */
	public record Result(int allowedChargePower, int allowedDischargePower) {
	}

	private AllowedPowerHandler() {
	}

	/**
	 * Calculates the allowed charge and discharge power.
	 *
	 * <p>
	 * As long as the State-of-Charge is unknown, no power is allowed. This avoids
	 * discharging an empty or charging a full battery during startup.
	 *
	 * @param soc               the State-of-Charge in [%]; null if not available
	 * @param minSoc            the minimum State-of-Charge in [%]
	 * @param maxSoc            the maximum State-of-Charge in [%]
	 * @param maxChargePower    the maximum battery charge power in [W]; positive
	 * @param maxDischargePower the maximum battery discharge power in [W];
	 *                          positive
	 * @return the {@link Result}
	 */
	public static Result calculate(Integer soc, int minSoc, int maxSoc, int maxChargePower, int maxDischargePower) {
		if (soc == null) {
			return new Result(0, 0);
		}
		return new Result(//
				soc < maxSoc ? -Math.abs(maxChargePower) : 0, //
				soc > minSoc ? Math.abs(maxDischargePower) : 0);
	}
}
