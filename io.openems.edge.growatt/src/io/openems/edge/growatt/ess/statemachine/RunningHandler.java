package io.openems.edge.growatt.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.growatt.common.enums.InverterStatus;
import io.openems.edge.growatt.ess.statemachine.StateMachine.State;

public class RunningHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		if (ess.hasFaults() || ess.getInverterStatus() == InverterStatus.FAULT) {
			return State.UNDEFINED;
		}

		// Mark as started
		ess._setStartStop(StartStop.START);

		return State.RUNNING;
	}
}
