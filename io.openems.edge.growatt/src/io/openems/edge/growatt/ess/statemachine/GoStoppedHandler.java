package io.openems.edge.growatt.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.growatt.ess.statemachine.StateMachine.State;

public class GoStoppedHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		ess.setPowerOn(false);

		return switch (ess.getInverterStatus()) {
		case WAITING -> State.STOPPED;
		case FAULT, NORMAL, UNDEFINED -> State.GO_STOPPED;
		};
	}
}
