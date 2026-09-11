package io.openems.edge.saj.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.saj.common.enums.InverterWorkMode;
import io.openems.edge.saj.ess.statemachine.StateMachine.State;

public class RunningHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		if (ess.hasFaults() || ess.getInverterWorkMode() == InverterWorkMode.FAULT) {
			return State.UNDEFINED;
		}

		// Keep the remote EMS session alive
		ess.setRemoteEmsEnabled(true);

		// Mark as started
		ess._setStartStop(StartStop.START);

		return State.RUNNING;
	}
}
