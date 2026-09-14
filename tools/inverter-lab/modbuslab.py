#!/usr/bin/env python3
"""Gemeinsame Bausteine für die Wechselrichter-Testskripte.

Enthaelt Verbindungsaufbau, Register-Dekoder und - am wichtigsten - die
Sicherheitsmechanik fuer schreibende Zugriffe:

* jeder Schreibvorgang wird vorher als Plan angezeigt (Register, Ist, Soll,
  Wirkung, Ruecksetzwert),
* ohne ``--yes`` wird nichts geschrieben,
* nach dem Schreiben laeuft das Skript im Vordergrund und setzt beim Beenden
  automatisch zurueck - auch bei Ctrl-C oder einem Fehler.

Dieses Modul wird nicht direkt aufgerufen, sondern von growatt_lab.py,
saj_lab.py und sungrow_lab.py importiert. Alle Dateien muessen im selben
Verzeichnis liegen.
"""
from __future__ import annotations

import argparse
import configparser
import glob
import inspect
import os
import signal
import sys
import time
from dataclasses import dataclass, field
from typing import Callable, Iterable, Sequence

HOLDING = "holding"
INPUT = "input"


# --------------------------------------------------------------------------
# pymodbus 2.x und 3.x
# --------------------------------------------------------------------------
#
# Auf aelteren Images (z. B. Revolution Pi mit Debian Buster) steckt pymodbus
# 2.5.3, auf aktuellen 3.x. Die beiden unterscheiden sich an drei Stellen:
#
#   Importpfad     2.x: pymodbus.client.sync     3.x: pymodbus.client
#   RTU-Framer     2.x: method="rtu" noetig      3.x: Parameter entfaellt
#   Geraeteadresse 2.x: unit=   3.0-3.6: slave=   ab 3.7: device_id=
#
# Alles andere, was hier benutzt wird (connect, read_*_registers, .registers,
# write_registers, isError), verhaelt sich in beiden Reihen gleich.

try:
    from pymodbus.client import ModbusSerialClient, ModbusTcpClient
except ImportError:  # pymodbus 2.x
    from pymodbus.client.sync import ModbusSerialClient, ModbusTcpClient

try:
    from pymodbus import __version__ as PYMODBUS_VERSION
except ImportError:
    PYMODBUS_VERSION = "unbekannt"

PYMODBUS_MAJOR = int(PYMODBUS_VERSION.split(".")[0]) if PYMODBUS_VERSION[:1].isdigit() else 3


# --------------------------------------------------------------------------
# Anlagenprofile (sites.ini)
# --------------------------------------------------------------------------

SITES_ENV = "INVERTER_LAB_SITES"

#: Optionen, die ein Profil vorbelegen darf. Alles andere in der ini wird
#: ignoriert - ein Tippfehler soll nicht stillschweigend zu einem Attribut
#: werden, das niemand liest.
SITE_KEYS = {
    "host": str, "port": int, "serial": str, "baud": int, "unit": int,
    "timeout": float, "bank": str, "reference": float, "rated": float,
    "mode": str, "keep_time": float,
}


def sites_path() -> str:
    """Wo die Profildatei liegt: Umgebungsvariable, Home, sonst neben dem Skript."""
    env = os.environ.get(SITES_ENV)
    if env:
        return env
    home = os.path.expanduser("~/.config/inverter-lab/sites.ini")
    if os.path.exists(home):
        return home
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "sites.ini")


def load_sites() -> configparser.ConfigParser:
    cfg = configparser.ConfigParser()
    path = sites_path()
    if os.path.exists(path):
        cfg.read(path, encoding="utf-8")
    return cfg


def add_site_args(ap: argparse.ArgumentParser) -> None:
    g = ap.add_argument_group("Anlagenprofil")
    g.add_argument("--site", help="Abschnitt aus sites.ini, der die Verbindung vorbelegt")
    g.add_argument("--list-sites", action="store_true", help="Bekannte Profile anzeigen")


