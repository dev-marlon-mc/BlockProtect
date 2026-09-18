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

- `/bp gui` öffnet das einfache Menü für Lookup, Block-Prüfung, Status und Rollback.
- `/bp inspect` aktiviert die Blockinspektion per Klick.
- `/bp lookup` durchsucht die Umgebung mit den Standardwerten.
- `/bp lookup gui` öffnet dieselbe räumliche Suche direkt als Inventar-GUI.
- `/bp lookup help` erklärt alle Filter mit Beispielen.
- `/bp lookup --radius 20 --player Steve --action break --since 2h` nutzt gut lesbare Filter; die Reihenfolge ist egal.
- `/bp lookup filter` öffnet nach einer Suche die klickbare Filterauswahl; `/bp lookup refresh` lädt die Suche neu.
- `/bp lookup block --action break` prüft den Block unter dem Fadenkreuz.
- Die alte Schreibweise `/bp lookup 10 50 Steve break` bleibt kompatibel.
- `/bp status` zeigt Tracking, Module und Queue.
- `/bp module set <name> <on|off>` schaltet einzelne Module sofort um.
- `/bp config get <path>`, `/bp config set <path> <wert>` und `/bp config list` ändern jede Einstellung live und speichern sie sofort.
- `/bp flush` leert die Schreibqueue.
- `/bp purge <tage> confirm` löscht alte Einträge dauerhaft.

## Erfasste Bereiche

Die mitgelieferte Konfiguration hat alle Module und Detailoptionen aktiviert – einschließlich Befehlen und Chat.

Blöcke (abbauen, platzieren, Explosionen, Feuer, TNT, Wachstum, Düngen, Feuchtigkeit, Kessel, Schwämme, Flüssigkeitsfluss, Pistons, Kochen und Drops), Container und Inventare (öffnen, Klicks, Drag, Hopperbewegungen, Entnahmen, Crafting, Brauen und Dispense), Entities (Spawn, Schaden, Tod, Entfernen, Transformation, Explosionen, Teleport, Zielwechsel, Pickup/Drop, Fischen, Zähmen, Blockänderung, Hängeschilder, Leinen, Armorstands und Projektile), Interaktionen (Block/entity use, Eimer, Essen, Schilder, Befehle und Chat) sowie Join/Quit/Kick werden als Audit-Events gespeichert.

Bei Entity-Toden speichert BlockProtect nicht nur einen Spieler-Killer. Über Paper `DamageSource` werden Ursache, verursachende Entity und direkte Quelle getrennt aufgezeichnet – zum Beispiel Zombie → Dorfbewohner, Skelett → Pfeil → Spieler oder Creeper/TNT → Entity.

## Bewusste Grenzen

Die Bukkit/Paper-API liefert nicht für jede interne Weltmutation einen Spieler-Verursacher. Solche Ereignisse werden trotzdem mit Quelle, Ursache und Entity-UUID aufgezeichnet. Hochfrequente interne Ticks wie jede einzelne Redstone-/Physikberechnung werden nicht als Audit-Eintrag gespeichert, weil sie eine Datenbank unbrauchbar schnell überfluten würden. Abgebrochene Events können bei Bedarf mit `tracking.options.record-cancelled: true` ebenfalls protokolliert werden.

Die Kernmodule implementieren `AuditModule`; Erweiterungen können über `BlockProtectPlugin#registerModule(...)` zusätzliche Listener registrieren und über den öffentlichen `AuditRecorder` dieselbe asynchrone Queue verwenden.
