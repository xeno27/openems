#!/usr/bin/env python3
"""Sungrow SH - Register und Steuerung testen (SH5T-SH25T, SH-RT).

Zwei Stolpersteine, die dieses Skript bereits beruecksichtigt:

  Adressierung   Die hier verwendeten Adressen sind Protokolladressen.
                 Die Sungrow-Doku zaehlt ab 1: Doku-Adresse = Adresse hier + 1.

  Wortreihenfolge  32-Bit-Werte kommen LOW-WORD ZUERST. Das ist der haeufigste
                 Dekodierfehler bei Sungrow.

Steuerung nach der in evcc erprobten Sequenz:
    Laden:    13049 = 2 (Forced) -> 13050 = 0xAA -> 13051 = Leistung in W
    Entladen: 13049 = 2          -> 13050 = 0xBB -> 13051 = Leistung in W
    Normal:   13050 = 0xCC       -> 13049 = 0    -> 33046/33047 auf Nennwerte

Beispiele:
    uv run --with pymodbus sungrow_lab.py --host 192.168.1.50 probe
    uv run --with pymodbus sungrow_lab.py --host 192.168.1.50 read
    uv run --with pymodbus sungrow_lab.py --host 192.168.1.50 charge 2000 --yes

WiNet-S/WiNet-S2 braucht aktuelle Dongle-Firmware, sonst fehlen Leistung und SOC.
Nur ein Master pro Bus beziehungsweise Proxy.
"""
from __future__ import annotations

import argparse
import sys

from modbuslab import (
    HOLDING, INPUT, Bus, ModbusTcpClient, Plan, add_connection_args, add_scan_args,
    add_site_args, add_write_args, apply_site_defaults, check_identity, connect,
    dump_registers, environment_info, heading, own_network, percent_of, run_controlled,
    s16, s32_lo, scale, scan_port, table, u32_lo,
)

CMD_CHARGE = 0xAA
CMD_DISCHARGE = 0xBB
CMD_STOP = 0xCC

EMS_MODE = {0: "Eigenverbrauch", 2: "Forced (Compulsory)", 3: "External EMS", 4: "VPP"}
RUNNING_STATE = {
    0x0000: "Running (on-grid)", 0x0040: "Running (on-grid)", 0x0014: "Microgrid",
    0x0400: "Maintain mode", 0x0800: "Compulsory mode", 0x1000: "Running (off-grid)",
    0x4000: "External EMS mode", 0x4001: "Emergency charging", 0x1111: "Uninitialized",
    0x8000: "Stop", 0x0001: "Stop", 0x1300: "Key stop", 0x1400: "Standby",
    0x1200: "Initial standby", 0x1600: "Starting", 0x5500: "Fault",
    0x8100: "Derating running", 0x8200: "Dispatch running", 0x9100: "Warn running",
}
DEVICE_TYPE = {
    0xE20: "SH5T", 0xE21: "SH6T", 0xE22: "SH8T", 0xE23: "SH10T", 0xE24: "SH12T",
    0xE25: "SH15T", 0xE26: "SH20T", 0xE28: "SH25T",
    0xE00: "SH5.0RT", 0xE01: "SH6.0RT", 0xE02: "SH8.0RT", 0xE03: "SH10RT",
}


