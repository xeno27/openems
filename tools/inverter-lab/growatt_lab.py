#!/usr/bin/env python3
"""Growatt SPH - Register und Steuerung testen (SPH3000-6000TL BL, SPH4000-10000TL3 BH-UP).

Kennt beide Registerbaenke:

  legacy  Storage-Protokoll (Input 0-124 / 1000-1124, Holding 1044-1102).
          Steuerung ueber Prioritaet + Leistungsrate + Zeitfenster.
          Diese Register liegen im EEPROM - nicht dauerhaft zyklisch schreiben.

  vpp     VPP-Protokoll (Holding 30000-30999, Input 31000-31599).
          Steuerung ueber einen echten Sollwert in 30409.
          30407/30408/30409 sind laut Doku 'Not storage', also fuer
          zyklisches Schreiben vorgesehen.

Beispiele:
    uv run --with pymodbus growatt_lab.py --serial /dev/ttyUSB0 probe
    uv run --with pymodbus growatt_lab.py --serial /dev/ttyUSB0 read
    uv run --with pymodbus growatt_lab.py --serial /dev/ttyUSB0 dump holding 30000 20
    uv run --with pymodbus growatt_lab.py --serial /dev/ttyUSB0 charge 1000        # Trockenlauf
    uv run --with pymodbus growatt_lab.py --serial /dev/ttyUSB0 charge 1000 --yes  # schreibt wirklich

Nur ein Modbus-Master auf dem RS485-Bus. Einen parallel pollenden Shine-Stick
vorher abziehen, sonst CRC-Fehler und fehlende Antworten.
"""
from __future__ import annotations

import argparse
import sys

from modbuslab import (
    HOLDING, INPUT, Bus, Plan, add_connection_args, add_site_args, add_write_args,
    apply_site_defaults, ascii_str, check_identity, connect, dump_registers, environment_info,
    heading, hhmm, looks_like_text, percent_of, run_controlled, s16, s32_hi, scale, table,
    u32_hi, write_probe,
)

SLOT_START = 0x0000          # 00:00
SLOT_STOP = 0x173B           # 23:59

PRIORITY = {0: "Load first", 1: "Battery first", 2: "Grid first"}
WORK_MODE = {
    0: "Waiting", 1: "Self test", 3: "Fault", 4: "Flash",
    5: "PV+Batterie online", 6: "Batterie online", 7: "PV offline", 8: "Batterie offline",
}
VPP_WORK_STATE = {
    0: "Standby", 1: "Self test", 3: "Fault", 4: "Upgrade",
    5: "PV online, Batterie offline", 6: "Batterie online", 7: "PV+Batterie Inselbetrieb",
    8: "Batterie online, PV offline", 9: "Bypass",
}
VPP_BATTERY_STATE = {0: "Standby", 1: "Getrennt", 2: "Laden", 3: "Entladen", 4: "Fault", 5: "Upgrade"}


# --------------------------------------------------------------------------
# Lesen
# --------------------------------------------------------------------------

