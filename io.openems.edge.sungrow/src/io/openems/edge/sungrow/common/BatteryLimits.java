package io.openems.edge.sungrow.common;

/**
 * Decides which battery power limits a Set-Point is clamped to.
 *
 * <p>
 * Holding-Registers 33046 and 33047 are plant parameters, not Set-Points. On a
 * measured SH25T they hold 30000 W for charge and 25000 W for discharge: the
 * battery DC/DC converter is rated higher than the inverter, and the discharge
 * limit matches the permitted grid connection. Overwriting them from an OpenEMS
 * configuration would silently redefine that commissioning decision, so by
 * default they are only read.
 *
 * <p>
 * This class is intentionally free of any Modbus or OpenEMS Channel
 * dependencies so that the decision can be unit tested on its own.
 */
public final class BatteryLimits {

	private BatteryLimits() {
	}

	/**
	 * Calculates the limit that a Set-Point is actually clamped to.
	 *
	 * <p>
	 * The inverter enforces its own limit regardless of what OpenEMS asks for, and
	 * the configured value expresses what the operator wants; the smaller of the
	 * two is the one that holds. If the inverter has not reported a limit yet, the
	 * configured value is used on its own.
	 *
	 * @param fromInverter the limit read from the inverter in [W], or null if it
	 *                     has not been read yet
	 * @param fromConfig   the configured limit in [W]
	 * @return the limit to clamp to in [W]; never negative
	 */
	public static int effective(Integer fromInverter, int fromConfig) {
		var configured = Math.abs(fromConfig);
		if (fromInverter == null || fromInverter <= 0) {
			return configured;
		}
		return Math.min(fromInverter, configured);
	}
}