def read_all(bus: Bus) -> dict[str, object]:
    r: dict[int, int] = {}
    for start, count in [(4999, 22), (5032, 5), (5213, 2), (5241, 1), (5600, 2),
                         (5627, 12), (12999, 2), (13007, 5), (13019, 30)]:
        blk = bus.read(INPUT, start, count)
        if blk:
            r.update(blk)
    h: dict[int, int] = {}
    for start, count in [(12999, 1), (13049, 3), (13057, 3), (13073, 2), (13079, 1), (33046, 2)]:
        blk = bus.read(HOLDING, start, count)
        if blk:
            h.update(blk)

    # Batterieleistung robust: alte Firmware liefert den Betrag ohne Vorzeichen
    batt = s16(r.get(13021))
    if batt is not None:
        charging = bool((r.get(13000, 0) & 0x02)) or (s16(r.get(13020)) or 0) < 0
        if charging and batt >= 0:
            batt = -batt

    dtc = r.get(4999)
    return {
        "_raw_input": r,
        "_raw_holding": h,
        "Geraetetyp (4999)": f"0x{dtc:X} {DEVICE_TYPE.get(dtc, '')}".strip() if dtc else None,
        "Nennleistung [W] (5000)": None if r.get(5000) is None else r[5000] * 100,
        "Ausgangstyp (5001)": r.get(5001),
        "Innentemperatur [C] (5007)": scale(s16(r.get(5007)), 0.1),
        "PV-Leistung [W] (5016)": u32_lo(r, 5016),
        "Spannung L1/L2/L3 [V]": [scale(r.get(a), 0.1) for a in (5018, 5019, 5020)]
                                 if 5018 in r else None,
        "Blindleistung [var] (5032)": s32_lo(r, 5032),
        "Netzfrequenz [Hz] (5241)": scale(r.get(5241), 0.01, 2),
        "Zaehlerleistung [W] (5600)": s32_lo(r, 5600),
        "BDC-Nennleistung [W] (5627)": None if r.get(5627) is None else r[5627] * 100,
        "Batteriestrom [A] (5630)": scale(s16(r.get(5630)), 0.1),
        "Betriebszustand (12999)": RUNNING_STATE.get(r.get(12999), r.get(12999)),
        "Power-Flow-Status (13000)": None if r.get(13000) is None else f"0x{r[13000]:04X}",
        "Lastleistung [W] (13007)": s32_lo(r, 13007),
        "Einspeiseleistung [W] (13009)": s32_lo(r, 13009),
        "Batteriespannung [V] (13019)": scale(r.get(13019), 0.1),
        "Batterieleistung [W] (+ entladen)": batt,
        "SOC [%] (13022)": scale(r.get(13022), 0.1),
        "SOH [%] (13023)": scale(r.get(13023), 0.1),
        "Batterietemperatur [C] (13024)": scale(s16(r.get(13024)), 0.1),
        "Entladung gesamt [kWh] (13026)": scale(u32_lo(r, 13026), 0.1),
        "Gesamtwirkleistung [W] (13033)": s32_lo(r, 13033),
        "Netzbezug gesamt [kWh] (13036)": scale(u32_lo(r, 13036), 0.1),
        "Ladung gesamt [kWh] (13040)": scale(u32_lo(r, 13040), 0.1),
        "Einspeisung gesamt [kWh] (13045)": scale(u32_lo(r, 13045), 0.1),
        "Start/Stop (Holding 12999)": None if h.get(12999) is None else f"0x{h[12999]:02X}",
        "EMS-Mode (13049)": EMS_MODE.get(h.get(13049), h.get(13049)),
        "Lade-/Entladebefehl (13050)": None if h.get(13050) is None else f"0x{h[13050]:02X}",
        "Forced-Leistung [W] (13051)": h.get(13051),
        "Max-SOC [%] (13057)": scale(h.get(13057), 0.1),
        "Min-SOC [%] (13058)": scale(h.get(13058), 0.1),
        "Einspeisebegrenzung [W] (13073)": h.get(13073),
        "EMS-Heartbeat [s] (13079)": h.get(13079),
        "Max. Ladeleistung [W] (33046)": None if h.get(33046) is None else h[33046] * 10,
        "Max. Entladeleistung [W] (33047)": None if h.get(33047) is None else h[33047] * 10,
    }


def observe(bus: Bus) -> list[tuple[str, object]]:
    d = read_all(bus)
    return [
        ("SOC [%]", d.get("SOC [%] (13022)")),
        ("Batterie [W] (+ entladen)", d.get("Batterieleistung [W] (+ entladen)")),
        ("Zaehler [W] (+ Bezug)", d.get("Zaehlerleistung [W] (5600)")),
        ("PV [W]", d.get("PV-Leistung [W] (5016)")),
        ("EMS-Mode (13049)", d.get("EMS-Mode (13049)")),
        ("Befehl (13050)", d.get("Lade-/Entladebefehl (13050)")),
        ("Forced-Leistung [W] (13051)", d.get("Forced-Leistung [W] (13051)")),
        # Die Grenzen gehoeren in die Tabelle: 'hold' wirkt ueber 33047 und
        # laesst 13049-13051 absichtlich unveraendert. Ohne diese Zeilen sieht
        # der Lauf aus, als haette der Befehl nichts getan.
        ("Max. Laden [W] (33046)", d.get("Max. Ladeleistung [W] (33046)")),
        ("Max. Entladen [W] (33047)", d.get("Max. Entladeleistung [W] (33047)")),
        ("Betriebszustand", d.get("Betriebszustand (12999)")),
    ]


def nominal_limits(bus: Bus, override: float | None) -> int:
    """Nennwert fuer 33046/33047 in Einheiten von 0,01 kW."""
    if override:
        return max(1, round(override / 10))
    bdc = bus.read_one(INPUT, 5627)
    if bdc:
        return max(1, round(bdc * 100 / 10))
    return 1000  # 10 kW als vorsichtige Annahme


