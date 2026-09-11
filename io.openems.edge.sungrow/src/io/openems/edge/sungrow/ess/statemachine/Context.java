package io.openems.edge.sungrow.ess.statemachine;

import java.time.Clock;

import io.openems.edge.common.statemachine.AbstractContext;
import io.openems.edge.sungrow.ess.SungrowEssImpl;

public class Context extends AbstractContext<SungrowEssImpl> {

	protected final Clock clock;

	public Context(SungrowEssImpl parent, Clock clock) {
		super(parent);
		this.clock = clock;
	}
}
