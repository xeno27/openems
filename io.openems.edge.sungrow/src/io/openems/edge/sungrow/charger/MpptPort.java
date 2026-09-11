package io.openems.edge.sungrow.charger;

/**
 * The MPPT ports of a Sungrow SH inverter and the Modbus Input-Register where
 * the values of the port start.
 *
 * <p>
 * Each port occupies two registers: voltage [0.1 V] and current [0.1 A]. A
 * SH20T has three MPPT ports.
 */
public enum MpptPort {
	MPPT_1(5010), //
	MPPT_2(5012), //
	MPPT_3(5014), //
	MPPT_4(5114);

	private final int startAddress;

	private MpptPort(int startAddress) {
		this.startAddress = startAddress;
	}

	/**
	 * Gets the Modbus Input-Register of the voltage of this MPPT port.
	 *
	 * @return the start address
	 */
	public int getStartAddress() {
		return this.startAddress;
	}
}
