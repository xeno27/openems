package io.openems.edge.saj.ess.statemachine;

import java.time.Clock;

import io.openems.edge.common.statemachine.AbstractContext;
import io.openems.edge.saj.ess.SajCh2EssImpl;

public class Context extends AbstractContext<SajCh2EssImpl> {

	protected final Clock clock;

	public Context(SajCh2EssImpl parent, Clock clock) {
		super(parent);
		this.clock = clock;
	}
}