def apply_site_defaults(ap: argparse.ArgumentParser, argv: list[str] | None = None) -> bool:
    """'--site' vorab auswerten und als argparse-Defaults setzen.

    Dadurch gewinnt eine explizit angegebene Option immer gegen das Profil:
    das Profil liefert nur den Default, nicht den Wert.

    Gibt True zurueck, wenn stattdessen nur die Profilliste gefragt war - das
    muss vor dem eigentlichen Parsen passieren, weil dabei sonst ein
    Unterbefehl verlangt wuerde.
    """
    argv = sys.argv[1:] if argv is None else argv
    if "--list-sites" in argv:
        list_sites()
        return True
    name = None
    for i, item in enumerate(argv):
        if item == "--site" and i + 1 < len(argv):
            name = argv[i + 1]
        elif item.startswith("--site="):
            name = item.split("=", 1)[1]
    if not name:
        return False
    cfg = load_sites()
    if not cfg.has_section(name):
        known = ", ".join(cfg.sections()) or "keine"
        print(f"Profil '{name}' steht nicht in {sites_path()} (bekannt: {known}).",
              file=sys.stderr)
        raise SystemExit(2)
    defaults = {}
    for key, value in cfg.items(name):
        if key in SITE_KEYS:
            defaults[key] = SITE_KEYS[key](value)
    ap.set_defaults(**defaults)
    return False


def list_sites() -> int:
    path = sites_path()
    cfg = load_sites()
    heading(f"Anlagenprofile aus {path}")
    if not cfg.sections():
        print("  Keine Profile hinterlegt. Beispiel siehe sites.ini.example.")
        return 0
    for name in cfg.sections():
        items = ", ".join(f"{k}={v}" for k, v in cfg.items(name) if k in SITE_KEYS)
        print(f"  {name:<12} {items}")
    return 0


# --------------------------------------------------------------------------
# Verbindung
# --------------------------------------------------------------------------

def add_connection_args(ap: argparse.ArgumentParser) -> None:
    g = ap.add_argument_group("Verbindung")
    g.add_argument("--host", help="Modbus-TCP-Host, z. B. 192.168.1.60")
    g.add_argument("--port", type=int, default=502)
    g.add_argument("--serial", help="Modbus-RTU-Port, z. B. /dev/ttyUSB0 oder COM3")
    g.add_argument("--baud", type=int, default=9600)
    g.add_argument("--unit", type=int, default=1, help="Modbus-Adresse des Geraets")
    g.add_argument("--timeout", type=float, default=3.0)


def environment_info() -> str:
    """Welche Python- und pymodbus-Version laeuft hier gerade?

    Gehoert in jede probe-Ausgabe: bei einer Fehlersuche aus der Ferne ist das
    meist die erste Frage.
    """
    py = ".".join(str(n) for n in sys.version_info[:3])
    return f"Python {py}, pymodbus {PYMODBUS_VERSION}"


def serial_candidates() -> list[str]:
    """Serielle Schnittstellen, die auf diesem Rechner existieren.

    Auf einem Revolution Pi Connect heisst die eingebaute RS485-Schnittstelle
    /dev/ttyRS485; bei einem USB-Adapter ist es /dev/ttyUSB0 oder aehnlich.
    """
    found = []
    for pattern in ("/dev/ttyRS485*", "/dev/ttyUSB*", "/dev/ttyAMA*", "/dev/ttyS[0-9]"):
        found.extend(sorted(glob.glob(pattern)))
    return found


def connect(args) -> "Bus":
    if not args.host and not args.serial:
        print("Bitte --host oder --serial angeben (oder --site verwenden).", file=sys.stderr)
        ports = serial_candidates()
        if ports:
            print("  Gefundene serielle Schnittstellen: " + ", ".join(ports), file=sys.stderr)
        raise SystemExit(2)
    if args.serial and not os.path.exists(args.serial):
        print(f"Die Schnittstelle {args.serial} gibt es auf diesem Rechner nicht.",
              file=sys.stderr)
        ports = serial_candidates()
        if ports:
            print("  Vorhanden sind: " + ", ".join(ports), file=sys.stderr)
        raise SystemExit(2)
    client = (
        ModbusTcpClient(args.host, port=args.port, timeout=args.timeout)
        if args.host
        else _serial_client(args.serial, args.baud, args.timeout)
    )
    if not client.connect():
        where = args.host or args.serial
        print(f"Verbindung zu {where} fehlgeschlagen.", file=sys.stderr)
        raise SystemExit(2)
    return Bus(client, args.unit)


