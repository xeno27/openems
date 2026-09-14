#!/usr/bin/env python3
"""SAJ CH2 - Register und Steuerung testen (C&I Hybrid, PCS der CHS2/CM2-Systeme).

Der CH2 hat ein echtes Remote-EMS-Interface. EMSEnable (0x8400) waehlt, welchem
Sollwert der Wechselrichter folgt:

    0  aus, der Wechselrichter faehrt seinen eigenen Modus
    1  Wechselrichter-Sollwert  EMSINVPRef (0x8402)  - AC-Seite, PV eingerechnet
    2  Batterie-Sollwert        EMSBATPRef (0x8405)  - nur die Batterie

Beide Sollwerte sind vorzeichenbehaftet in 0,01 % der Nennleistung, also
10000 = 100 %, Bereich -110 % bis +110 %. Positiv = entladen/einspeisen,
negativ = laden. Das ist dieselbe Vorzeichenkonvention wie in OpenEMS.

EMSKeepTime (0x8401) ist der Watchdog: hoert das EMS auf zu schreiben, faellt
der Wechselrichter nach dieser Zeit von selbst in seinen eigenen Modus zurueck.
Dieses Skript setzt ihn bewusst kurz, damit auch ein abgestuerztes Skript kein
Dauerproblem hinterlaesst.

Beispiele:
    uv run --with pymodbus saj_lab.py --host 192.168.1.70 probe
    uv run --with pymodbus saj_lab.py --host 192.168.1.70 read
    uv run --with pymodbus saj_lab.py --host 192.168.1.70 discharge 10000 --yes

Registeradressen aus 'Protocol for CH2 remote EMS control' (Guangzhou Sanjing).
Alle Zugriffe per FC03 lesen und FC16 schreiben.
"""
from __future__ import annotations

import argparse
import sys

from modbuslab import (
    HOLDING, INPUT, Bus, Plan, add_connection_args, add_site_args, add_write_args,
    apply_site_defaults, check_identity, connect, dump_registers, environment_info, heading,
    run_controlled, s16, s32_hi, scale, table, u32_hi,
)

PER_UNIT = 10_000       # 10000 = 100 %
MAX_PER_UNIT = 11_000   # der CH2 akzeptiert bis 110 %

REG_SELL_LIMIT = 0x8306
REG_BUY_LIMIT = 0x8307
REG_EMS_ENABLE = 0x8400
REG_KEEP_TIME = 0x8401
REG_INV_PREF = 0x8402
REG_BAT_PREF = 0x8405

MPV_MODE = {
    0: "Initialisierung", 1: "Warten", 2: "Netzbetrieb", 3: "Inselbetrieb (Speicher)",
    4: "Netzgekoppelt (Speicher)", 5: "Fault", 6: "Upgrade", 7: "Debug",
    8: "Selbsttest", 9: "Reset",
}
APP_MODE = {0: "Eigenverbrauch", 1: "Zeitsteuerung", 2: "Backup", 3: "Passiv"}
EMS_ENABLE = {0: "aus", 1: "Wechselrichter-Sollwert (0x8402)", 2: "Batterie-Sollwert (0x8405)"}
DIRECTION = {1: "1 (raus/entladen)", 0: "0 (kein Fluss)", -1: "-1 (rein/laden)"}


def to_per_unit(watt: float, rated: float) -> int:
    if rated <= 0:
        return 0
    value = round(watt * PER_UNIT / rated)
    return max(-MAX_PER_UNIT, min(MAX_PER_UNIT, value))