def identity(bus: Bus) -> str | None:
    """Kennung, die nur ein Sungrow-Hybrid liefert (Geraetetyp 4999)."""
    dtc = bus.read_one(INPUT, 4999)
    if not dtc:
        return None
    name = DEVICE_TYPE.get(dtc)
    if name is None and not 0x0D00 <= dtc <= 0x0F00:
        return None
    nominal = bus.read_one(INPUT, 5000)
    return (f"Sungrow {name or f'Typ 0x{dtc:X}'}"
            + (f", {nominal * 100} W Nennleistung" if nominal else ""))


def cmd_scan(args) -> int:
    """Den Wechselrichter im Netz suchen.

    Zuerst alle Adressen, die den Modbus-Port annehmen, dann bei jeder
    nachfragen, ob dort wirklich ein Sungrow antwortet. Ein offener Port 502
    allein sagt wenig - den haben auch Waermepumpen, Zaehler und Gateways.
    """
    network = args.network or own_network()
    if not network:
        print("Eigenes Netz nicht ermittelbar. Bitte '--network 192.168.1.0/24' angeben.",
              file=sys.stderr)
        return 2

    heading(f"Suche in {network} auf Port {args.scan_port}")
    print("  Das dauert ein paar Sekunden ...")
    hosts = scan_port(network, args.scan_port, args.scan_timeout)
    if not hosts:
        print("\n  Keine Adresse nimmt den Port an.")
        print("  Moegliche Ursachen: der Wechselrichter haengt in einem anderen Netz,")
        print("  der WiNet-Dongle hat Modbus TCP nicht freigeschaltet, oder eine")
        print("  Firewall blockt. Mit '--network' gezielt ein anderes Netz absuchen.")
        return 1

    heading("Gefunden")
    found = 0
    for host in hosts:
        client = ModbusTcpClient(host, port=args.scan_port, timeout=2.0)
        if not client.connect():
            print(f"  {host:<16} Port offen, aber keine Verbindung")
            continue
        bus = Bus(client, args.unit)
        name = identity(bus)
        bus.close()
        if name:
            found += 1
            print(f"  {host:<16} {name}")
        else:
            print(f"  {host:<16} antwortet, aber nicht wie ein Sungrow")

    if found == 1:
        heading("Naechster Schritt")
        print("  Die Adresse in sites.ini unter [sungrow] als 'host' eintragen,")
        print("  dann:  python3 sungrow_lab.py --site sungrow probe")
    elif found == 0:
        heading("Nichts Passendes dabei")
        print("  Kein Geraet hat sich als Sungrow zu erkennen gegeben.")
        print("  Bei WiNet-S: Modbus TCP im Dongle freischalten, und pruefen, ob")
        print("  eine andere Verbindung (iSolarCloud, evcc, Home Assistant) den")
        print("  einzigen erlaubten TCP-Platz belegt.")
    return 0


def cmd_probe(bus: Bus, args) -> int:
    heading("Verbindung und Identitaet")
    print(f"  {environment_info()}")
    d = read_all(bus)
    table([(k, d.get(k)) for k in [
        "Geraetetyp (4999)", "Nennleistung [W] (5000)", "BDC-Nennleistung [W] (5627)",
        "Betriebszustand (12999)", "SOC [%] (13022)", "Batterieleistung [W] (+ entladen)",
        "EMS-Mode (13049)", "Lade-/Entladebefehl (13050)", "Forced-Leistung [W] (13051)",
        "Max. Ladeleistung [W] (33046)", "Max. Entladeleistung [W] (33047)",
    ]])

    heading("Plausibilitaet")
    pn = d.get("Nennleistung [W] (5000)")
    soc = d.get("SOC [%] (13022)")
    if d.get("Geraetetyp (4999)") and pn:
        print("  Input-Bank antwortet plausibel.")
    else:
        print("  Input-Bank liefert keine Identitaet - Verbindung oder Unit-ID pruefen.")
    if soc is not None:
        print(f"  Batteriedaten vorhanden (SOC {soc} %).")
    else:
        print("  Keine Batteriedaten. Bei WiNet-S: Dongle-Firmware aktualisieren.")
    if d.get("EMS-Mode (13049)") is None:
        print("  Holding-Bank 13049 antwortet nicht - Steuerung waere nicht moeglich.")
    else:
        print("  Holding-Bank antwortet, Steuerung ist erreichbar.")
    return 0


def cmd_read(bus: Bus, args) -> int:
    heading("Sungrow SH")
    d = read_all(bus)
    table([(k, v) for k, v in d.items() if not k.startswith("_")], width=38)
    return 0


