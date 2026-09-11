package io.openems.edge.saj.charger;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.dccharger.api.EssDcCharger;

/**
 * A PV string (MPPT port) of a SAJ CH2 inverter.
 */
public interface SajCharger extends EssDcCharger, OpenemsComponent {
}
