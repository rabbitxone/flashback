# Flashback Server Recorder (eksperyment)

Plugin na **Paper 26.3**, który nagrywa rozgrywkę **po stronie serwera** i zapisuje ją jako replay
w formacie Flashbacka (`.zip`). Taki plik wrzucasz do `.minecraft/flashback/replays/` i otwierasz
normalnie w modzie Flashback (wersja dla MC 26.3).

Kod formatu (akcje, snapshoty, `level_chunk_caches`, `metadata.json`) to port klas z modu:
`ReplayWriter`, `AsyncReplaySaver`, `ReplayExporter`, `Recorder#writeSnapshot`, `IgnoredPacketSet`.

## Tryby nagrywania

| Tryb | Co nagrywa | Jak |
|------|------------|-----|
| **Kamera** (`camera`) | Wszystko w zasięgu `viewDistance` chunków od punktu, niezależnie od graczy. Idealne na arenę mini-gry. | Niewidzialny „gracz-kamera” w trybie spectator dodany tylko do świata (nie do listy graczy). Serwer wysyła mu chunki, encje, bloki, cząsteczki – pakiety są zapisywane zamiast iść do sieci. |
| **Gracz** (`player`) | Dokładnie to, co widzi jeden gracz (jak Flashback na jego kliencie). | Handler Netty w pipeline gracza + co tick dane „o sobie” (hotbar, HP, XP, ekwipunek, machanie ręką), których serwer graczowi nie wysyła. |

Kamera dodatkowo:
- **tablista / skiny** – generowana z listy graczy serwera,
- **czas** – synchronizowany co sekundę,
- **dźwięki i `level_event`** – serwer rozsyła je przez listę graczy, więc kamera „podsłuchuje” je
  u prawdziwych graczy w tym samym świecie (deduplikacja po instancji pakietu),
- **HUD** (scoreboard, tab header/footer, bossbary, tytuły, czat) – kopiowany z wybranego gracza („HUD source”),
- **pękanie bloków** – odtwarzane z eventu `BlockBreakProgressUpdateEvent`.

Na timeline Flashbacka trafiają **markery**: śmierci graczy (automatycznie), zmiany wymiaru,
oraz własne z API/komendy.

## Komendy (`flashbackrecorder.admin`, domyślnie OP)

```
/fbrec camera [nazwa] [viewDistance] [hudGracz|-] [świat x y z [yaw pitch]]
/fbrec player <gracz> [nazwa]
/fbrec stop <nazwa|all>
/fbrec list
/fbrec marker <nazwa> [opis...]
/fbrec hud <nazwa kamery> <gracz|none>
/fbrec verify <plik.zip>      # parsuje replay tak jak Flashback i dekoduje każdy pakiet
```

Bez współrzędnych kamera staje w miejscu gracza, który wpisał komendę.
Gotowe pliki lądują w `plugins/FlashbackServerRecorder/replays/`.

## Automatyczne nagrywanie aren (`config.yml`)

```yaml
auto-record:
  enabled: true
  arenas:
    bedwars1:
      world: bedwars1
      x: 0.5
      y: 100
      z: 0.5
      view-distance: 8
      min-players: 2
```

Nagrywanie startuje, gdy w świecie jest co najmniej `min-players` graczy, a kończy się
(i eksportuje ZIP), gdy świat się opróżni.

## API dla własnej mini-gry

W `plugin.yml` swojego pluginu dodaj `depend: [FlashbackServerRecorder]`, a jar pluginu jako `compileOnly`.

```java
FlashbackRecorderApi api = FlashbackRecorderApi.get();

// start gry
RecordingHandle rec = api.recordCamera("duel-" + gameId, arenaCenter, 6, someParticipant);

// w trakcie
rec.marker("Pierwsza krew: " + killer.getName(), 0xFF5555, victim.getLocation());

// koniec gry
rec.stop().thenAccept(zip -> getLogger().info("Replay: " + zip));

// kamery są w world.getPlayers(), ale NIE w Bukkit.getOnlinePlayers()
if (api.isCamera(player)) return;
```

Wszystkie metody wywołuj na głównym wątku.

## Budowanie

Wymaga JDK 25 (Gradle wrapper w tym folderze używa Gradle 9.7.1, którego wymaga paperweight).

```bash
cd server-plugin
./gradlew build
```

Wynik: `build/libs/flashback-server-recorder-<wersja>.jar`.

## Stan testów i znane ograniczenia

Przetestowane na prawdziwym serwerze Paper 26.3 (build 244): kamera nagrywa moby, bloki,
pogodę, czas, pioruny i markery, a `/fbrec verify` dekoduje cały ZIP bez błędów
(z tymi samymi kodekami pakietów 26.3 co klient).

**Nie** przetestowane jeszcze z prawdziwym klientem: otwieranie we Flashbacku, tryb `player`,
most dźwiękowy, kopiowanie HUD-u i synchronizacja tablisty.

- Wersja serwera musi zgadzać się z wersją Flashbacka (tu 26.3), bo pakiety są zapisywane w protokole serwera.
- Kamera jest w `world.getPlayers()` – jeśli Twoja mini-gra liczy graczy w świecie, filtruj przez `isCamera`.
- Dźwięki w trybie kamery są tylko te, które usłyszał jakiś prawdziwy gracz w pobliżu; dźwięki
  wywołane przez gracza, który był jedynym w okolicy (np. jego kroki), mogą zniknąć.
- W trybie `player` brakuje dźwięków, które klient odtwarza sam (kroki, atak itp.).
- Bossbary utworzone przez `Bukkit.createBossBar`/Adventure przed startem nagrania nie trafiają do
  pierwszego snapshotu (te dodane później już tak).
- Snapshot na początku każdego segmentu (domyślnie 5 min) jest budowany na głównym wątku – przy dużym
  `viewDistance` może to dać krótki lag.
- Przejście gracza do fazy konfiguracji (np. `/reconfigure`) kończy nagrywanie w trybie `player`.
