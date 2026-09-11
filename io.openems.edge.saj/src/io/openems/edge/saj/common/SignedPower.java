package io.openems.edge.saj.common;

/**
 * Applies the SAJ energy-flow direction to a power value.
 *
 * <p>
 * The CH2 protocol reports the energy flow direction in a dedicated register
 * (for example 0x7BEF for the battery and 0x7BF0 for the grid) and the power in
 * a separate register. Using the direction register as the authority makes the
 * result independent of whether the power register itself carries a sign.
 */
public final class SignedPower {

	private SignedPower() {
	}

	/**
	 * Signs a power value according to a SAJ direction register.
	 *
	 * @param power     the power value; the sign of this value is ignored
	 * @param direction the direction register value: 1, 0 or -1
	 * @return the signed power, 0 if the direction is 0, or null if one of the
	 *         inputs is null
	 */
	public static Integer applyDirection(Integer power, Integer direction) {
		if (power == null || direction == null) {
			return null;
		}
		if (direction > 0) {
			return Math.abs(power);
		}
		if (direction < 0) {
			return -Math.abs(power);
		}
		return 0;
	}
}
