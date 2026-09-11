package io.openems.edge.saj.charger;

/**
 * The PV (MPPT) ports of a SAJ CH2 inverter and the Modbus register where the
 * values of the port start.
 *
 * <p>
 * Each port occupies four registers: voltage [0.1 V], total current [0.01 A]
 * and power [W] as a 32-bit value.
 */
public enum PvPort {
	PV_1(0x7B99), //
	PV_2(0x7B9D), //
	PV_3(0x7BA1), //
	PV_4(0x7BA5), //
	PV_5(0x7BA9), //
	PV_6(0x7BAD), //
	PV_7(0x7BB1), //
	PV_8(0x7BB5);

	private final int startAddress;

	private PvPort(int startAddress) {
		this.startAddress = startAddress;
	}

	/**
	 * Gets the Modbus register of the voltage of this PV port.
	 *
	 * @return the start address
	 */
	public int getStartAddress() {
		return this.startAddress;
	}
}
