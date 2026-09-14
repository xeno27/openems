package io.openems.edge.growatt.ess.statemachine;

import java.time.Clock;

import io.openems.edge.common.statemachine.AbstractContext;
import io.openems.edge.growatt.ess.GrowattSphEssImpl;

public class Context extends AbstractContext<GrowattSphEssImpl> {

	protected final Clock clock;

	public Context(GrowattSphEssImpl parent, Clock clock) {
		super(parent);
		this.clock = clock;
	}
}
