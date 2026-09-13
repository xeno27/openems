# Inverter-Lab - Register und Steuerung ohne OpenEMS testen

Drei kleine CLIs, mit denen sich Modbus-Verbindung, Registerlage und die
Steuerbefehle (Laden / Entladen / Halten) direkt vom PC oder vom Revolution Pi
aus pruefen lassen - bevor OpenEMS ins Spiel kommt.

    modbuslab.py        gemeinsame Basis (Verbindung, Dekoder, Plan, Not-Aus)
    growatt_lab.py      Growatt SPH  - Legacy-Storage-Bank und VPP-Bank
    sungrow_lab.py      Sungrow SH20T
    saj_lab.py          SAJ CH2 / CM2
    sites.ini.example   Vorlage fuer die Anlagenprofile

## Fuenf Grundregeln, nach denen die Skripte gebaut sind

1. **Eine gemeinsame Basis.** Die Sicherheitslogik (Rueckstellung, Abbruch,
   Trockenlauf, Geraetepruefung) liegt genau einmal in `modbuslab.py`.
2. **Schreibbefehle laufen im Vordergrund und stellen beim Beenden zurueck** -
   bei Ctrl-C, nach Ablauf von `--duration` und auch bei einem Fehler.
3. **Ohne `--yes` wird nichts geschrieben.** Ohne die Option kommt nur der
   Plan: Register, Istwert, Sollwert, erwartete Wirkung, Rueckstellwert.
4. **Vor jedem Schreibvorgang wird das Geraet identifiziert.** Antwortet an
   dem Anschluss kein Geraet der erwarteten Marke, bricht das Skript ab,
   statt fremde Registeradressen zu beschreiben.
5. **`selftest` laeuft ohne Anlage** und prueft die Dekoder (Wortreihenfolge,
   Vorzeichen, Skalierung).

## Installation auf dem Revolution Pi

RevPi laeuft auf Debian; seit Bookworm laesst sich `pip` nicht mehr direkt in
das System installieren. Deshalb entweder das Systempaket nehmen

    sudo apt install python3-pymodbus

oder eine eigene Umgebung anlegen:

    python3 -m venv ~/labenv
    ~/labenv/bin/pip install pymodbus
    ~/labenv/bin/python growatt_lab.py --site growatt probe

Der Benutzer braucht Zugriff auf die serielle Schnittstelle:

    sudo usermod -aG dialout $USER     # danach neu anmelden

## Welche Schnittstelle?

Die eingebaute RS485-Schnittstelle des RevPi Connect heisst `/dev/ttyRS485`.
Haengt stattdessen ein USB-Adapter dran, ist es `/dev/ttyUSB0` oder aehnlich.
Was tatsaechlich da ist, zeigt

    ls -l /dev/ttyRS485* /dev/ttyUSB* /dev/serial/by-id/ 2>/dev/null

Die Skripte pruefen den angegebenen Pfad und nennen die vorhandenen
Schnittstellen, wenn er nicht existiert - das erspart das Raten.

## Anlagenprofile statt langer Kommandozeilen

Drei Anlagen an zwei Schnittstellen tippt niemand gern jedes Mal aus. Deshalb
`sites.ini.example` nach `sites.ini` kopieren und anpassen:

    [growatt]
    serial    = /dev/ttyRS485
    baud      = 9600
    unit      = 1
    reference = 4600        ; 30026 meldet 0, deshalb fest vorgeben

    [saj]
    serial    = /dev/ttyRS485
    baud      = 9600
    unit      = 1
    rated     = 8000        ; Pflicht fuer Schreibbefehle

    [sungrow]
    host      = 192.168.1.60
    port      = 502
    unit      = 1

Danach genuegt `--site growatt`. Das Profil liefert nur Vorgabewerte - eine
Option auf der Kommandozeile gewinnt immer. Die Datei wird gesucht unter
`$INVERTER_LAB_SITES`, dann `~/.config/inverter-lab/sites.ini`, dann neben den
Skripten. `--list-sites` zeigt, was gefunden wurde.

## Bedienung (bei allen drei gleich)

    probe                    Was antwortet? Bewertung im Klartext
    read                     alles dekodiert
    watch                    zyklisch
    dump holding 30000 20    Rohregister in Hex
    charge 1000              Trockenlauf - zeigt nur den Plan
    charge 1000 --yes        schreibt wirklich
    discharge 2300 --yes
    hold --yes               Batterie auf 0 W
    reset --yes              alles zurueck auf Normalbetrieb
    selftest                 Dekoder pruefen, ganz ohne Anlage

Zu den Schreibbefehlen gehoeren `--duration` (Laufzeit in Sekunden, danach
automatische Rueckstellung, Vorgabe 60), `--interval` (Anzeigetakt) und
`--force` (Geraetepruefung uebergehen).

## Empfohlene Reihenfolge fuer die erste Anlage

    python3 growatt_lab.py --site growatt selftest     # ohne Anlage
    python3 growatt_lab.py --site growatt probe        # was antwortet?
    python3 growatt_lab.py --site growatt read         # alles dekodiert
    python3 growatt_lab.py --site growatt watch        # zyklisch, Ctrl-C beendet
    python3 growatt_lab.py --site growatt charge 500                  # Plan lesen
    python3 growatt_lab.py --site growatt charge 500 --yes --duration 30
    python3 growatt_lab.py --site growatt reset --yes                 # falls noetig

Beim vorletzten Schritt auf die Zeilen `VPP Sollwert [%] (30409)` und
`VPP wirksam [%] (30474)` achten: bleibt 30474 bei 0, waehrend 30409 den
Sollwert zeigt, nimmt der Wechselrichter die Vorgabe zwar an, setzt sie aber
nicht um. Genau das ist bei dieser Anlage noch offen.

## Wichtig vor dem ersten Schreibversuch

* **Nur ein Master auf dem RS485-Bus.** Einen parallel pollenden Shine-Stick
  oder ein zweites Gateway vorher abziehen, sonst CRC-Fehler und
  Antwortausfaelle. Das gilt fuer Growatt und SAJ gleichermassen.
* **Growatt Legacy-Register (1044, 1070-1102) liegen im EEPROM.** Der
  Legacy-Pfad ist fuer kurze Tests gedacht, nicht fuer Dauerbetrieb. Die
  VPP-Register 30407-30409 sind laut Doku "Not storage" und damit zyklisch
  beschreibbar.
* **Growatt VPP braucht eine Bezugsleistung.** Meldet 30026 eine 0, muss sie
  ueber `reference` im Profil oder `--reference 4600` kommen. Ohne sie bricht
  das Skript ab, statt eine falsche Prozentzahl zu schreiben.
* **SAJ braucht `rated`**, weil die Sollwerte dort in 0,01 % der Nennleistung
  angegeben werden. `--mode inverter` schreibt 0x8402 (AC-Seite, PV
  eingerechnet), `--mode battery` schreibt 0x8405 (reiner Batteriesollwert).
* **Sungrow 33046/33047** werden auf den Nennwert gesetzt und beim Beenden
  wieder darauf zurueckgestellt. Der WiNet-Dongle laesst nur eine
  TCP-Verbindung gleichzeitig zu - andere Clients vorher trennen.

## Geprueft

Alle drei Skripte wurden gegen einen Modbus-TCP-Simulator gefahren:
Trockenlauf schreibt nichts, `--yes` schreibt den Plan, die Rueckstellung
greift sowohl nach Ablauf der Laufzeit als auch bei Ctrl-C mitten im Lauf, und
die Geraetepruefung verhindert einen Schreibvorgang auf die falsche Anlage.
