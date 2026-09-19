# BlockProtect

BlockProtect besteht aus einem dauerhaft geladenen Core und separat geladenen Live-Modulen für Paper/Minecraft 26.2 und 26.3. Es kompiliert gegen die gemeinsame Paper-26.2-API und begrenzt den Modul-Descriptor auf diese geprüfte Versionsspanne; Audit-Listener werden über eigene ClassLoader geladen und können ohne Bukkit-/Paper-Reload ersetzt werden.

## Build

Voraussetzung: Java 25 und Gradle 9+.

```powershell
.\gradlew.bat clean build
```

Das fertige Core-Plugin liegt danach in `build/libs/BlockProtect-26.2.jar` und wird nach `test-server/plugins/BlockProtect.jar` kopiert. Die beiden Live-Module werden separat gebaut und als `test-server/plugins/BlockProtect/modules/audit.jar` sowie `sessions.jar` installiert. Die Modulversion folgt standardmäßig derselben Projektversion; ein abweichender Wert kann für lokale Update-Tests über `-PmoduleVersion=...` gesetzt werden.

Für den lokalen Update-Test wird eine zweite Modulversion mit SHA-256-Sidecar in das Testserver-Updateverzeichnis gelegt:

```powershell
.\gradlew.bat prepareTestServerModuleUpdate '-PmoduleVersion=26.2.1'
```

Danach erkennt `/bp module check` die lokalen JARs und `/bp module update audit` oder `/bp module update sessions` führt den kontrollierten Austausch aus. Der Core wird dabei nicht ersetzt.

## Testserver

`test-server/paper.jar` ist Paper 26.2 Build 121. Der Server benötigt Java 25. Vor dem ersten Start muss die Minecraft-EULA gelesen und `test-server/eula.txt` manuell auf `eula=true` gesetzt werden. Danach startet:

```powershell
.\test-server\start-test-server.ps1
```

Der Testserver ist absichtlich nur für lokale Tests konfiguriert (`online-mode=false`, `spawn-protection=0`). Er darf nicht öffentlich erreichbar gemacht werden.

Paper 26.3 wird aktuell noch als Alpha-Build getestet. Die 26.3-Freigabe sollte nach Veröffentlichung eines stabilen Paper-Builds erneut auf diesem Build verifiziert werden.

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
- `/bp module gui` öffnet die Admin-GUI zum Aktivieren, Deaktivieren und Aktualisieren der Live-Module. Linksklick schaltet, Rechtsklick aktualisiert.
- `/bp module list` zeigt Live-Module und verfügbare Updates.
- `/bp module check` prüft die konfigurierte Releasequelle sofort.
- `/bp module update <id>` lädt nur eine neuere, SHA-256-geprüfte Version.
- `/bp module enable|disable|restart <id>` verwaltet ein Modul ohne `/reload`.
- `/bp module set <name> <on|off>` schaltet weiterhin einzelne Audit-Unterbereiche sofort um.
- `/bp config get <path>`, `/bp config set <path> <wert>` und `/bp config list` ändern jede Einstellung live und speichern sie sofort.
- `/bp flush` leert die Schreibqueue.
- `/bp purge <tage> confirm` löscht alte Einträge dauerhaft.

## Erfasste Bereiche

Die mitgelieferte Konfiguration hat alle Module und Detailoptionen aktiviert – einschließlich Befehlen und Chat.

Blöcke (abbauen, platzieren, Explosionen, Feuer, TNT, Wachstum, Düngen, Feuchtigkeit, Kessel, Schwämme, Flüssigkeitsfluss, Pistons, Kochen und Drops), Container und Inventare (öffnen, Klicks, Drag, Hopperbewegungen, Entnahmen, Crafting, Brauen und Dispense), Entities (Spawn, Schaden, Tod, Entfernen, Transformation, Explosionen, Teleport, Zielwechsel, Pickup/Drop, Fischen, Zähmen, Blockänderung, Hängeschilder, Leinen, Armorstands und Projektile), Interaktionen (Block/entity use, Eimer, Essen, Schilder, Befehle und Chat) sowie Join/Quit/Kick werden als Audit-Events gespeichert.

