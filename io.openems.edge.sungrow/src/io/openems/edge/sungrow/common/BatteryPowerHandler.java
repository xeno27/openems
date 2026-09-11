package io.openems.edge.sungrow.common;

/**
 * Derives the signed battery power from the Sungrow registers.
 *
 * <p>
 * Older firmware reports 'Battery power' (Input-Register 13021) as an unsigned
 * magnitude and encodes the direction in 'Power flow status' (Input-Register
 * 13000, bit 1 = charging) and in the sign of 'Battery current' (Input-Register
 * 13020). Newer firmware already delivers a signed value. Evaluating both
 * sources makes the result correct on either firmware.
 */
public final class BatteryPowerHandler {

	/**
	 * Bit 1 of the 'Power flow status' register means 'battery is charging'.
	 */
	private static final int POWER_FLOW_CHARGING = 0x02;

	private BatteryPowerHandler() {
	}

	/**
	 * Calculates the DC discharge power in the OpenEMS convention: positive for
	 * discharge, negative for charge.
	 *
	 * @param batteryPower    the value of Input-Register 13021
	 * @param batteryCurrent  the value of Input-Register 13020; may be null
	 * @param powerFlowStatus the value of Input-Register 13000; may be null
	 * @return the signed battery power in [W]; null if the battery power is not
	 *         available
	 */
	public static Integer calculateDcDischargePower(Integer batteryPower, Integer batteryCurrent,
			Integer powerFlowStatus) {
		if (batteryPower == null) {
			return null;
		}
		if (batteryPower >= 0 && isCharging(batteryCurrent, powerFlowStatus)) {
			return -batteryPower;
		}
		return batteryPower;
	}

	/**
	 * Is the battery charging?.
	 *
	 * @param batteryCurrent  the value of Input-Register 13020; may be null
	 * @param powerFlowStatus the value of Input-Register 13000; may be null
	 * @return true if one of the two sources reports charging
	 */
	protected static boolean isCharging(Integer batteryCurrent, Integer powerFlowStatus) {
		if (powerFlowStatus != null && (powerFlowStatus & POWER_FLOW_CHARGING) != 0) {
			return true;
		}
		return batteryCurrent != null && batteryCurrent < 0;
	}
}