def read_legacy(bus: Bus) -> dict[str, object]:
    r: dict[int, int] = {}
    for start, count in [(0, 3), (93, 3), (118, 2), (1000, 2), (1009, 8), (1021, 2),
                         (1029, 2), (1040, 2), (1046, 2), (1054, 2)]:
        blk = bus.read(INPUT, start, count)
        if blk:
            r.update(blk)
    h: dict[int, int] = {}
    for start, count in [(1044, 1), (1070, 2), (1080, 3), (1090, 3), (1100, 3)]:
        blk = bus.read(HOLDING, start, count)
        if blk:
            h.update(blk)

    charge = scale(u32_hi(r, 1011), 0.1)
    discharge = scale(u32_hi(r, 1009), 0.1)
    to_user = scale(u32_hi(r, 1021), 0.1)
    to_grid = scale(u32_hi(r, 1029), 0.1)
    return {
        "_raw_input": r,
        "_raw_holding": h,
        "Inverter-Status": r.get(0),
        "System-Betriebsart": WORK_MODE.get(r.get(1000), r.get(1000)),
        "PV-Leistung [W]": scale(u32_hi(r, 1), 0.1),
        "Batterie [W] (+ entladen)": None if charge is None or discharge is None else round(discharge - charge, 1),
        "  davon laden [W]": charge,
        "  davon entladen [W]": discharge,
        "Batteriespannung [V]": scale(r.get(1013), 0.1),
        "SOC [%]": r.get(1014),
        "Batterietemperatur [C]": scale(r.get(1040), 0.1),
        "Netz [W] (+ Bezug)": None if to_user is None or to_grid is None else round(to_user - to_grid, 1),
        "Netzbezug gesamt [kWh]": scale(u32_hi(r, 1046), 0.1),
        "Entladung gesamt [kWh]": scale(u32_hi(r, 1054), 0.1),
        "Prioritaet (gelesen 118)": PRIORITY.get(r.get(118), r.get(118)),
        "Prioritaet (gesetzt 1044)": PRIORITY.get(h.get(1044), h.get(1044)),
        "Grid-First Rate [%] (1070)": h.get(1070),
        "Grid-First Slot 1 (1080-82)": f"{hhmm(h.get(1080))}-{hhmm(h.get(1081))} aktiv={h.get(1082)}"
                                       if 1080 in h else None,
        "Battery-First Rate [%] (1090)": h.get(1090),
        "AC-Charge (1092)": h.get(1092),
        "Battery-First Slot 1 (1100-02)": f"{hhmm(h.get(1100))}-{hhmm(h.get(1101))} aktiv={h.get(1102)}"
                                          if 1100 in h else None,
    }


def read_vpp(bus: Bus) -> dict[str, object]:
    h: dict[int, int] = {}
    for start, count in [(30000, 32), (30099, 2), (30112, 2), (30151, 5), (30200, 5),
                         (30404, 7), (30474, 1)]:
        blk = bus.read(HOLDING, start, count)
        if blk:
            h.update(blk)
    r: dict[int, int] = {}
    for start, count in [(31000, 9), (31058, 2), (31100, 16), (31200, 20)]:
        blk = bus.read(INPUT, start, count)
        if blk:
            r.update(blk)

    return {
        "_raw_holding": h,
        "_raw_input": r,
        "DTC (30000)": h.get(30000),
        "Seriennummer (30001)": ascii_str(h, 30001, 15),
        "Nennleistung Pn [W] (30016)": scale(u32_hi(h, 30016), 0.1),
        "Max. Wirkleistung [W] (30018)": scale(u32_hi(h, 30018), 0.1),
        "BDC-Nennleistung [W] (30026)": scale(u32_hi(h, 30026), 0.1),
        "Batterietyp (30030)": h.get(30030),
        "VPP-Protokollversion (30099)": h.get(30099),
        "Control authority (30100)": h.get(30100),
        "Laenderkennung (30102)": h.get(30102),
        "Modbus-Adresse (30112)": h.get(30112),
        "Baudrate (30113)": {0: "9600", 1: "38400"}.get(h.get(30113), h.get(30113)),
        "Wirkleistungsderating [%] (30151)": h.get(30151),
        "Statische Begrenzung [%] (30154)": h.get(30154),
        "Export-Limit aktiv (30200)": h.get(30200),
        "Export-Limit Rate [%] (30201)": s16(h.get(30201)),
        "EMS-Watchdog Zeit [s] (30203)": h.get(30203),
        "EMS-Watchdog aktiv (30204)": h.get(30204),
        "Ladeschluss-SOC [%] (30404)": h.get(30404),
        "Entladeschluss-SOC [%] (30405)": h.get(30405),
        "Remote aktiv (30407)": h.get(30407),
        "Remote Dauer [min] (30408)": h.get(30408),
        "Remote Sollwert [%] (30409)": s16(h.get(30409)),
        "AC-Charge (30410)": h.get(30410),
        "Wirksamer Wert [%] (30474)": s16(h.get(30474)),
        "Betriebszustand (31000)": VPP_WORK_STATE.get(r.get(31000), r.get(31000)),
        "Batteriezustand (31001)": VPP_BATTERY_STATE.get(r.get(31001), r.get(31001)),
        "Prioritaet (31002)": PRIORITY.get(r.get(31002), r.get(31002)),
        "Fehlercode (31005/31006)": None if 31005 not in r else f"{r.get(31005)} / {r.get(31006)}",
        "Alarmcode (31007/31008)": None if 31007 not in r else f"{r.get(31007)} / {r.get(31008)}",
        "PV-Leistung [W] (31058)": scale(s32_hi(r, 31058), 0.1),
        "AC-Leistung [W] (31100)": scale(s32_hi(r, 31100), 0.1),
        "Netzfrequenz [Hz] (31105)": scale(r.get(31105), 0.01, 2),
        "Zaehlerleistung [W] (31112)": scale(s32_hi(r, 31112), 0.1),
        "Wechselrichtertemp [C] (31114)": scale(s16(r.get(31114)), 0.1),
        "Batterieleistung [W] (31200)": scale(s32_hi(r, 31200), 0.1),
        "Max. Ladeleistung [W] (31210)": scale(u32_hi(r, 31210), 0.1),
        "Max. Entladeleistung [W] (31212)": scale(u32_hi(r, 31212), 0.1),
        "Batteriespannung [V] (31214)": scale(s16(r.get(31214)), 0.1),
        "SOC [%] (31217)": r.get(31217),
        "SOH [%] (31218)": r.get(31218),
    }


