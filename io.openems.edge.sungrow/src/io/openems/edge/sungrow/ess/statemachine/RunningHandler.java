package io.openems.edge.sungrow.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.sungrow.common.enums.RunningState;
import io.openems.edge.sungrow.ess.statemachine.StateMachine.State;

public class RunningHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		if (ess.hasFaults() || ess.getRunningState() == RunningState.FAULT) {
			return State.UNDEFINED;
		}

		// Keep the EMS mode and, if required, the heartbeat alive
		ess.applyEmsMode();

		// Mark as started
		ess._setStartStop(StartStop.START);

		return State.RUNNING;
	}
}