Bei Entity-Toden speichert BlockProtect nicht nur einen Spieler-Killer. Über Paper `DamageSource` werden Ursache, verursachende Entity und direkte Quelle getrennt aufgezeichnet – zum Beispiel Zombie → Dorfbewohner, Skelett → Pfeil → Spieler oder Creeper/TNT → Entity.

## Bewusste Grenzen

Die Bukkit/Paper-API liefert nicht für jede interne Weltmutation einen Spieler-Verursacher. Solche Ereignisse werden trotzdem mit Quelle, Ursache und Entity-UUID aufgezeichnet. Hochfrequente interne Ticks wie jede einzelne Redstone-/Physikberechnung werden nicht als Audit-Eintrag gespeichert, weil sie eine Datenbank unbrauchbar schnell überfluten würden. Abgebrochene Events können bei Bedarf mit `tracking.options.record-cancelled: true` ebenfalls protokolliert werden.

## Live-Modularchitektur

`BlockProtectPlugin` und die Datenbank bleiben im Core-JAR. Der Core liest `blockprotect-module.yml` aus `plugins/BlockProtect/modules/*.jar`, prüft API-, Paper- und Minecraft-Version, lädt den Entrypoint mit einem child-first `URLClassLoader` und hält nur die API-Pakete parent-first. Aktuell werden zwei unabhängige Module gebaut:

- `audit`: Block-, Container-, Entity- und Interaktions-Audit.
- `sessions`: Join-, Quit- und Kick-Audit.

Ein Modul implementiert `BlockProtectModule` und erhält einen `ModuleContext`. Listener, Bukkit-Tasks, Modulbefehle, Executor, Threads und `AutoCloseable`-Ressourcen müssen über diesen Kontext registriert werden. Beim Stoppen werden zuerst Tasks und Listener entfernt, danach Befehle und Ressourcen geschlossen, Threads unterbrochen und zuletzt der ClassLoader geschlossen. Dadurch bleibt der Core geladen, während das Modul ausgetauscht wird.

Der Updatepfad ist für den Testserver standardmäßig `local` und verwendet nur Dateien aus `plugins/BlockProtect/updates`. Für Produktion kann `updates.source: github` mit `updates.github.repository: dev-marlon-mc/BlockProtect` verwendet werden. Die GitHub-Implementierung fragt ausschließlich das aktuelle GitHub-Release ab, nicht Branches oder Commits. Jede JAR benötigt eine `.sha256`-Datei; vor dem Stoppen des alten Moduls werden Hash, Descriptor, Entrypoint und Kompatibilität geprüft. Beim Fehler der neuen Aktivierung wird die vorherige JAR aus `module-backups/<id>/` wiederhergestellt und erneut aktiviert.

Für ein privates GitHub-Repository muss jeder Server ein Fine-Grained-Token mit Read-only-Zugriff auf Repository-Inhalte als Umgebungsvariable `BLOCKPROTECT_GITHUB_TOKEN` erhalten. Das Token wird nicht in der Plugin-Konfiguration gespeichert oder protokolliert. Bei öffentlichen Repositories ist kein Token erforderlich.

Ein GitHub-Release verwendet einen numerischen Tag wie `v26.2` und enthält die mit Gradle gebauten Assets `BlockProtect-Audit-26.2.jar` samt `.jar.sha256` sowie `BlockProtect-Sessions-26.2.jar` samt `.jar.sha256`. Kürzere Namen wie `audit-26.2.jar` und `sessions-26.2.jar` werden ebenfalls erkannt. Release-Tag, Core-Version und Modulversionen folgen standardmäßig derselben Version; der Paper-/Minecraft-Kompatibilitätsbereich bleibt separat im jeweiligen Descriptor festgelegt.

Die zentrale Ressourcensammlung kann keine absichtlich außerhalb der API erzeugten Threads, Scheduler-Tasks oder statischen Bukkit-Registrierungen magisch finden. Modulcode muss deshalb die `ModuleContext`-API benutzen. Drittanbieter-Code mit eigenen globalen Registries oder nicht beendbaren Threads kann einen ClassLoader-Leak verursachen und ist für Hot-Updates nicht geeignet. Bukkit-/Paper-Reloads, das Ersetzen des Core-JARs und das dynamische Austauschen bereits geladener API-Klassen bleiben bewusst außerhalb des Designs.