def vpp_reference(bus: Bus, override: float | None) -> float | None:
    """Bezugsleistung fuer die Prozentangabe in 30409."""
    if override:
        return override
    blk = bus.read(HOLDING, 30026, 2)
    if blk:
        bdc = u32_hi(blk, 30026)
        if bdc:
            return bdc / 10.0
    return None


# --------------------------------------------------------------------------
# Befehle
# --------------------------------------------------------------------------

def identity(bus: Bus) -> str | None:
    """Kennung, die nur ein Growatt SPH liefert."""
    dtc_vpp = bus.read_one(HOLDING, 30000)
    if dtc_vpp:
        return f"Growatt, DTC {dtc_vpp} (VPP-Bank 30000)"
    dtc_legacy = bus.read_one(HOLDING, 43)
    mode = bus.read_one(INPUT, 1000)
    if dtc_legacy or mode is not None:
        return f"Growatt, DTC {dtc_legacy} / Betriebsart {mode} (Legacy-Bank)"
    return None


def cmd_probe(bus: Bus, args) -> int:
    heading("Verbindung")
    print(f"  {environment_info()}")
    dtc_legacy = bus.read_one(HOLDING, 43)
    soc = bus.read_one(INPUT, 1014)
    table([
        ("Legacy Holding 43 (DTC)", dtc_legacy),
        ("Legacy Input 1014 (SOC) [%]", soc),
    ])
    legacy_ok = dtc_legacy is not None or soc is not None
    print(f"\n  Legacy-Bank: {'antwortet' if legacy_ok else 'KEINE Antwort'}")

    heading("VPP-Registerbank")
    vpp = read_vpp(bus)
    interesting = [
        "DTC (30000)", "Seriennummer (30001)", "Nennleistung Pn [W] (30016)",
        "BDC-Nennleistung [W] (30026)", "VPP-Protokollversion (30099)",
        "Control authority (30100)", "Laenderkennung (30102)",
        "EMS-Watchdog Zeit [s] (30203)", "Remote aktiv (30407)",
        "Remote Sollwert [%] (30409)", "Wirksamer Wert [%] (30474)",
        "Betriebszustand (31000)", "SOC [%] (31217)", "Batterieleistung [W] (31200)",
    ]
    table([(k, vpp.get(k)) for k in interesting])

    heading("Bewertung")
    dtc = vpp.get("DTC (30000)")
    sn = vpp.get("Seriennummer (30001)")
    pn = vpp.get("Nennleistung Pn [W] (30016)")
    soc_vpp = vpp.get("SOC [%] (31217)")

    evidence = []
    if looks_like_text(sn):
        evidence.append("Seriennummer lesbar")
    if pn:
        evidence.append("Nennleistung gesetzt")
    if soc_vpp:
        evidence.append("SOC ueber Input-Bank")
    if dtc:
        evidence.append(f"DTC {dtc}")

    if len(evidence) >= 2:
        print("  VPP-Bank ist echt befuellt: " + ", ".join(evidence) + ".")
        print("  Die Steuerung ueber 30407/30409 sollte funktionieren.")
        print("  Naechster Schritt: 'charge 500' im Trockenlauf, dann mit --yes.")
    elif dtc:
        print(f"  Nur der DTC antwortet ({dtc}), sonst alles 0.")
        print("  Das kann eine frueh befuellte Firmware sein - oder eine Bank, die")
        print("  formal antwortet, aber keine Wirkung hat. Schreibversuche waeren")
        print("  dann wirkungslos, ohne dass ein Modbus-Fehler kommt.")
        print("  Pruefen mit: 'charge 500 --yes --duration 30' und dabei auf")
        print("  'Wirksamer Wert [%] (30474)' und die Batterieleistung achten.")
    else:
        print("  Die VPP-Bank antwortet nicht. Es bleibt beim Legacy-Pfad.")
    return 0


