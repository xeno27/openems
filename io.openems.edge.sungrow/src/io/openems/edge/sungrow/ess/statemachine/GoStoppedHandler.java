package io.openems.edge.sungrow.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.sungrow.ess.statemachine.StateMachine.State;

public class GoStoppedHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		// Hand control back to the inverter before shutting it down
		ess.resetToSelfConsumption();
		ess.setStartStopCommand(false);

		return State.STOPPED;
	}
}
