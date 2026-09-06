# BlockProtect-Testserver

Der Ordner enthält Paper 26.2 Build 121 und das gebaute Plugin unter `plugins/BlockProtect.jar`.

1. Lies die [Minecraft-EULA](https://aka.ms/MinecraftEULA) und setze danach `eula=true` in `eula.txt`.
2. Starte `start-test-server.ps1` mit Java 25.
3. Gib in der Serverkonsole `op <deinSpielername>` ein.
4. Verbinde dich lokal mit `localhost:25565`.

Nützliche Befehle im Spiel:

- `/bp inspect` – Klick auf einen Block zeigt seine Audit-Historie.
- `/bp lookup [radius] [limit] [spieler] [aktion]` – räumliche Suche.
- `/bp status` – Tracking, Module und Queue.
- `/bp config list` – alle live änderbaren Einstellungen.
- `/bp config set tracking.modules.chat true` – Änderung sofort aktiv, ohne Reload.
- `/bp module set blocks off` – Modul sofort deaktivieren.

Der Testserver ist absichtlich auf `online-mode=false` und `spawn-protection=0` für lokale Tests eingestellt. Nicht öffentlich erreichbar machen.