def cmd_read(bus: Bus, args) -> int:
    heading("Legacy-Bank (Storage-Protokoll)")
    legacy = read_legacy(bus)
    table([(k, v) for k, v in legacy.items() if not k.startswith("_")])

    if args.bank != "legacy":
        vpp = read_vpp(bus)
        if vpp.get("DTC (30000)"):
            heading("VPP-Bank")
            table([(k, v) for k, v in vpp.items() if not k.startswith("_")])
    return 0


def observe(bus: Bus) -> list[tuple[str, object]]:
    legacy = read_legacy(bus)
    rows = [
        ("SOC [%]", legacy.get("SOC [%]")),
        ("Batterie [W] (+ entladen)", legacy.get("Batterie [W] (+ entladen)")),
        ("Netz [W] (+ Bezug)", legacy.get("Netz [W] (+ Bezug)")),
        ("PV [W]", legacy.get("PV-Leistung [W]")),
        ("Prioritaet (118)", legacy.get("Prioritaet (gelesen 118)")),
    ]
    blk = bus.read(HOLDING, 30407, 4)
    actual = bus.read(HOLDING, 30474, 1)
    if blk or actual:
        rows.append(("VPP Remote an (30407)", blk.get(30407) if blk else None))
        rows.append(("VPP Sollwert [%] (30409)", s16(blk.get(30409)) if blk else None))
        rows.append(("VPP AC-Charge (30410)", blk.get(30410) if blk else None))
        rows.append(("VPP wirksam [%] (30474)", s16(actual.get(30474)) if actual else None))
    state = bus.read(INPUT, 31000, 3)
    if state:
        rows.append(("VPP Zustand (31000)", VPP_WORK_STATE.get(state.get(31000), state.get(31000))))
        rows.append(("VPP Batterie (31001)",
                     VPP_BATTERY_STATE.get(state.get(31001), state.get(31001))))
        rows.append(("VPP Prioritaet (31002)", PRIORITY.get(state.get(31002), state.get(31002))))
    return rows


