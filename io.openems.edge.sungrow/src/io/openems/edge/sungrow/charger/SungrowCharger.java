package io.openems.edge.sungrow.charger;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.dccharger.api.EssDcCharger;

/**
 * A PV string (MPPT port) of a Sungrow SH inverter.
 */
public interface SungrowCharger extends EssDcCharger, OpenemsComponent {
}
