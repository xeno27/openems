package io.openems.edge.sungrow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class BatteryLimitsTest {

	@Test
	public void inverterLimitWinsWhenItIsTheSmallerOne() {
		// Measured on a SH25T: 33047 reports 25000 W while the battery DC/DC is
		// rated 30000 W. The inverter limit is the one that holds.
		assertEquals(25_000, BatteryLimits.effective(25_000, 30_000));
	}

	@Test
	public void configuredLimitWinsWhenItIsTheSmallerOne() {
		assertEquals(10_000, BatteryLimits.effective(25_000, 10_000));
	}

	@Test
	public void fallsBackToConfigurationBeforeTheFirstRead() {
		assertEquals(20_000, BatteryLimits.effective(null, 20_000));
	}

	@Test
	public void treatsAnUnreportedZeroAsUnknown() {
		assertEquals(20_000, BatteryLimits.effective(0, 20_000));
	}

	@Test
	public void neverReturnsANegativeLimit() {
		assertEquals(20_000, BatteryLimits.effective(null, -20_000));
	}
}