def current_limits(bus: Bus) -> tuple[int, int]:
    """Die eingestellten Lade-/Entladegrenzen 33046/33047, roh in 0,01 kW.

    Diese Werte sind Anlagenparameter, keine Sollwerte: die Entladegrenze
    entspricht oft dem genehmigten Netzanschluss. Sie werden deshalb gelesen,
    beim Beenden exakt so wiederhergestellt, und nur angehoben, wenn der
    gewuenschte Sollwert sonst gar nicht erreichbar waere.
    """
    blk = bus.read(HOLDING, 33046, 2)
    if not blk:
        nominal = nominal_limits(bus, None)
        return nominal, nominal
    return blk[33046], blk[33047]


def build_plan(bus: Bus, watt: float, charging: bool, args) -> Plan:
    power = max(0, round(watt))
    what = "Laden" if charging else "Entladen"
    cmd = CMD_CHARGE if charging else CMD_DISCHARGE
    charge_limit, discharge_limit = current_limits(bus)
    needed = max(1, -(-power // 10))          # W -> 0,01 kW, aufgerundet

    plan = Plan(f"Plan: {what} mit {power} W (Forced Mode)")
    plan.add(13049, 2, "EMS-Mode", "Forced Mode - der Wechselrichter folgt dem Sollwert",
             once=True)
    plan.add(13050, cmd, "Lade-/Entladebefehl", f"0x{cmd:02X} = {what}", once=True)

    # Die Grenze nur anfassen, wenn sie den Sollwert sonst abschneiden wuerde.
    limit_address = 33046 if charging else 33047
    limit_now = charge_limit if charging else discharge_limit
    if needed > limit_now:
        plan.add(limit_address, needed,
                 f"Max. {what}leistung ({limit_address})",
                 f"angehoben von {limit_now * 10} W auf {needed * 10} W - "
                 f"der Sollwert waere sonst begrenzt", once=True)
        plan.add_reset(limit_address, limit_now,
                       f"Max. {what}leistung ({limit_address})",
                       f"zurueck auf den vorgefundenen Wert {limit_now * 10} W")

    plan.add(13051, power, "Forced-Leistung", f"{power} W")
    plan.add_reset(13050, CMD_STOP, "Lade-/Entladebefehl", "0xCC = Stopp")
    plan.add_reset(13049, 0, "EMS-Mode", "Eigenverbrauch")
    return plan


def cmd_power(bus: Bus, args, charging: bool) -> int:
    if not check_identity(bus, "Sungrow SH", identity, args.yes, args.force):
        return 2
    plan = build_plan(bus, args.watt, charging, args)
    return run_controlled(bus, plan, args.yes, args.duration, args.interval, observe)


def cmd_hold(bus: Bus, args) -> int:
    """Entladen sperren, Laden erlaubt - die evcc-Sequenz fuer 'Halten'."""
    if not check_identity(bus, "Sungrow SH", identity, args.yes, args.force):
        return 2
    charge_limit, discharge_limit = current_limits(bus)
    plan = Plan("Plan: Entladen sperren (Halten)")
    plan.add(13049, 0, "EMS-Mode", "Eigenverbrauch", once=True)
    plan.add(13050, CMD_STOP, "Lade-/Entladebefehl", "0xCC = Stopp", once=True)
    plan.add(33047, 1, "Max. Entladeleistung (33047)",
             f"von {discharge_limit * 10} W auf 10 W - das wirkt als Entladesperre. "
             f"Die Ladegrenze ({charge_limit * 10} W) bleibt unangetastet.", once=True)
    plan.add_reset(33047, discharge_limit, "Max. Entladeleistung (33047)",
                   f"zurueck auf den vorgefundenen Wert {discharge_limit * 10} W")
    plan.add_reset(13050, CMD_STOP, "Lade-/Entladebefehl", "0xCC = Stopp")
    plan.add_reset(13049, 0, "EMS-Mode", "Eigenverbrauch")
    return run_controlled(bus, plan, args.yes, args.duration, args.interval, observe)


def cmd_reset(bus: Bus, args) -> int:
    """Aus dem Forced Mode heraus, ohne die Anlagenparameter zu ueberschreiben."""
    if not check_identity(bus, "Sungrow SH", identity, args.yes, args.force):
        return 2
    charge_limit, discharge_limit = current_limits(bus)
    nominal = nominal_limits(bus, args.reference)

    plan = Plan("Plan: Normalbetrieb wiederherstellen")
    plan.add(13050, CMD_STOP, "Lade-/Entladebefehl", "0xCC = Stopp", once=True)
    plan.add(13049, 0, "EMS-Mode", "Eigenverbrauch", once=True)

    # Eine Grenze nur dann anheben, wenn sie so niedrig steht, dass sie aus
    # einem abgebrochenen Testlauf stammen muss. Sonst ist sie ein bewusst
    # gesetzter Anlagenparameter - oft der genehmigte Netzanschluss.
    for address, value, name in [(33046, charge_limit, "Ladeleistung"),
                                 (33047, discharge_limit, "Entladeleistung")]:
        if value < 10:
            plan.add(address, nominal, f"Max. {name} ({address})",
                     f"steht auf {value * 10} W - das sieht nach einem "
                     f"haengengebliebenen Test aus, zurueck auf {nominal * 10} W",
                     once=True)
        else:
            print(f"  {address} steht auf {value * 10} W und bleibt unveraendert.")

    plan.show(bus)
    if not args.yes:
        print("\n  Trockenlauf: es wurde nichts geschrieben. Mit '--yes' ausfuehren.")
        return 0
    plan.apply(bus)
    heading("Danach")
    table(observe(bus), width=38)
    return 0


def cmd_selftest(_bus, _args) -> int:
    checks = [
        ("u32_lo", u32_lo({1: 0x86A0, 2: 0x0001}, 1), 100000),
        ("s32_lo negativ", s32_lo({1: 0xFFCE, 2: 0xFFFF}, 1), -50),
        ("s16 negativ", s16(0xFFCE), -50),
        ("scale SOC", scale(505, 0.1), 50.5),
        ("percent", percent_of(2000, 10000), 20),
    ]
    heading("Selbsttest der Dekoder")
    failed = 0
    for name, got, want in checks:
        ok = got == want
        failed += 0 if ok else 1
        print(f"  {'OK  ' if ok else 'FEHL'} {name:<24} {got!r} (erwartet {want!r})")

    print("\n  Wortreihenfolge-Probe: dasselbe Registerpaar einmal falsch gelesen")
    wrong = (0x86A0 << 16) | 0x0001
    print(f"    korrekt (low first): {u32_lo({1: 0x86A0, 2: 0x0001}, 1)}")
    print(f"    falsch  (high first): {wrong}  <- typischer Riesenwert")
    print(f"\n  {len(checks) - failed} von {len(checks)} bestanden.")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    add_connection_args(ap)
    ap.add_argument("--reference", type=float,
                    help="Nennleistung in W fuer 33046/33047 (sonst aus 5627)")
    ap.add_argument("--verbose", action="store_true", help="Modbus-Fehler einzeln anzeigen")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("probe", help="Verbindung und Identitaet pruefen")
    sub.add_parser("read", help="Alle bekannten Werte dekodiert anzeigen")
    p_watch = sub.add_parser("watch", help="Werte zyklisch anzeigen")
    p_watch.add_argument("--interval", type=float, default=2.0)
    p_dump = sub.add_parser("dump", help="Rohregister anzeigen")
    p_dump.add_argument("kind", choices=[HOLDING, INPUT])
    p_dump.add_argument("start", type=int)
    p_dump.add_argument("count", type=int)
    for name, helptext in [("charge", "Laden mit X Watt"), ("discharge", "Entladen mit X Watt")]:
        p = sub.add_parser(name, help=helptext)
        p.add_argument("watt", type=float)
        add_write_args(p)
    add_write_args(sub.add_parser("hold", help="Entladen sperren"))
    add_write_args(sub.add_parser("reset", help="Normalbetrieb wiederherstellen"))
    sub.add_parser("selftest", help="Dekoder ohne Anlage pruefen")
    add_scan_args(sub.add_parser("scan", help="Wechselrichter im Netz suchen"))

    add_site_args(ap)
    if apply_site_defaults(ap):
        return 0
    args = ap.parse_args()
    if args.cmd == "selftest":
        return cmd_selftest(None, args)
    if args.cmd == "scan":
        return cmd_scan(args)

    bus = connect(args)
    bus.verbose = args.verbose
    try:
        if args.cmd == "probe":
            return cmd_probe(bus, args)
        if args.cmd == "read":
            return cmd_read(bus, args)
        if args.cmd == "watch":
            import time
            while True:
                cmd_read(bus, args)
                time.sleep(args.interval)
        if args.cmd == "dump":
            return dump_registers(bus, args.kind, args.start, args.count)
        if args.cmd == "charge":
            return cmd_power(bus, args, charging=True)
        if args.cmd == "discharge":
            return cmd_power(bus, args, charging=False)
        if args.cmd == "hold":
            return cmd_hold(bus, args)
        if args.cmd == "reset":
            return cmd_reset(bus, args)
    except KeyboardInterrupt:
        print("\nAbgebrochen.")
        return 130
    finally:
        bus.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
