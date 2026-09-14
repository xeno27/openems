package io.openems.edge.growatt.common;

import static io.openems.common.utils.IntUtils.sumInteger;

/**
 * Converts the power values of the Growatt VPP register bank into the OpenEMS
 * conventions.
 */
public final class VppPowerHandler {

	private VppPowerHandler() {
	}

	/**
	 * Converts the VPP 'Charge/discharge power' (Input-Register 31200) into the
	 * OpenEMS DC discharge power.
	 *
	 * <p>
	 * Growatt counts positive as charging, OpenEMS counts positive as
	 * discharging.
	 *
	 * @param growattBatteryPower the value of Input-Register 31200 in [W]
	 * @return the DC discharge power in [W]; null if the input is null
	 */
	public static Integer toDcDischargePower(Integer growattBatteryPower) {
		return growattBatteryPower == null ? null : -growattBatteryPower;
	}

	/**
	 * Picks the AC Active-Power of the Energy Storage System.
	 *
	 * <p>
	 * The VPP register bank measures the AC power directly (Input-Register
	 * 31100). Without it the value is calculated as the sum of PV production and
	 * DC discharge power.
	 *
	 * @param vppAcActivePower the value of Input-Register 31100 in [W]; null if
	 *                         the VPP register bank is not available
	 * @param pvProduction     the PV production in [W]
	 * @param dcDischargePower the DC discharge power in [W]
	 * @return the AC Active-Power in [W]; null if no value can be determined
	 */
	public static Integer selectAcActivePower(Integer vppAcActivePower, Integer pvProduction,
			Integer dcDischargePower) {
		if (vppAcActivePower != null) {
			return vppAcActivePower;
		}
		return sumInteger(pvProduction, dcDischargePower);
	}
}