def read_all(bus: Bus) -> dict[str, object]:
    h: dict[int, int] = {}
    for start, count in [(0x7B04, 1), (0x7B28, 1), (0x7B2D, 6), (0x7B37, 27),
                         (0x7B81, 8), (0x7BEE, 6), (0x7BFC, 8), (0x7C22, 16),
                         (REG_SELL_LIMIT, 2), (REG_EMS_ENABLE, 6)]:
        blk = bus.read(HOLDING, start, count)
        if blk:
            h.update(blk)

    def phase(base: int, name: str) -> dict[str, object]:
        return {
            f"{name} Spannung [V]": scale(h.get(base), 0.1),
            f"{name} Strom [A]": scale(s16(h.get(base + 1)), 0.01, 2),
            f"{name} Frequenz [Hz]": scale(h.get(base + 2), 0.01, 2),
            f"{name} Wirkleistung [W]": s32_hi(h, base + 4),
        }

    out: dict[str, object] = {"_raw_holding": h}
    out["Betriebsart (0x7B04)"] = MPV_MODE.get(h.get(0x7B04), h.get(0x7B04))
    out["Anwendungsmodus (0x7B28)"] = APP_MODE.get(h.get(0x7B28), h.get(0x7B28))
    out["Batteriestatus (0x7B2D)"] = h.get(0x7B2D)
    out["SOC-Obergrenze [%] (0x7B2F)"] = s16(h.get(0x7B2F))
    out["SOC-Untergrenze [%] (0x7B30)"] = s16(h.get(0x7B30))
    for base, name in [(0x7B37, "R"), (0x7B40, "S"), (0x7B49, "T")]:
        out.update(phase(base, name))
    out["Batteriespannung [V] (0x7B81)"] = scale(h.get(0x7B81), 0.1)
    out["Batteriestrom [A] (0x7B82)"] = scale(s16(h.get(0x7B82)), 0.01, 2)
    out["Batterieleistung [W] (0x7B85)"] = s32_hi(h, 0x7B85)
    out["Batterietemperatur [C] (0x7B87)"] = scale(s16(h.get(0x7B87)), 0.1)
    out["SOC [%] (0x7B88)"] = scale(h.get(0x7B88), 0.01, 2)
    out["PV-Richtung (0x7BEE)"] = h.get(0x7BEE)
    out["Batterierichtung (0x7BEF)"] = DIRECTION.get(s16(h.get(0x7BEF)), s16(h.get(0x7BEF)))
    out["Netzrichtung (0x7BF0)"] = DIRECTION.get(s16(h.get(0x7BF0)), s16(h.get(0x7BF0)))
    out["Systemlast [W] (0x7BF2)"] = s32_hi(h, 0x7BF2)
    out["PV-Leistung gesamt [W] (0x7BFC)"] = s32_hi(h, 0x7BFC)
    out["Batterieleistung gesamt [W] (0x7BFE)"] = s32_hi(h, 0x7BFE)
    out["Netzleistung gesamt [W] (0x7C00)"] = s32_hi(h, 0x7C00)
    out["Ladung gesamt [kWh] (0x7C28)"] = scale(u32_hi(h, 0x7C28), 0.01, 2)
    out["Entladung gesamt [kWh] (0x7C30)"] = scale(u32_hi(h, 0x7C30), 0.01, 2)
    out["Export-Limit [%] (0x8306)"] = scale(s16(h.get(REG_SELL_LIMIT)), 0.01, 2)
    out["Import-Limit [%] (0x8307)"] = scale(s16(h.get(REG_BUY_LIMIT)), 0.01, 2)
    out["EMSEnable (0x8400)"] = EMS_ENABLE.get(h.get(REG_EMS_ENABLE), h.get(REG_EMS_ENABLE))
    out["EMSKeepTime [s] (0x8401)"] = h.get(REG_KEEP_TIME)
    out["Wechselrichter-Sollwert [%] (0x8402)"] = scale(s16(h.get(REG_INV_PREF)), 0.01, 2)
    out["Batterie-Sollwert [%] (0x8405)"] = scale(s16(h.get(REG_BAT_PREF)), 0.01, 2)
    return out


def observe(bus: Bus) -> list[tuple[str, object]]:
    d = read_all(bus)
    return [
        ("SOC [%]", d.get("SOC [%] (0x7B88)")),
        ("Batterie [W] (0x7BFE)", d.get("Batterieleistung gesamt [W] (0x7BFE)")),
        ("Batterierichtung", d.get("Batterierichtung (0x7BEF)")),
        ("Netz [W] (0x7C00)", d.get("Netzleistung gesamt [W] (0x7C00)")),
        ("Netzrichtung", d.get("Netzrichtung (0x7BF0)")),
        ("PV [W]", d.get("PV-Leistung gesamt [W] (0x7BFC)")),
        ("EMSEnable", d.get("EMSEnable (0x8400)")),
        ("Sollwert INV [%] (0x8402)", d.get("Wechselrichter-Sollwert [%] (0x8402)")),
        ("Sollwert BAT [%] (0x8405)", d.get("Batterie-Sollwert [%] (0x8405)")),
        ("Betriebsart", d.get("Betriebsart (0x7B04)")),
    ]


