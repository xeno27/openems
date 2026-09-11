package io.openems.edge.sungrow.ess.statemachine;

import static io.openems.edge.sungrow.common.enums.RunningState.COMPULSORY_MODE;
import static io.openems.edge.sungrow.common.enums.RunningState.FAULT;
import static io.openems.edge.sungrow.common.enums.RunningState.RUNNING_ON_GRID;
import static io.openems.edge.sungrow.common.enums.RunningState.STANDBY;
import static io.openems.edge.sungrow.common.enums.RunningState.STARTING;
import static io.openems.edge.sungrow.common.enums.RunningState.UNDEFINED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GoRunningHandlerTest {

	@Test
	public void testRunningStates() {
		assertTrue(GoRunningHandler.isRunning(RUNNING_ON_GRID));
		assertTrue(GoRunningHandler.isRunning(COMPULSORY_MODE));
	}

	@Test
	public void testNonRunningStates() {
		assertFalse(GoRunningHandler.isRunning(STANDBY));
		assertFalse(GoRunningHandler.isRunning(STARTING));
		assertFalse(GoRunningHandler.isRunning(FAULT));
		assertFalse(GoRunningHandler.isRunning(UNDEFINED));
	}
}
