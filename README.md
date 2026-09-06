# BlockProtect

BlockProtect ist ein modularer Griefing-Audit für Paper 26.2. Es speichert Ereignisse asynchron in SQLite und hält die Laufzeitkonfiguration als unveränderlichen Snapshot, sodass Einstellungen während des laufenden Betriebs wirksam werden.

## Build

Voraussetzung: Java 25 und Gradle 9+.

```powershell
.\gradlew.bat clean build
```

Das fertige Plugin liegt danach in `build/libs/BlockProtect-0.1.0.jar`. Für den vorbereiteten lokalen Server wird es nach `test-server/plugins/BlockProtect.jar` kopiert.

## Testserver

`test-server/paper.jar` ist Paper 26.2 Build 121. Der Server benötigt Java 25. Vor dem ersten Start muss die Minecraft-EULA gelesen und `test-server/eula.txt` manuell auf `eula=true` gesetzt werden. Danach startet:

```powershell
.\test-server\start-test-server.ps1
```

Der Testserver ist absichtlich nur für lokale Tests konfiguriert (`online-mode=false`, `spawn-protection=0`). Er darf nicht öffentlich erreichbar gemacht werden.

## Ingame-Steuerung

- `/bp inspect` aktiviert die Blockinspektion per Klick.
- `/bp lookup [radius] [limit] [spieler] [aktion]` sucht Audit-Einträge am Spielerstandort.
- `/bp status` zeigt Tracking, Module und Queue.
- `/bp module set <name> <on|off>` schaltet einzelne Module sofort um.
- `/bp config get <path>`, `/bp config set <path> <wert>` und `/bp config list` ändern jede Einstellung live und speichern sie sofort.
- `/bp flush` leert die Schreibqueue.
- `/bp purge <tage> confirm` löscht alte Einträge dauerhaft.

## Erfasste Bereiche

Blöcke (abbauen, platzieren, Explosionen, Feuer, Wachstum, Flüssigkeitsfluss, Pistons und Drops), Container und Inventare (öffnen, Klicks, Drag, Hopperbewegungen, Entnahmen, Crafting, Brauen und Dispense), Entities (Spawn, Tod, Schaden, Pickup/Drop, Fischen, Zähmen, Blockänderung, Hängeschilder, Leinen, Armorstands und Projektile), Interaktionen (Block/entity use, Eimer, Essen, Schilder, Befehle und optional Chat) sowie Join/Quit/Kick werden als Audit-Events gespeichert.

## Bewusste Grenzen

Die Bukkit/Paper-API liefert nicht für jede interne Weltmutation einen Spieler-Verursacher. Solche Ereignisse werden trotzdem mit `actor=Umgebung` aufgezeichnet. Für maximal vollständige Attribution sollten zusätzliche Gameplay-Plugins ihre eigenen Aktionen über ein kleines späteres API-Modul an BlockProtect melden.

Die Kernmodule implementieren `AuditModule`; Erweiterungen können über `BlockProtectPlugin#registerModule(...)` zusätzliche Listener registrieren und über den öffentlichen `AuditRecorder` dieselbe asynchrone Queue verwenden.
