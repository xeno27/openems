package io.openems.edge.saj.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.saj.ess.statemachine.StateMachine.State;

public class GoRunningHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		// Activate the remote EMS session
		ess.setRemoteEmsEnabled(true);

		return switch (ess.getInverterWorkMode()) {
		case GRID_CONNECTED, GRID_LOADED, OFF_GRID -> State.RUNNING;
		case FAULT -> State.ERROR;
		case DEBUG, INITIALIZATION, RESET, SELF_TEST, UNDEFINED, UPGRADE, WAITING -> State.GO_RUNNING;
		};
	}
}