def identity(bus: Bus) -> str | None:
    """Kennung, die nur ein SAJ CH2/CM2 liefert.

    0x7B04 ist die Betriebsart, 0x8400 der EMS-Schalter. Beide zusammen gibt
    es bei den anderen beiden Herstellern an diesen Adressen nicht.
    """
    mode = bus.read_one(HOLDING, 0x7B04)
    ems = bus.read_one(HOLDING, REG_EMS_ENABLE)
    if mode is None or ems is None:
        return None
    return f"SAJ CH2/CM2, Betriebsart {MPV_MODE.get(mode, mode)}, EMSEnable {ems}"


def cmd_probe(bus: Bus, args) -> int:
    heading("Verbindung und Zustand")
    print(f"  {environment_info()}")
    d = read_all(bus)
    table([(k, d.get(k)) for k in [
        "Betriebsart (0x7B04)", "Anwendungsmodus (0x7B28)", "SOC [%] (0x7B88)",
        "Batterieleistung gesamt [W] (0x7BFE)", "Netzleistung gesamt [W] (0x7C00)",
        "PV-Leistung gesamt [W] (0x7BFC)", "EMSEnable (0x8400)", "EMSKeepTime [s] (0x8401)",
        "Wechselrichter-Sollwert [%] (0x8402)", "Batterie-Sollwert [%] (0x8405)",
        "Export-Limit [%] (0x8306)", "Import-Limit [%] (0x8307)",
    ]], width=40)

    heading("Bewertung")
    if d.get("Betriebsart (0x7B04)") is None:
        print("  Die Monitoring-Bank (0x7Bxx) antwortet nicht.")
        print("  Verbindung, Unit-ID und Baudrate pruefen.")
        return 1
    print("  Monitoring-Bank antwortet.")
    if d.get("EMSEnable (0x8400)") is None:
        print("  Die EMS-Bank (0x84xx) antwortet NICHT - Fernsteuerung nicht moeglich.")
    else:
        print("  EMS-Bank antwortet, Fernsteuerung ist erreichbar.")
        if not args.rated:
            print("\n  Fuer die Sollwertrechnung wird die Nennleistung gebraucht.")
            print("  Der CH2 meldet sie nicht ueber diese Register: bitte '--rated <Watt>'")
            print("  angeben, z. B. --rated 50000 fuer einen 50-kW-CH2.")
    return 0


def cmd_read(bus: Bus, args) -> int:
    heading("SAJ CH2")
    d = read_all(bus)
    table([(k, v) for k, v in d.items() if not k.startswith("_")], width=40)
    return 0


def build_plan(watt: float, charging: bool, args) -> Plan:
    # OpenEMS-Konvention: positiv = entladen. Der CH2 verwendet dieselbe.
    signed = -abs(watt) if charging else abs(watt)
    per_unit = to_per_unit(signed, args.rated)
    battery_mode = args.mode == "battery"
    reg = REG_BAT_PREF if battery_mode else REG_INV_PREF
    enable = 2 if battery_mode else 1
    what = "Laden" if charging else "Entladen"
    target = "Batterie-Sollwert (0x8405)" if battery_mode else "Wechselrichter-Sollwert (0x8402)"

    plan = Plan(f"Plan: {what} mit {abs(watt):.0f} W ueber {target} "
                f"({per_unit / 100:+.2f} % von {args.rated:.0f} W)")
    plan.add(REG_EMS_ENABLE, [enable, max(10, int(args.keep_time))],
             "EMSEnable / EMSKeepTime",
             f"{EMS_ENABLE[enable]}, Watchdog {max(10, int(args.keep_time))} s")
    plan.add(reg, per_unit & 0xFFFF, target,
             f"{per_unit / 100:+.2f} % ({'negativ = laden' if charging else 'positiv = entladen'})")
    plan.add_reset(reg, 0, target, "Sollwert 0")
    plan.add_reset(REG_EMS_ENABLE, 0, "EMSEnable", "Fernsteuerung aus, eigener Modus")
    return plan


def cmd_power(bus: Bus, args, charging: bool) -> int:
    if not check_identity(bus, "SAJ CH2/CM2", identity, args.yes, args.force):
        return 2
    if not args.rated:
        print("  Bitte '--rated <Watt>' angeben - die Sollwerte sind Prozent der Nennleistung.",
              file=sys.stderr)
        return 2
    plan = build_plan(args.watt, charging, args)
    return run_controlled(bus, plan, args.yes, args.duration, args.interval, observe)