def build_plan_vpp(watt: float, charging: bool, reference: float,
                   minutes: int = 0, ac_charge: int | None = None) -> Plan:
    percent = percent_of(watt if charging else -watt, reference)
    what = "Laden" if charging else "Entladen"
    plan = Plan(f"Plan: {what} mit {watt:.0f} W ueber die VPP-Bank "
                f"({percent:+d} % von {reference:.0f} W Bezugsleistung)")
    # 30407..30409 liegen zusammenhaengend und sind laut Doku nicht im EEPROM
    dauer = "unbegrenzt" if minutes == 0 else f"{minutes} min"
    plan.add(30407, [1, minutes, percent & 0xFFFF],
             "Remote enable / Dauer / Sollwert",
             f"Fernsteuerung an, {dauer}, {percent:+d} % "
             f"({'positiv = laden' if charging else 'negativ = entladen'})")
    if ac_charge is not None:
        # 30410 ist eine Einstellung, kein Sollwert - deshalb nur einmal schreiben
        plan.add(30410, 1, "AC-Charge (30410)",
                 "Laden aus dem Netz erlaubt", once=True)
        plan.add_reset(30410, ac_charge, "AC-Charge (30410)",
                       f"zurueck auf den vorgefundenen Wert {ac_charge}")
    plan.add_reset(30407, [0, 0, 0], "Remote enable / Dauer / Sollwert",
                   "Fernsteuerung aus, Wechselrichter regelt wieder selbst")
    return plan


def build_plan_legacy(watt: float, charging: bool, reference: float) -> Plan:
    percent = max(0, min(100, percent_of(watt, reference)))
    if charging:
        plan = Plan(f"Plan: Laden mit {watt:.0f} W ueber Battery-First "
                    f"({percent} % von {reference:.0f} W)")
        plan.add(1100, [SLOT_START, SLOT_STOP, 1], "Battery-First Slot 1",
                 "Zeitfenster 00:00-23:59 aktiv - Slot 1 ist damit fuer den Test belegt",
                 once=True)
        plan.add(1090, percent, "Battery-First Ladeleistungsrate",
                 f"{percent} % Ladeleistung", once=True)
        plan.add(1092, 1, "AC-Charge", "Laden aus dem Netz erlaubt", once=True)
        plan.add(1044, 1, "Prioritaet", "Battery first", once=True)
        plan.add_reset(1102, 0, "Battery-First Slot 1 aus", "Zeitfenster deaktiviert")
        plan.add_reset(1092, 0, "AC-Charge aus", "Kein Netzladen mehr")
        plan.add_reset(1044, 0, "Prioritaet", "Load first (Normalbetrieb)")
    else:
        plan = Plan(f"Plan: Entladen mit {watt:.0f} W ueber Grid-First "
                    f"({percent} % von {reference:.0f} W)")
        plan.add(1080, [SLOT_START, SLOT_STOP, 1], "Grid-First Slot 1",
                 "Zeitfenster 00:00-23:59 aktiv - Slot 1 ist damit fuer den Test belegt",
                 once=True)
        plan.add(1070, percent, "Grid-First Entladeleistungsrate",
                 f"{percent} % Entladeleistung", once=True)
        plan.add(1044, 2, "Prioritaet", "Grid first", once=True)
        plan.add_reset(1082, 0, "Grid-First Slot 1 aus", "Zeitfenster deaktiviert")
        plan.add_reset(1044, 0, "Prioritaet", "Load first (Normalbetrieb)")
    return plan


