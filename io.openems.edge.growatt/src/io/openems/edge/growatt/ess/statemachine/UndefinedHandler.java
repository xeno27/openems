package io.openems.edge.growatt.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.growatt.ess.statemachine.StateMachine.State;

public class UndefinedHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();
		return switch (ess.getStartStopTarget()) {
		case UNDEFINED // Stuck in UNDEFINED State
			-> State.UNDEFINED;

		case START // force START
			-> ess.hasFaults() //
					? State.ERROR //
					: State.GO_RUNNING;

		case STOP // force STOP
			-> State.GO_STOPPED;
		};
	}
}
