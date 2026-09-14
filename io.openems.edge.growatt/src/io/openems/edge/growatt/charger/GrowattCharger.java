package io.openems.edge.growatt.charger;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.dccharger.api.EssDcCharger;

/**
 * A PV string (MPPT port) of a Growatt SPH inverter.
 */
public interface GrowattCharger extends EssDcCharger, OpenemsComponent {
}
