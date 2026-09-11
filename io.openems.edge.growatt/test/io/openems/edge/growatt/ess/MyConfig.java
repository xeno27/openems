package io.openems.edge.growatt.ess;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.utils.ConfigUtils;
import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.growatt.common.enums.ControlMode;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	public static class Builder {
		private String id;
		private String modbusId = null;
		private int modbusUnitId = 1;
		private StartStopConfig startStop = StartStopConfig.AUTO;
		private ControlMode controlMode = ControlMode.INTERNAL;
		private int capacity = 6000;
		private int maxApparentPower = 4600;
		private int maxBatteryChargePower = 4600;
		private int maxBatteryDischargePower = 4600;
		private int minSoc = 10;
		private int maxSoc = 100;
		private int powerRateStep = 5;
		private int minimumWriteInterval = 30;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setModbusId(String modbusId) {
			this.modbusId = modbusId;
			return this;
		}

		public Builder setModbusUnitId(int modbusUnitId) {
			this.modbusUnitId = modbusUnitId;
			return this;
		}

		public Builder setStartStop(StartStopConfig startStop) {
			this.startStop = startStop;
			return this;
		}

		public Builder setControlMode(ControlMode controlMode) {
			this.controlMode = controlMode;
			return this;
		}

		public Builder setCapacity(int capacity) {
			this.capacity = capacity;
			return this;
		}

		public Builder setMaxApparentPower(int maxApparentPower) {
			this.maxApparentPower = maxApparentPower;
			return this;
		}

		public Builder setMaxBatteryChargePower(int maxBatteryChargePower) {
			this.maxBatteryChargePower = maxBatteryChargePower;
			return this;
		}

		public Builder setMaxBatteryDischargePower(int maxBatteryDischargePower) {
			this.maxBatteryDischargePower = maxBatteryDischargePower;
			return this;
		}

		public Builder setMinSoc(int minSoc) {
			this.minSoc = minSoc;
			return this;
		}

		public Builder setMaxSoc(int maxSoc) {
			this.maxSoc = maxSoc;
			return this;
		}

		public Builder setPowerRateStep(int powerRateStep) {
			this.powerRateStep = powerRateStep;
			return this;
		}

		public Builder setMinimumWriteInterval(int minimumWriteInterval) {
			this.minimumWriteInterval = minimumWriteInterval;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 *
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String modbus_id() {
		return this.builder.modbusId;
	}

	@Override
	public int modbusUnitId() {
		return this.builder.modbusUnitId;
	}

	@Override
	public String Modbus_target() {
		return ConfigUtils.generateReferenceTargetFilter(this.id(), this.builder.modbusId);
	}

	@Override
	public StartStopConfig startStop() {
		return this.builder.startStop;
	}

	@Override
	public ControlMode controlMode() {
		return this.builder.controlMode;
	}

	@Override
	public int capacity() {
		return this.builder.capacity;
	}

	@Override
	public int maxApparentPower() {
		return this.builder.maxApparentPower;
	}

	@Override
	public int maxBatteryChargePower() {
		return this.builder.maxBatteryChargePower;
	}

	@Override
	public int maxBatteryDischargePower() {
		return this.builder.maxBatteryDischargePower;
	}

	@Override
	public int minSoc() {
		return this.builder.minSoc;
	}

	@Override
	public int maxSoc() {
		return this.builder.maxSoc;
	}

	@Override
	public int powerRateStep() {
		return this.builder.powerRateStep;
	}

	@Override
	public int minimumWriteInterval() {
		return this.builder.minimumWriteInterval;
	}
}
