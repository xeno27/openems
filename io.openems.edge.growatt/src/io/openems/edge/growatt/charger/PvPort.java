package io.openems.edge.growatt.charger;

/**
 * The PV (MPPT) ports of a Growatt SPH inverter and the Modbus Input-Register
 * where the values of the port start.
 *
 * <p>
 * Each port occupies four registers: voltage [0.1 V], current [0.1 A] and power
 * [0.1 W] as a 32-bit value.
 */
public enum PvPort {
	PV_1(3), //
	PV_2(7);

	private final int startAddress;

	private PvPort(int startAddress) {
		this.startAddress = startAddress;
	}

	/**
	 * Gets the Modbus Input-Register of the voltage of this PV port.
	 *
	 * @return the start address
	 */
	public int getStartAddress() {
		return this.startAddress;
	}
}
