package io.openems.edge.sungrow.ess.statemachine;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.statemachine.StateHandler;
import io.openems.edge.sungrow.common.enums.RunningState;
import io.openems.edge.sungrow.ess.statemachine.StateMachine.State;

public class GoRunningHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		final var ess = context.getParent();

		ess.setStartStopCommand(true);
		ess.applyEmsMode();

		var runningState = ess.getRunningState();
		if (runningState == RunningState.FAULT) {
			return State.ERROR;
		}
		if (isRunning(runningState)) {
			return State.RUNNING;
		}
		return State.GO_RUNNING;
	}

	/**
	 * Is the inverter in one of the running states?.
	 *
	 * @param runningState the {@link RunningState}
	 * @return true if the inverter is running
	 */
	protected static boolean isRunning(RunningState runningState) {
		return switch (runningState) {
		case RUNNING_ON_GRID, MICROGRID_OPERATION, MAINTAIN_MODE, COMPULSORY_MODE, RUNNING_OFF_GRID,
				EXTERNAL_EMS_MODE, EMERGENCY_CHARGING, OFF_GRID_CHARGE, DERATING_RUNNING, DISPATCH_RUNNING,
				WARN_RUNNING ->
			true;
		case EMERGENCY_STOP, FAULT, INITIAL_STANDBY, KEY_STOP, STANDBY, STARTING, STOP, UNDEFINED, UNINITIALIZED ->
			false;
		};
	}
}