def check_charge_source(bus: Bus, allow_ac_charge: bool):
    """Hat der Wechselrichter ueberhaupt Energie zum Laden?

    Ein Ladebefehl bei PV = 0 und abgeschaltetem Netzladen ist physikalisch
    nicht erfuellbar. Das sieht im Protokoll aus wie eine ignorierte Vorgabe -
    obwohl die Fernsteuerung einwandfrei funktionieren kann. Deshalb vorher
    pruefen und, wenn noetig, mit --ac-charge das Netzladen freigeben.

    Rueckgabe: der vorgefundene Wert von 30410 (fuer die Rueckstellung),
    None wenn nichts zu tun ist, oder False als Abbruch.
    """
    pv = bus.read(INPUT, 1, 2)
    pv_watt = (scale(u32_hi(pv, 1), 0.1) or 0.0) if pv else 0.0
    ac = bus.read_one(HOLDING, 30410)

    if pv_watt > 100:
        return None
    if ac:
        print(f"\n  PV liefert {pv_watt:.0f} W, Netzladen ist bereits an (30410 = {ac}).")
        return None
    if allow_ac_charge:
        print(f"\n  PV liefert {pv_watt:.0f} W. Netzladen wird fuer diesen Test"
              f"\n  freigegeben (30410: {ac} -> 1) und danach zurueckgestellt.")
        return 0 if ac is None else ac

    print(f"\n  Abbruch: PV liefert {pv_watt:.0f} W und Netzladen ist aus "
          f"(30410 = {ac}).", file=sys.stderr)
    print("  Der Wechselrichter hat damit keine Energiequelle zum Laden - ein", file=sys.stderr)
    print("  Ladebefehl bliebe wirkungslos, ohne dass das etwas ueber die", file=sys.stderr)
    print("  Fernsteuerung aussagt.", file=sys.stderr)
    print("  Entweder bei Sonne testen, oder mit '--ac-charge' das Netzladen", file=sys.stderr)
    print("  fuer die Dauer des Tests freigeben, oder - am aussagekraeftigsten -", file=sys.stderr)
    print("  stattdessen 'hold' bzw. 'discharge' verwenden: dafuer braucht es", file=sys.stderr)
    print("  keine Energiequelle.", file=sys.stderr)
    return False


def cmd_power(bus: Bus, args, charging: bool) -> int:
    if not check_identity(bus, "Growatt SPH", identity, args.yes, args.force):
        return 2
    bank = args.bank
    if bank == "auto":
        dtc = bus.read_one(HOLDING, 30000)
        bank = "vpp" if dtc else "legacy"
        print(f"  Bank automatisch gewaehlt: {bank} (DTC 30000 = {dtc})")

    if bank == "vpp":
        reference = vpp_reference(bus, args.reference)
        if reference is None:
            print("  Bezugsleistung unbekannt: 30026 meldet 0 und --reference fehlt.",
                  file=sys.stderr)
            print("  Bitte '--reference <Watt>' angeben, z. B. die Nennleistung der Batterie.",
                  file=sys.stderr)
            return 2
        ac_charge = None
        if charging:
            ac_charge = check_charge_source(bus, args.ac_charge)
            if ac_charge is False:
                return 2
        plan = build_plan_vpp(args.watt, charging, reference,
                              int(args.vpp_minutes), ac_charge or None)
    else:
        reference = args.reference or 4600.0
        plan = build_plan_legacy(args.watt, charging, reference)
        print("\n  Hinweis: die Legacy-Register liegen im EEPROM. Sie werden deshalb"
              "\n  nur einmal beschrieben, nicht bei jedem Durchlauf.")

    return run_controlled(bus, plan, args.yes, args.duration, args.interval, observe)


def cmd_hold(bus: Bus, args) -> int:
    if not check_identity(bus, "Growatt SPH", identity, args.yes, args.force):
        return 2
    minutes = int(getattr(args, "vpp_minutes", 0))
    dauer = "unbegrenzt" if minutes == 0 else f"{minutes} min"
    plan = Plan("Plan: Batterie anhalten (Sollwert 0)")
    plan.add(30407, [1, minutes, 0], "Remote enable / Dauer / Sollwert",
             f"Fernsteuerung an, {dauer}, 0 % Leistung")
    plan.add_reset(30407, [0, 0, 0], "Remote enable / Dauer / Sollwert", "Fernsteuerung aus")
    return run_controlled(bus, plan, args.yes, args.duration, args.interval, observe)