def _unit_kw(func) -> str:
    """Wie heisst der Parameter fuer die Modbus-Adresse in dieser pymodbus-Reihe?

    3.7+ nennt ihn 'device_id', 3.0-3.6 'slave', 2.x 'unit'. In 2.x steht er
    nicht in der Signatur, sondern wird ueber **kwargs durchgereicht - deshalb
    der Rueckfall auf die Hauptversion.
    """
    params = inspect.signature(func).parameters
    for name in ("device_id", "slave", "unit"):
        if name in params:
            return name
    return "unit" if PYMODBUS_MAJOR < 3 else "slave"


def _serial_client(port: str, baudrate: int, timeout: float):
    """Seriellen Client bauen - in pymodbus 2.x ist method='rtu' Pflicht.

    Die Vorgabe dort ist 'ascii'; ohne diesen Parameter antwortet kein
    RTU-Geraet, ohne dass ein aussagekraeftiger Fehler kommt.
    """
    kwargs = {"port": port, "baudrate": baudrate, "timeout": timeout}
    if "method" in inspect.signature(ModbusSerialClient.__init__).parameters:
        kwargs["method"] = "rtu"
    return ModbusSerialClient(**kwargs)


class ModbusError(Exception):
    pass


@dataclass
class Bus:
    client: object
    unit: int
    verbose: bool = False

    def read(self, kind: str, start: int, count: int) -> dict[int, int] | None:
        """Liest einen Block. Gibt None zurueck, wenn das Geraet ablehnt."""
        func = self.client.read_input_registers if kind == INPUT else self.client.read_holding_registers
        try:
            rr = func(start, count=count, **{_unit_kw(func): self.unit})
        except Exception as exc:
            if self.verbose:
                print(f"  [{kind} {start}+{count}] Fehler: {exc}", file=sys.stderr)
            return None
        if rr.isError():
            if self.verbose:
                print(f"  [{kind} {start}+{count}] Modbus-Fehler: {rr}", file=sys.stderr)
            return None
        return {start + i: v for i, v in enumerate(rr.registers)}

    def read_one(self, kind: str, address: int) -> int | None:
        blk = self.read(kind, address, 1)
        return None if blk is None else blk[address]

    def write(self, start: int, values: Sequence[int]) -> None:
        """Schreibt per FC16. Wirft ModbusError, wenn das Geraet ablehnt."""
        func = self.client.write_registers
        raw = [v & 0xFFFF for v in values]
        try:
            rr = func(start, raw, **{_unit_kw(func): self.unit})
        except Exception as exc:
            raise ModbusError(f"Schreiben auf {start} fehlgeschlagen: {exc}") from exc
        if rr.isError():
            raise ModbusError(f"Schreiben auf {start} abgelehnt: {rr}")

    def close(self) -> None:
        try:
            self.client.close()
        except Exception:
            pass


# --------------------------------------------------------------------------
# Dekoder
# --------------------------------------------------------------------------

def s16(v: int | None) -> int | None:
    if v is None:
        return None
    return v - 0x10000 if v & 0x8000 else v


def u32_hi(r: dict[int, int], a: int) -> int | None:
    """32 Bit, High-Word zuerst (Growatt, SAJ)."""
    if a not in r or a + 1 not in r:
        return None
    return (r[a] << 16) | r[a + 1]


def s32_hi(r: dict[int, int], a: int) -> int | None:
    v = u32_hi(r, a)
    if v is None:
        return None
    return v - 0x1_0000_0000 if v & 0x8000_0000 else v


def u32_lo(r: dict[int, int], a: int) -> int | None:
    """32 Bit, Low-Word zuerst (Sungrow)."""
    if a not in r or a + 1 not in r:
        return None
    return (r[a + 1] << 16) | r[a]


def s32_lo(r: dict[int, int], a: int) -> int | None:
    v = u32_lo(r, a)
    if v is None:
        return None
    return v - 0x1_0000_0000 if v & 0x8000_0000 else v


def ascii_str(r: dict[int, int], a: int, count: int) -> str | None:
    """ASCII aus aufeinanderfolgenden Registern, High-Byte zuerst."""
    out = bytearray()
    for i in range(count):
        v = r.get(a + i)
        if v is None:
            return None
        out.append((v >> 8) & 0xFF)
        out.append(v & 0xFF)
    text = out.decode("ascii", errors="replace").strip("\x00� ").strip()
    return text or None


def scale(value: int | None, factor: float, digits: int = 1) -> float | None:
    if value is None:
        return None
    return round(value * factor, digits)


