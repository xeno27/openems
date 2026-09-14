package io.openems.edge.growatt.common;

/**
 * Verifies that the inverter actually applies the VPP Set-Point.
 *
 * <p>
 * The protocol documents 'Actual control value of charging and discharging
 * power' (Input-Register 30474) as the read-back of 'Remote charge and
 * discharge power' (Holding-Register 30409). Growatt inverters answer a read on
 * a register bank they do not implement with zero instead of rejecting it, so a
 * write can be accepted on the wire without having any effect. Comparing the
 * two registers closes the loop and turns a silent loss of control into a
 * reported one.
 */
public class VppSetPointVerifier {

	/**
	 * Difference in [%] that is still accepted as 'applied'.
	 */
	private static final int TOLERANCE = 1;

	private final int toleratedMismatches;

	private int mismatches = 0;
	private boolean failed = false;

	public VppSetPointVerifier(int toleratedMismatches) {
		this.toleratedMismatches = toleratedMismatches;
	}

	/**
	 * Compares the written Set-Point with the value the inverter reports as
	 * applied.
	 *
	 * <p>
	 * A Set-Point of zero is not evaluated: an inverter that ignores the remote
	 * control reports zero as well, so the two cases cannot be told apart. In that
	 * case the previous verdict is kept.
	 *
	 * @param writtenPercent the value written to Holding-Register 30409; null if
	 *                       nothing was written yet
	 * @param actualPercent  the value of Input-Register 30474; null if not
	 *                       available
	 * @return true while the Set-Point is being applied
	 */
	public boolean verify(Integer writtenPercent, Integer actualPercent) {
		if (writtenPercent == null || writtenPercent == 0) {
			return !this.failed;
		}
		if (actualPercent != null && Math.abs(actualPercent - writtenPercent) <= TOLERANCE) {
			this.mismatches = 0;
			this.failed = false;
			return true;
		}
		this.mismatches++;
		if (this.mismatches >= this.toleratedMismatches) {
			this.failed = true;
		}
		return !this.failed;
	}

	/**
	 * Has the inverter stopped applying the Set-Point?.
	 *
	 * @return true if the Set-Point is not applied
	 */
	public boolean hasFailed() {
		return this.failed;
	}
}