def cmd_reset(bus: Bus, args) -> int:
    if not check_identity(bus, "Growatt SPH", identity, args.yes, args.force):
        return 2
    plan = Plan("Plan: alles auf Normalbetrieb zuruecksetzen")
    plan.add(30407, [0, 0, 0], "VPP Remote aus", "Fernsteuerung aus")
    plan.add(1102, 0, "Battery-First Slot 1 aus", "Zeitfenster deaktiviert")
    plan.add(1082, 0, "Grid-First Slot 1 aus", "Zeitfenster deaktiviert")
    plan.add(1092, 0, "AC-Charge aus", "Kein Netzladen")
    plan.add(1044, 0, "Prioritaet", "Load first")
    plan.show(bus)
    if not args.yes:
        print("\n  Trockenlauf: es wurde nichts geschrieben. Mit '--yes' ausfuehren.")
        return 0
    plan.apply(bus)
    heading("Danach")
    table(observe(bus))
    return 0


def cmd_selftest(_bus, _args) -> int:
    """Prueft die Dekoder ohne Anlage."""
    checks = [
        ("u32_hi", u32_hi({1: 0x0001, 2: 0x86A0}, 1), 100000),
        ("s16 negativ", s16(0xFFCE), -50),
        ("s16 positiv", s16(50), 50),
        ("s32_hi negativ", s32_hi({1: 0xFFFF, 2: 0xFFCE}, 1), -50),
        ("hhmm", hhmm(0x173B), "23:59"),
        ("ascii", ascii_str({1: 0x4142, 2: 0x4344}, 1, 2), "ABCD"),
        ("percent laden", percent_of(2300, 4600), 50),
        ("percent entladen", percent_of(-2300, 4600), -50),
        ("percent begrenzt", percent_of(99999, 4600), 100),
        ("percent ohne Bezug", percent_of(1000, 0), 0),
        ("scale", scale(46000, 0.1), 4600.0),
    ]
    failed = 0
    heading("Selbsttest der Dekoder")
    for name, got, want in checks:
        ok = got == want
        failed += 0 if ok else 1
        print(f"  {'OK  ' if ok else 'FEHL'} {name:<24} {got!r} (erwartet {want!r})")
    print(f"\n  {len(checks) - failed} von {len(checks)} bestanden.")
    return 1 if failed else 0


#: Register, die fuer die Steuerung in Frage kommen - nach Bank getrennt.
PROBE_LEGACY = [
    (1044, "Prioritaet (0 Load / 1 Battery / 2 Grid first)"),
    (1070, "Grid-First Entladeleistungsrate [%]"),
    (1071, "Grid-First Entladeschluss-SOC [%]"),
    (1080, "Grid-First Slot 1 Start"),
    (1081, "Grid-First Slot 1 Ende"),
    (1082, "Grid-First Slot 1 aktiv"),
    (1090, "Battery-First Ladeleistungsrate [%]"),
    (1091, "Battery-First Ladeschluss-SOC [%]"),
    (1092, "AC-Charge (Netzladen)"),
    (1100, "Battery-First Slot 1 Start"),
    (1101, "Battery-First Slot 1 Ende"),
    (1102, "Battery-First Slot 1 aktiv"),
]
PROBE_VPP = [
    (30407, "Remote enable"),
    (30408, "Remote Dauer [min]"),
    (30409, "Remote Sollwert [%]"),
    (30410, "AC-Charge"),
]
#: Register, die nur gelesen werden - sie entscheiden, ob die Fernsteuerung
#: ueberhaupt freigeschaltet ist.
GATEKEEPERS = [
    (30026, "BDC-Nennleistung (0 = VPP-Teil unbefuellt)"),
    (30099, "VPP-Protokollversion"),
    (30100, "Control authority"),
    (30203, "EMS-Watchdog Zeit [s]"),
    (30204, "EMS-Watchdog aktiv"),
    (30474, "Wirksamer Sollwert [%]"),
]