def hhmm(v: int | None) -> str | None:
    if v is None:
        return None
    return f"{v >> 8:02d}:{v & 0xFF:02d}"


def looks_like_text(value: str | None) -> bool:
    """Grobe Pruefung, ob ein dekodierter String echt aussieht."""
    if not value:
        return False
    printable = sum(1 for c in value if 32 <= ord(c) < 127)
    return printable >= max(3, len(value) // 2)


# --------------------------------------------------------------------------
# Ausgabe
# --------------------------------------------------------------------------

def table(rows: Iterable[tuple[str, object]], width: int = 42) -> None:
    for name, value in rows:
        shown = "-" if value is None else value
        print(f"  {name:<{width}} {shown}")


def heading(text: str) -> None:
    print()
    print(text)
    print("-" * len(text))


def dump_registers(bus: Bus, kind: str, start: int, count: int) -> int:
    """Rohregister anzeigen - die ehrlichste Diagnose bei unklarer Doku."""
    heading(f"{kind} {start} .. {start + count - 1}")
    blk = bus.read(kind, start, count)
    if blk is None:
        print("  Block wurde abgelehnt oder nicht beantwortet.")
        return 1
    print(f"  {'Adr':>7} {'hex':>6} {'u16':>7} {'s16':>8}  ascii")
    for addr in sorted(blk):
        v = blk[addr]
        chars = "".join(chr(c) if 32 <= c < 127 else "." for c in ((v >> 8) & 0xFF, v & 0xFF))
        print(f"  {addr:>7}   {v:04X} {v:>7} {s16(v):>8}  {chars}")
    nonzero = sum(1 for v in blk.values() if v)
    print(f"\n  {nonzero} von {len(blk)} Registern sind ungleich 0.")
    return 0


# --------------------------------------------------------------------------
# Schreibende Zugriffe
# --------------------------------------------------------------------------

@dataclass
class Step:
    """Ein geplanter Schreibvorgang."""
    address: int
    values: list[int]
    name: str
    effect: str

    def describe(self, current: list[int | None]) -> str:
        ist = ", ".join("?" if c is None else str(c) for c in current)
        soll = ", ".join(str(v) for v in self.values)
        span = str(self.address) if len(self.values) == 1 else f"{self.address}..{self.address + len(self.values) - 1}"
        return f"  {span:<14} {self.name:<34} Ist [{ist}] -> Soll [{soll}]\n      {self.effect}"


@dataclass
class Plan:
    """Eine Folge von Schreibvorgaengen plus zugehoerigem Ruecksetzplan."""
    title: str
    steps: list[Step] = field(default_factory=list)
    reset_steps: list[Step] = field(default_factory=list)

    def add(self, address: int, values: int | Sequence[int], name: str, effect: str) -> None:
        vals = [values] if isinstance(values, int) else list(values)
        self.steps.append(Step(address, vals, name, effect))

    def add_reset(self, address: int, values: int | Sequence[int], name: str, effect: str) -> None:
        vals = [values] if isinstance(values, int) else list(values)
        self.reset_steps.append(Step(address, vals, name, effect))

    def show(self, bus: Bus) -> None:
        heading(self.title)
        for step in self.steps:
            blk = bus.read(HOLDING, step.address, len(step.values))
            current = [None] * len(step.values) if blk is None else [blk[step.address + i] for i in range(len(step.values))]
            print(step.describe(current))
        if self.reset_steps:
            print("\n  Rueckstellung beim Beenden:")
            for step in self.reset_steps:
                soll = ", ".join(str(v) for v in step.values)
                print(f"    {step.address:<12} {step.name:<34} -> [{soll}]")

    def apply(self, bus: Bus, steps: list[Step] | None = None) -> None:
        for step in (self.steps if steps is None else steps):
            bus.write(step.address, step.values)

    def apply_reset(self, bus: Bus) -> None:
        errors = []
        for step in self.reset_steps:
            try:
                bus.write(step.address, step.values)
            except ModbusError as exc:
                errors.append(str(exc))
        if errors:
            print("\n  ACHTUNG: Rueckstellung unvollstaendig:", file=sys.stderr)
            for e in errors:
                print(f"    {e}", file=sys.stderr)
            print("  Bitte den Wechselrichter pruefen.", file=sys.stderr)


def run_controlled(
    bus: Bus,
    plan: Plan,
    confirmed: bool,
    duration: float,
    interval: float,
    observe: Callable[[Bus], list[tuple[str, object]]],
) -> int:
    """Plan anzeigen, auf Wunsch schreiben, Wirkung beobachten, sicher zuruecksetzen.

    Die Rueckstellung laeuft auch bei Ctrl-C oder einem Fehler - ein erzwungener
    Lade- oder Entladevorgang kann nicht versehentlich stehen bleiben.
    """
    plan.show(bus)

    if not confirmed:
        print("\n  Trockenlauf: es wurde nichts geschrieben.")
        print("  Zum tatsaechlichen Ausfuehren '--yes' anhaengen.")
        return 0

    stopping = {"now": False}

    def on_signal(_sig, _frm):
        stopping["now"] = True

    previous = {}
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            previous[sig] = signal.signal(sig, on_signal)
        except (ValueError, OSError):
            pass

    print(f"\n  Schreibe jetzt. Laufzeit {duration:.0f} s, Abbruch mit Ctrl-C.")
    started = time.monotonic()
    try:
        plan.apply(bus)
        heading("Wirkung")
        while not stopping["now"] and time.monotonic() - started < duration:
            elapsed = time.monotonic() - started
            print(f"  t+{elapsed:5.1f}s")
            table(observe(bus), width=38)
            print()
            # zyklisch nachschreiben: fluechtige Sollwerte halten, Watchdog fuettern
            plan.apply(bus)
            slept = 0.0
            while slept < interval and not stopping["now"]:
                time.sleep(min(0.2, interval - slept))
                slept += 0.2
    except ModbusError as exc:
        print(f"\n  Fehler: {exc}", file=sys.stderr)
        return 1
    finally:
        for sig, handler in previous.items():
            try:
                signal.signal(sig, handler)
            except (ValueError, OSError):
                pass
        heading("Rueckstellung")
        plan.apply_reset(bus)
        table(observe(bus), width=38)
        print("\n  Normalbetrieb wiederhergestellt.")
    return 0


def add_write_args(ap: argparse.ArgumentParser) -> None:
    g = ap.add_argument_group("Schreibzugriff")
    g.add_argument("--yes", action="store_true",
                   help="Schreibvorgang wirklich ausfuehren (ohne diese Option nur Trockenlauf)")
    g.add_argument("--duration", type=float, default=60.0,
                   help="Laufzeit in Sekunden, danach automatische Rueckstellung")
    g.add_argument("--interval", type=float, default=3.0, help="Anzeigeintervall in Sekunden")
    g.add_argument("--force", action="store_true",
                   help="Geraetepruefung uebergehen (nur wenn die Kennung bekannt falsch ist)")


def percent_of(watt: float, reference: float) -> int:
    """Watt in Prozent einer Bezugsleistung, begrenzt auf -100..100."""
    if reference <= 0:
        return 0
    return max(-100, min(100, round(watt * 100.0 / reference)))


# --------------------------------------------------------------------------
# Geraetepruefung
# --------------------------------------------------------------------------

def check_identity(bus: Bus, expected: str, probe: Callable[[Bus], str | None],
                   confirmed: bool, force: bool) -> bool:
    """Vor dem Schreiben pruefen, ob wirklich das erwartete Geraet antwortet.

    Drei Anlagen an zwei Schnittstellen - ein vertauschter Port waere sonst
    ein Schreibvorgang mit fremden Registeradressen auf fremder Hardware.
    Beim Trockenlauf wird nur gewarnt, ein Schreibvorgang wird abgebrochen.
    """
    found = probe(bus)
    if found:
        print(f"\n  Geraet erkannt: {found}")
        return True
    print(f"\n  ACHTUNG: An diesem Anschluss antwortet nichts, das wie ein "
          f"{expected} aussieht.", file=sys.stderr)
    print("  Moegliche Ursachen: falscher Port, falsche Modbus-Adresse (--unit),"
          "\n  falsche Baudrate, A/B vertauscht, oder ein zweiter Master am Bus.",
          file=sys.stderr)
    if not confirmed:
        print("  (Trockenlauf - es wird ohnehin nichts geschrieben.)", file=sys.stderr)
        return True
    if force:
        print("  --force ist gesetzt, es wird trotzdem geschrieben.", file=sys.stderr)
        return True
    print("  Es wird nichts geschrieben. Mit 'probe' pruefen, oder --force setzen.",
          file=sys.stderr)
    return False
