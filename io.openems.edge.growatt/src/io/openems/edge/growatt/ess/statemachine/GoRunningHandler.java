package io.openems.edge.growatt.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.growatt.common.enums.InverterStatus;
import io.openems.edge.growatt.ess.statemachine.StateMachine.State;

public class GoRunningHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		ess.setPowerOn(true);

		return switch (ess.getInverterStatus()) {
		case NORMAL -> State.RUNNING;
		case FAULT -> State.ERROR;
		case UNDEFINED, WAITING -> State.GO_RUNNING;
		};
	}
}