def cmd_writeprobe(bus: Bus, args) -> int:
    """Kartiert, welche Steuerregister das Geraet beschreiben laesst."""
    if not check_identity(bus, "Growatt SPH", identity, args.yes, args.force):
        return 2

    heading("Torwaechter-Register (nur gelesen)")
    print(f"  {'Adresse':>7}  {'Wert':>8}  Bedeutung")
    for address, name in GATEKEEPERS:
        value = bus.read_one(HOLDING, address)
        print(f"  {address:>7}  {'-' if value is None else value:>8}  {name}")

    if not args.yes:
        heading("Trockenlauf")
        print("  Mit '--yes' wird auf jede der folgenden Adressen der gerade")
        print("  gelesene Wert zurueckgeschrieben. Der Zustand der Anlage aendert")
        print("  sich dadurch nicht - es wird nur gemessen, ob das Geraet den")
        print("  Schreibzugriff annimmt.")
        for title, addresses in [("Legacy-Bank", PROBE_LEGACY), ("VPP-Bank", PROBE_VPP)]:
            print(f"    {title}: " + ", ".join(str(a) for a, _ in addresses))
        print("\n  Jeder Versuch auf einem EEPROM-Register kostet einen Schreibzyklus.")
        print("  Das ist eine Einzelfalldiagnose, nichts fuer den Dauerbetrieb.")
        return 0

    return write_probe(bus, [("Legacy-Bank (Storage-Protokoll)", PROBE_LEGACY),
                             ("VPP-Bank", PROBE_VPP)])


def add_vpp_args(ap: argparse.ArgumentParser) -> None:
    """Optionen, die nur die VPP-Bank betreffen.

    Sie haengen am Unterbefehl und nicht an der Hauptebene, damit
    'charge 500 --ac-charge' funktioniert - argparse verlangt Hauptoptionen
    sonst vor dem Unterbefehl, und so tippt das niemand.
    """
    g = ap.add_argument_group("VPP-Bank")
    g.add_argument("--vpp-minutes", type=int, default=0,
                   help="Dauer in Register 30408: 0 = unbegrenzt (Vorgabe). Falls die "
                        "Anlage 0 als 'sofort abgelaufen' auslegt, hier z. B. 10 setzen")
    g.add_argument("--ac-charge", action="store_true",
                   help="Netzladen (30410) fuer die Dauer des Tests freigeben - noetig, "
                        "um ohne PV laden zu koennen")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    add_connection_args(ap)
    ap.add_argument("--bank", choices=["auto", "legacy", "vpp"], default="auto",
                    help="Welche Registerbank fuer die Steuerung verwendet wird")
    ap.add_argument("--reference", type=float,
                    help="Bezugsleistung in W fuer die Prozentangabe (sonst aus 30026)")
    ap.add_argument("--verbose", action="store_true", help="Modbus-Fehler einzeln anzeigen")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("probe", help="Verbindung pruefen und feststellen, welche Baenke antworten")
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
        add_vpp_args(p)
    p_hold = sub.add_parser("hold", help="Batterie auf 0 W halten")
    add_write_args(p_hold)
    add_vpp_args(p_hold)
    add_write_args(sub.add_parser("reset", help="Alles auf Normalbetrieb zuruecksetzen"))
    add_write_args(sub.add_parser(
        "writeprobe", help="Kartieren, welche Steuerregister beschreibbar sind"))
    sub.add_parser("selftest", help="Dekoder ohne Anlage pruefen")

    add_site_args(ap)
    if apply_site_defaults(ap):
        return 0
    args = ap.parse_args()

    if args.cmd == "selftest":
        return cmd_selftest(None, args)

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
        if args.cmd == "writeprobe":
            return cmd_writeprobe(bus, args)
    except KeyboardInterrupt:
        print("\nAbgebrochen.")
        return 130
    finally:
        bus.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
