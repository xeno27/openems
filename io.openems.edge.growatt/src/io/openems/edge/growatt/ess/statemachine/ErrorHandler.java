package io.openems.edge.growatt.ess.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.growatt.ess.statemachine.StateMachine.State;

public class ErrorHandler extends StateHandler<State, Context> {

	private static final Duration RETRY_AFTER = Duration.ofMinutes(2);

	private Instant entryAt = Instant.MIN;

	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		this.entryAt = Instant.now(context.clock);
	}

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		if (Duration.between(this.entryAt, Instant.now(context.clock)).compareTo(RETRY_AFTER) > 0) {
			// Try again
			return State.UNDEFINED;
		}
		return State.ERROR;
	}
}
