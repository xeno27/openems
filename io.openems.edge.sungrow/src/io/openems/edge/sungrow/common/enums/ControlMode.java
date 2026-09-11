package io.openems.edge.sungrow.common.enums;

/**
 * Defines how much control OpenEMS takes over the Sungrow SH inverter.
 */
public enum ControlMode {
	/**
	 * The inverter dispatches itself ('Self-consumption mode'). OpenEMS only reads
	 * values and never writes a Set-Point.
	 */
	INTERNAL,
	/**
	 * Remote control via 'Compulsory mode' (EMS mode 2). This is the mode that is
	 * verified in the field and does not need a heartbeat.
	 */
	REMOTE_COMPULSORY,
	/**
	 * Remote control via 'External EMS mode' (EMS mode 3). Requires a cyclic
	 * heartbeat on Holding-Register 13079.
	 */
	REMOTE_EXTERNAL_EMS,
	/**
	 * Remote control via 'VPP' (EMS mode 4). Requires a cyclic heartbeat on
	 * Holding-Register 13079.
	 */
	REMOTE_VPP;

	/**
	 * Gets the {@link EmsMode} that belongs to this {@link ControlMode}.
	 *
	 * @return the {@link EmsMode}
	 */
	public EmsMode getEmsMode() {
		return switch (this) {
		case INTERNAL -> EmsMode.SELF_CONSUMPTION;
		case REMOTE_COMPULSORY -> EmsMode.COMPULSORY;
		case REMOTE_EXTERNAL_EMS -> EmsMode.EXTERNAL_EMS;
		case REMOTE_VPP -> EmsMode.VPP;
		};
	}
}
