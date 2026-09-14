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

## Voraussetzungen

Python ab 3.7 und pymodbus - **sowohl die alte 2.x-Reihe als auch 3.x**. Die
Skripte erkennen selbst, welche installiert ist, und stellen sich darauf ein.
`probe` gibt beide Versionen gleich in der ersten Zeile aus:

    Verbindung
    ----------
      Python 3.8.20, pymodbus 2.5.3

Das ist bei einer Fehlersuche aus der Ferne meist die erste Frage.

### Altes Image (Debian Buster, pymodbus 2.5.3)

Dort ist meist schon alles da:

    python3 -c "import pymodbus; print(pymodbus.__version__)"

Fehlt es, reicht auf Buster noch die direkte Installation:

    sudo pip3 install 'pymodbus==2.5.3' pyserial

Ein Upgrade auf pymodbus 3.x ist **nicht** noetig - und auf Python 3.7 auch
gar nicht moeglich, denn pymodbus 3.x setzt Python 3.8 voraus.

### Neues Image (Bookworm oder neuer)

Seit Bookworm laesst sich `pip` nicht mehr direkt in das System installieren.
Deshalb entweder das Systempaket nehmen

    sudo apt install python3-pymodbus

oder eine eigene Umgebung anlegen:

    python3 -m venv ~/labenv
    ~/labenv/bin/pip install pymodbus
    ~/labenv/bin/python growatt_lab.py --site growatt probe

### In beiden Faellen

Der Benutzer braucht Zugriff auf die serielle Schnittstelle:

    sudo usermod -aG dialout $USER     # danach neu anmelden

### Was sich zwischen den pymodbus-Reihen unterscheidet

Wer eigenen Code danebenstellt, sollte die drei Stolperstellen kennen - die
Skripte fangen sie in `modbuslab.py` ab:

| | pymodbus 2.x | pymodbus 3.0-3.6 | pymodbus ab 3.7 |
|---|---|---|---|
| Importpfad | `pymodbus.client.sync` | `pymodbus.client` | `pymodbus.client` |
| RTU-Framer | `method="rtu"` noetig | entfaellt | entfaellt |
| Geraeteadresse | `unit=` | `slave=` | `device_id=` |

Die erste Zeile ist die unangenehme: in pymodbus 2.x ist die Vorgabe
`method="ascii"`. Wer sie vergisst, bekommt von einem RTU-Geraet schlicht
keine Antwort - ohne aussagekraeftige Fehlermeldung.

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

`sites.ini` steht in `.gitignore` - die Datei enthaelt Ihre Adressen und wird
absichtlich nicht mit eingecheckt. Ohne sie bricht ein `--site`-Aufruf ab und
nennt den Kopierbefehl; ein Tippfehler im Profilnamen soll nicht stillschweigend
auf einer beliebigen Schnittstelle landen. `selftest` laeuft auch ohne Profil,
weil es gar keine Verbindung aufbaut.

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

    cp sites.ini.example sites.ini                     # einmalig, dann anpassen
    python3 growatt_lab.py --list-sites                # steht das Profil drin?

    python3 growatt_lab.py selftest                    # braucht kein Profil
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

### Womit man die Fernsteuerung wirklich prueft

Ein **Ladebefehl ist der schlechteste erste Test**. Laden braucht eine
Energiequelle: ohne PV und ohne freigegebenes Netzladen kann der
Wechselrichter den Befehl gar nicht ausfuehren, und das Ergebnis sieht
genauso aus wie eine ignorierte Vorgabe. Das Skript bricht deshalb ab, wenn
die PV unter 100 W liegt und 30410 auf 0 steht, und nennt die Alternativen.

Aussagekraeftig sind stattdessen:

    python3 growatt_lab.py --site growatt hold --yes --duration 60
    python3 growatt_lab.py --site growatt discharge 1000 --yes --duration 60

Beide brauchen keine Energiequelle. Entlaedt die Batterie gerade mit 2,4 kW
und bleibt sie bei `hold` unveraendert dabei, ist die Fernsteuerung wirkungslos
- das ist dann ein Befund und keine Vermutung mehr.

Zwei Schalter fuer die verbleibenden Unbekannten:

    --vpp-minutes 10    schreibt 10 statt 0 in 30408, falls die Firmware 0
                        nicht als "unbegrenzt", sondern als "sofort abgelaufen"
                        auslegt
    --ac-charge         gibt Netzladen (30410) fuer die Dauer des Tests frei
                        und stellt es danach auf den vorgefundenen Wert zurueck

## Wichtig vor dem ersten Schreibversuch

* **Nur ein Master auf dem RS485-Bus.** Einen parallel pollenden Shine-Stick
  oder ein zweites Gateway vorher abziehen, sonst CRC-Fehler und
  Antwortausfaelle. Das gilt fuer Growatt und SAJ gleichermassen.
* **Growatt Legacy-Register (1044, 1070-1102) liegen im EEPROM.** Sie werden
  deshalb nur **einmal** geschrieben, auch wenn der Test eine Minute laeuft;
  der Plan weist die betroffenen Adressen eigens aus. Zyklisch nachgeschrieben
  werden nur die VPP-Register 30407-30409, die laut Doku "Not storage" sind.
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

Alle drei Skripte wurden gegen einen Modbus-TCP-Simulator gefahren, und zwar
auf beiden Staenden - **Python 3.8 mit pymodbus 2.5.3** und Python 3.11 mit
pymodbus 3.15:

* Trockenlauf schreibt nichts,
* `--yes` schreibt den Plan,
* die Rueckstellung greift nach Ablauf der Laufzeit und bei Ctrl-C mitten im
  Lauf,
* die Geraetepruefung verhindert einen Schreibvorgang auf die falsche Anlage,
* die Dekoder-Selbsttests laufen auf beiden Staenden durch.

Der Quelltext kommt ohne Sprachmittel aus, die neuer als Python 3.7 sind.