def cmd_hold(bus: Bus, args) -> int:
    if not check_identity(bus, "SAJ CH2/CM2", identity, args.yes, args.force):
        return 2
    plan = Plan("Plan: Sollwert 0 (Batterie halten)")
    plan.add(REG_EMS_ENABLE, [1, max(10, int(args.keep_time))], "EMSEnable / EMSKeepTime",
             "Wechselrichter-Sollwert aktiv, Watchdog gesetzt")
    plan.add(REG_INV_PREF, 0, "Wechselrichter-Sollwert (0x8402)", "0 %")
    plan.add_reset(REG_INV_PREF, 0, "Wechselrichter-Sollwert (0x8402)", "0 %")
    plan.add_reset(REG_EMS_ENABLE, 0, "EMSEnable", "Fernsteuerung aus")
    return run_controlled(bus, plan, args.yes, args.duration, args.interval, observe)


def cmd_reset(bus: Bus, args) -> int:
    if not check_identity(bus, "SAJ CH2/CM2", identity, args.yes, args.force):
        return 2
    plan = Plan("Plan: Fernsteuerung beenden")
    plan.add(REG_INV_PREF, 0, "Wechselrichter-Sollwert (0x8402)", "0 %")
    plan.add(REG_BAT_PREF, 0, "Batterie-Sollwert (0x8405)", "0 %")
    plan.add(REG_EMS_ENABLE, 0, "EMSEnable", "Fernsteuerung aus, eigener Modus")
    plan.show(bus)
    if not args.yes:
        print("\n  Trockenlauf: es wurde nichts geschrieben. Mit '--yes' ausfuehren.")
        return 0
    plan.apply(bus)
    heading("Danach")
    table(observe(bus), width=40)
    return 0


def cmd_selftest(_bus, _args) -> int:
    checks = [
        ("50 % entladen", to_per_unit(25_000, 50_000), 5000),
        ("50 % laden", to_per_unit(-25_000, 50_000), -5000),
        ("auf 110 % begrenzt", to_per_unit(999_999, 50_000), 11000),
        ("auf -110 % begrenzt", to_per_unit(-999_999, 50_000), -11000),
        ("ohne Nennleistung", to_per_unit(25_000, 0), 0),
        ("u32_hi", u32_hi({1: 0x0001, 2: 0x86A0}, 1), 100000),
        ("s32_hi negativ", s32_hi({1: 0xFFFF, 2: 0xFFCE}, 1), -50),
        ("SOC 0,01 %", scale(5055, 0.01, 2), 50.55),
    ]
    heading("Selbsttest der Dekoder")
    failed = 0
    for name, got, want in checks:
        ok = got == want
        failed += 0 if ok else 1
        print(f"  {'OK  ' if ok else 'FEHL'} {name:<24} {got!r} (erwartet {want!r})")
    print(f"\n  {len(checks) - failed} von {len(checks)} bestanden.")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    add_connection_args(ap)
    ap.add_argument("--rated", type=float,
                    help="Nennleistung des Wechselrichters in W (Bezug fuer die Sollwerte)")
    ap.add_argument("--mode", choices=["inverter", "battery"], default="inverter",
                    help="inverter = 0x8402 (AC-Seite, PV eingerechnet), battery = 0x8405")
    ap.add_argument("--keep-time", type=float, default=30.0,
                    help="EMSKeepTime in s - Watchdog des Wechselrichters")
    ap.add_argument("--verbose", action="store_true", help="Modbus-Fehler einzeln anzeigen")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("probe", help="Verbindung und Zustand pruefen")
    sub.add_parser("read", help="Alle bekannten Werte dekodiert anzeigen")
    p_watch = sub.add_parser("watch", help="Werte zyklisch anzeigen")
    p_watch.add_argument("--interval", type=float, default=2.0)
    p_dump = sub.add_parser("dump", help="Rohregister anzeigen")
    p_dump.add_argument("kind", choices=[HOLDING, INPUT])
    p_dump.add_argument("start", type=lambda v: int(v, 0), help="dezimal oder 0x7B04")
    p_dump.add_argument("count", type=int)
    for name, helptext in [("charge", "Laden mit X Watt"), ("discharge", "Entladen mit X Watt")]:
        p = sub.add_parser(name, help=helptext)
        p.add_argument("watt", type=float)
        add_write_args(p)
    add_write_args(sub.add_parser("hold", help="Sollwert 0"))
    add_write_args(sub.add_parser("reset", help="Fernsteuerung beenden"))
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
    except KeyboardInterrupt:
        print("\nAbgebrochen.")
        return 130
    finally:
        bus.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
