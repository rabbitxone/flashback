# Flashback Server Recorder (eksperyment)

Plugin na **Paper 26.3**, który nagrywa rozgrywkę **po stronie serwera** i zapisuje ją jako replay
w formacie Flashbacka (`.zip`). Plik wrzucasz do `.minecraft/flashback/replays/` i otwierasz
normalnie w modzie Flashback (wersja dla MC 26.3). W replayu latasz swobodnie kamerą po całym
nagranym terenie.

## Jak to działa

Nagrywany jest **cały świat** (albo kwadratowy obszar wokół areny), bez kamer i bez fałszywych graczy:

- **Snapshot** (na początku i co `segment-length-seconds`): rejestry, teren (wszystkie załadowane
  chunki obszaru albo wszystko, co widzą gracze), wszystkie encje, tablista, scoreboard, czas, pogoda.
- **Co tick, ze stanu serwera**: pozycje i obroty wszystkich encji i graczy, pojawianie się
  i znikanie encji, tablista (wejścia, wyjścia, gamemode), ekwipunek, sneak/sprint/pozy i machanie ręką
  graczy, czas i pogoda.
- **Z pakietów wysyłanych do graczy w tym świecie**: zmiany bloków, dźwięki, cząsteczki, eksplozje,
  animacje, metadane encji, efekty, border. Serwer wysyła każdemu pobliskiemu graczowi tę samą instancję
  pakietu, więc duplikaty są usuwane po tożsamości obiektu.
- **HUD** (scoreboard, nagłówek tablisty, bossbary, tytuły, czat) – kopiowany z jednego gracza
  (domyślnie wybieranego automatycznie, można ustawić ręcznie).
- **Markery** na osi czasu: śmierci graczy (automatycznie) oraz własne z API/komendy.

Kod formatu (akcje, snapshoty, `level_chunk_caches`, `metadata.json`) to port klas z modu:
`ReplayWriter`, `AsyncReplaySaver`, `ReplayExporter`, `Recorder#writeSnapshot`.

## Tryby

Wszystkie tryby nagrywają świat (teren, wszystkie encje i graczy). Różnią się obszarem i tym,
gdzie startuje kamera w replayu:

| Tryb | Obszar | Start kamery / „lokalny gracz” replayu |
|------|--------|----------------------------------------|
| `world`  | cały świat | przy pierwszym graczu |
| `area`   | kwadrat wokół punktu | nad środkiem obszaru |
| `camera` | cały świat albo kwadrat | dokładnie w Twoim miejscu i z Twoim kierunkiem patrzenia |
| `player` | cały świat gracza | to ten gracz – Flashback startuje przy nim, może go śledzić, pokazuje jego scoreboard/czat; nagranie kończy się, gdy gracz wyjdzie ze świata |

## Hotbar, jedzenie i XP przy Spectate

Zwykły Flashback zna hotbar tylko gracza, który nagrywał na swoim kliencie – o innych graczach klient
wie tylko, co trzymają w rękach. Plugin zapisuje dla **każdego** gracza dodatkową akcję
`flashback:action/player_state_optional` (hotbar, offhand, wybrany slot, jedzenie, XP).
Obsługuje ją ten fork Flashbacka (`ActionPlayerState`); oryginalny Flashback ją pomija
(końcówka `_optional`), więc nagrania dalej się w nim otwierają, tylko bez hotbarów innych graczy.

## Komendy (`flashbackrecorder.admin`, domyślnie OP)

```
/fbrec world <nazwa> [świat]                         # cały świat
/fbrec area <nazwa> <promień> [świat x z]           # kwadrat wokół gracza albo podanych x/z
/fbrec camera <nazwa> [promień] [świat x y z [yaw pitch]]   # promień 0 lub brak = cały świat
/fbrec player <nazwa> <gracz>
/fbrec stop <nazwa|all>
/fbrec list
/fbrec marker <nazwa> [opis...]
/fbrec hud <nazwa> <gracz|auto>
/fbrec verify <plik.zip>      # czyta replay tak jak Flashback i dekoduje każdy pakiet
```

Gotowe pliki lądują w `plugins/FlashbackServerRecorder/replays/`.

## Automatyczne nagrywanie (`config.yml`)

```yaml
auto-record:
  enabled: true
  arenas:
    bedwars1:
      world: bedwars1        # bez x/z/radius nagrywany jest cały świat
      min-players: 2
    duel:
      world: duels
      x: 1000
      z: 0
      radius: 48
      min-players: 2
```

Nagrywanie startuje, gdy w świecie jest co najmniej `min-players` graczy, a kończy się
(i eksportuje ZIP), gdy świat się opróżni.

## API dla własnej mini-gry

W `plugin.yml` swojego pluginu dodaj `depend: [FlashbackServerRecorder]`, a jar tego pluginu dodaj jako `compileOnly`.

```java
FlashbackRecorderApi api = FlashbackRecorderApi.get();

// start gry
RecordingHandle rec = api.recordArea("duel-" + gameId, arenaCenter, 64);
// albo: api.recordWorld("bedwars-" + gameId, arenaWorld);
// albo: api.recordCamera("duel-" + gameId, cameraLocation, 64);
// albo: api.recordPlayer("pov-" + player.getName(), player);

// w trakcie
rec.marker("Pierwsza krew: " + killer.getName(), 0xFF5555, victim.getLocation());

// koniec gry
rec.stop().thenAccept(zip -> getLogger().info("Replay: " + zip));
```

Wszystkie metody wywołuj na głównym wątku.

## Budowanie

Wymaga JDK 25 (wrapper w tym folderze używa Gradle 9.7.1, którego wymaga paperweight).

```bash
cd server-plugin
./gradlew build
```

Wynik: `build/libs/flashback-server-recorder-<wersja>.jar`. Gotowy jar leży też w `dist/`.

GitHub Actions (`.github/workflows/build.yml`) buduje przy każdym pushu zarówno plugin, jak i mod
Flashback z tego forka – oba jary są do pobrania jako artefakty w zakładce **Actions** repozytorium.

## Ograniczenia

- Wersja serwera musi zgadzać się z wersją Flashbacka (tu 26.3).
- Zmiany bloków, dźwięki i efekty są nagrywane tylko tam, gdzie jest jakiś gracz (bo bierzemy je
  z pakietów wysyłanych graczom). Pozycje encji i teren ze snapshotu nie mają tego ograniczenia.
- Dźwięki wywołane przez gracza, który jest sam w okolicy (jego kroki, stawianie bloków), mogą nie
  trafić do nagrania – serwer nie wysyła ich temu graczowi, bo jego klient odtwarza je sam.
- Snapshot jest budowany na głównym wątku – przy bardzo dużym obszarze może to dać krótki lag
  co `segment-length-seconds`.
- Bossbary utworzone przez `Bukkit.createBossBar`/Adventure przed startem nagrania nie trafiają do
  pierwszego snapshotu (te dodane później już tak).
