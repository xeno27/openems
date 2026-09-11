package io.openems.edge.saj.gridmeter;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * The grid connection point as it is measured by a SAJ CH2 hybrid inverter.
 */
public interface SajGridMeter extends ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Total grid power as reported by the inverter; the sign is taken from
		 * {@link #GRID_DIRECTION}.
		 */
		TOTAL_GRID_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //
		/**
		 * Energy flow direction of the grid: 1 = sell to grid, 0 = idle, -1 = buy from
		 * grid.
		 */
		GRID_DIRECTION(Doc.of(OpenemsType.INTEGER)); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
