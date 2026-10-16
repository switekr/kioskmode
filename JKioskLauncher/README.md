# JKioskLauncher

Port [`KioskLauncher`](../KioskLauncher) (C#/.NET) na Javę. Identyczna
zasada działania - izolacja przez osobny obiekt desktopu Win32
(`CreateDesktop`/`SwitchDesktop`, ten sam mechanizm co bezpieczny ekran
UAC), hotkey `Ctrl+Alt+Shift+K` do wyjścia przez blokadę sesji
(`LockWorkStation`). Przeczytaj [README.md KioskLauncher](../KioskLauncher/README.md)
po pełny opis mechanizmu - tu opisane są tylko różnice specyficzne dla Javy.

> **✅ Przetestowane i działa.** Zbudowane przez `mvn package` i uruchomione
> przez `java -jar target/JKioskLauncher.jar` - pełne przełączenie desktopu,
> pełny ekran, `Ctrl+Alt+Shift+K` (blokada ekranu, nie bezpośredni powrót)
> i blokowanie Alt+F4 zostały ręcznie potwierdzone jako działające.

## Jak wywołane jest Win32 API

Przez [JNA](https://github.com/java-native-access/jna) (Java Native
Access) - jedyna zależność w projekcie. To odpowiednik P/Invoke z C#:
pozwala wołać dowolne funkcje z `user32.dll`/`kernel32.dll` bez pisania
kodu w C/JNI. Wszystkie deklaracje są w [`Win32.java`](src/main/java/com/kiosklauncher/Win32.java)
- bezpośredni port `NativeMethods.cs`.

## Różnice względem wersji C#

- **Tray icon**: zamiast WinForms `NotifyIcon` - wbudowany w JDK
  `java.awt.SystemTray`/`TrayIcon`. Ikona jest generowana w locie
  (Java2D), nie ładowana z pliku zasobu.
- **Zarządzanie procesem przeglądarki**: zamiast `Process.GetProcessById`
  (którego Java nie ma) - `ProcessHandle.of(pid)` (Java 9+), które
  "dołącza się" do istniejącego PID i pozwala go ubić razem z potomkami
  (`descendants()`) bez potrzeby posiadania go od startu.
- **Konfiguracja**: `kiosk.settings.json` czytany/pisany ręcznie
  (`KioskConfig.java`) zamiast przez `System.Text.Json` - żeby nie
  dokładać drugiej zależności (np. Gson/Jackson) do jednego prostego,
  płaskiego pliku. Nazwy pól w JSON-ie są identyczne jak w wersji C#
  (`Url`, `BrowserPath`, `KioskType`, `IdleTimeoutMinutes`), więc ten sam
  plik ustawień działa z obiema wersjami.
- **Wątki**: `new Thread()` w Javie to zawsze prawdziwy wątek OS (bez
  green threads), więc `SetThreadDesktop` przypina dokładnie ten wątek,
  którego oczekujemy - bez dodatkowych zabiegów, w przeciwieństwie np. do
  Go, gdzie goroutines wymagają jawnego `runtime.LockOSThread()`.

## Tryby pracy z plikami: `FileMode`

Obsługa plików (dwie następne sekcje) ma **dwa tryby**, wybierane w
`kiosk.settings.json`:

```json
"FileMode": "block"
```

| `FileMode` | uploady | pobieranie | Eksplorator |
|---|---|---|---|
| **`"block"`** (domyślny) | zablokowane | **zablokowane** - żaden plik nie jest zapisywany | zamykany |
| **`"download"`** | zablokowane | tylko do folderu z `DownloadDir`, potem przekazanie do `handoff` | zamykany |

1. **`block`** - żadnej pracy z plikami: nie da się niczego wgrać ani
   pobrać. `DownloadDir` jest ignorowany, nic nie powstaje na dysku.
2. **`download`** - klient może pobierać, ale wyłącznie do jednego,
   zdefiniowanego katalogu (`DownloadDir`, patrz "Folder pobierania"); po
   sesji pliki są przenoszone do `handoff`. Upload nadal jest zablokowany.

Nie ma trybu "wyłączone" i to celowo: kiosk działa na koncie operatora, więc
natywne okno wyboru pliku w jakimkolwiek trybie ujawniłoby jego pliki.

Brak klucza, wartość nierozpoznana lub literówka (`"downlaod"`) dają
**`block`** - błąd w pliku może kiosk tylko zaostrzyć, nigdy poluzować. Wielkość
liter i spacje wokół wartości nie mają znaczenia. Stary klucz `FileGuard` jest
ignorowany (a więc = `block`). Plik czytany jest z katalogu jara i **tylko
przy starcie aplikacji** - po zmianie uruchom ją ponownie.

Od trybu **nie zależą** i działają zawsze: izolowany desktop, skrót
`Ctrl+Alt+Shift+K` z blokadą ekranu przy wyjściu, blokada Alt+F4 oraz świeży
profil przeglądarki.

## Blokada okien wyboru pliku

Przeglądarka kiosku działa na **koncie operatora** - izolacja desktopu
ukrywa jego okna, ale nie pliki. Bez dodatkowej ochrony okno "Otwórz
plik" (załącznik w webmailu, Ctrl+O, "Zapisz jako") pokazałoby cały profil
operatora i pozwoliło wysłać dowolny plik. Edge w trybie kiosku nie
blokuje tych okien sam z siebie, a polityka, która to robi
(`AllowFileSelectionDialogs`), jest maszynowa - wyłączyłaby upload także w
Edge operatora na tym samym komputerze.

Dlatego `FileDialogWatchdog` zamyka takie okna w samej aplikacji, bez
rejestru i uprawnień admina. Hooki `SetWinEventHook` na wątku strażnika
(przypiętym do desktopu kiosku) działają w dwóch krokach:

1. gdy powstaje okno dialogowe najwyższego poziomu (`#32770`), od razu
   robimy je w pełni przezroczystym (`WS_EX_LAYERED`, alfa 0) - zanim
   zdąży się pierwszy raz pokazać, więc nie ma czego "mrugnąć";
2. gdy w środku pojawi się kontrolka widoku plików - rozpoznawana po
   **klasie okna** (`DirectUIHWND` dla nowego stylu, `SHELLDLL_DefView` dla
   klasycznego), więc niezależnie od języka Windows - okno jest ukrywane i:
   - **tryb `block`**: każde takie okno dostaje `WM_CLOSE`, czyli dla
     przeglądarki zwykłe "Anuluj" - nic nie zostaje wgrane ani zapisane;
   - **tryb `download`**, **okno otwierania** (pole nazwy pliku puste - to
     upload): `WM_CLOSE`, jak wyżej;
   - **tryb `download`**, **okno zapisu** (pole nazwy ma wpisaną sugerowaną
     nazwę - tak wygląda pobieranie w Edge): jest **zatwierdzane** - nazwa
     zostaje, ścieżka jest podmieniana na jeden wskazany folder pobierania
     (patrz niżej).

Okno otwierania od zapisu odróżniamy po wpisanej nazwie, a kontrolki
znajdujemy po klasie i identyfikatorze, nie po tytułach ani napisach na
przyciskach - więc to też nie zależy od języka systemu.

**Pusty tekst to nie wyrok.** Tuż po pojawieniu się okna zapisu Edge ma już
pole nazwy, ale jeszcze bez tekstu (w pomiarach ok. co piątego pobranie).
Wcześniejsza wersja brała to za upload i anulowała - pobranie po cichu
znikało (5 z 8 przebiegów testu nie zapisywało pliku). Teraz pusta nazwa
oznacza "spróbuj ponownie": dopiero jeśli pole jest puste jeszcze po ok.
1,5 s, okno jest uznawane za upload i anulowane. Okno cały czas pozostaje
ukryte, więc klient nie widzi różnicy; blokowany upload po prostu trwa
1,5 s dłużej. Diagnostyka: `-Dkiosk.trace=true` wypisuje na stderr, co
strażnik zdecydował i dlaczego.

Wcześniejsza wersja reagowała dopiero na krok 2, czyli już po pokazaniu
ramki okna - okno było widoczne ok. 11-19 ms (jedna klatka 60 Hz), co
było widać jako mrugnięcie. Teraz sonda w teście mierzy 0 ms widoczności.

**Okna Eksploratora plików** są zamykane tym samym mechanizmem. Przeglądarka
ma drogę do nich: "Pokaż w folderze" na liście pobranych (Ctrl+J). Edge
uruchamia wtedy nowy `explorer.exe`, a ponieważ na desktopie kiosku nie ma
powłoki, otwiera on pełne okno folderu (`CabinetWClass`) - czyli przeglądarkę
plików po całym profilu operatora (sprawdzone na żywo na tymczasowym
desktopie). Takie okno staje się przezroczyste w chwili utworzenia i dostaje
`WM_CLOSE`, a proces `explorer.exe`, który je otworzył, jest zabijany -
**tylko** jeśli powstał po uzbrojeniu strażnika. Twój własny shell startuje
przy logowaniu, więc ta reguła go nie dotyka (test sprawdza, że przeżywa).

Dodatkowo Ctrl+O i Ctrl+S są połykane przez istniejący hook klawiatury.
Inne natywne okna (np. systemowy dialog drukowania lub zwykły komunikat)
nie są zamykane - odzyskują widoczność po 1,5 s, więc są tylko opóźnione,
nie utracone. Jeśli na bardzo wolnej maszynie kontrolka widoku plików
pojawi się dopiero po tych 1,5 s, okno i tak zostanie zamknięte, ale
mogło być przez chwilę widoczne.

Jeśli strażnik nie da się uzbroić (hook się nie zainstaluje), kiosk w
ogóle się nie uruchamia - fail closed - zamiast oddać ekran klientowi bez
ochrony.

**Czego to NIE chroni:** wpisania `file:///C:/Users/...` w pasku adresu.
Klient nie wyśle w ten sposób pliku (nie ma okna wyboru), ale mógłby
przeglądać pliki operatora i skopiować ich tekst do poczty. Zamyka to
dopiero polityka `URLBlocklist = file://*` - wymaga GPO/Intune i działa
maszynowo, więc to naturalny dodatek przy wdrożeniu korporacyjnym, jeśli
akceptowalny jest jej wpływ na Edge operatora. Wersja C# (`KioskLauncher/`)
nie ma jeszcze tej ochrony.

Test bez klikania (prawdziwe okna wspólne z PowerShella na tymczasowym,
nieaktywnym desktopie): przebieg kontrolny okna otwierania bez strażnika
musi "zawisnąć"; ze strażnikiem okno otwierania musi zwrócić "Cancel" bez
widocznego mignięcia (sonda mierzy, jak długo okno jest widoczne; próg ok.
jednej klatki); w trybie `download` okno zapisu z nazwą musi zostać
zatwierdzone ścieżką w folderze pobierania (a gdy nazwa jest zajęta -
`nazwa (1).ext`, bez pytania o nadpisanie), a z pustą nazwą - anulowane; w
trybie `block` anulowane muszą być wszystkie okna, także zapisu z dobrą
nazwą, i nic nie może trafić do folderu; zwykły `MessageBox` musi zostać
otwarty i znów być widoczny:

```bash
mvn package
java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.FileDialogWatchdogSmokeTest
```

## Folder pobierania

Dotyczy trybu `"FileMode": "download"` (w `block` nic się nie pobiera, a
`DownloadDir` jest ignorowany). Pobrane pliki trafiają do **jednego folderu**
(domyślnie `%LOCALAPPDATA%\JKioskLauncher\downloads`, zmiana przez
`DownloadDir` w `kiosk.settings.json`, np. `"DownloadDir": "C:\\tmp"`; wersja
C# ignoruje ten klucz), bez okna "Zapisz jako" dla klienta.

**Dlaczego nie da się tego ustawić samą konfiguracją przeglądarki.** Pierwszy
pomysł - wstępnie zasiać profil (`download.default_directory`,
`prompt_for_download=false` w `Preferences`, przekazany przez
`--user-data-dir`) - działa w zwykłym oknie Edge, ale **nie w trybie kiosku**:
sprawdzone na prawdziwym Edge 155 - kiosk nadpisuje folder własnym
(`%LOCALAPPDATA%\Microsoft\Edge\KioskDownloads`) i wymusza okno "Zapisz jako"
przy każdym pobraniu. Wcześniejsza wersja strażnika anulowała to okno, a
anulowane pobranie po prostu się nie dzieje. Polityki Edge odpadają, bo kiosk
działa na koncie operatora, więc dotknęłyby też jego własnej przeglądarki.
Stąd obecne rozwiązanie: strażnik sam odpowiada na okno zapisu (patrz wyżej).

Przeglądarka dostaje przy tym własny, świeży profil (`--user-data-dir`,
kasowany przy każdym starcie): nic nie przechodzi między klientami, a
działający Edge operatora nie może "przechwycić" uruchomienia (Chromium
oddaje drugi start na tym samym profilu pierwszemu procesowi - na jego
desktopie).

Po sesji (`Ctrl+Alt+Shift+K`, już po zablokowaniu ekranu) `DownloadCollector`
przenosi gotowe pliki z folderu pobierania do jego podfolderu
`<folder pobierania>\handoff\<yyyyMMdd-HHmmss>\` (np. `C:\tmp\handoff\...`) i
zapisuje je w `handoff\handoff.log`; niedokończone pobrania (`.crdownload`,
`.tmp`) są usuwane. Poza podfolderem `handoff` folder pobierania zostaje
pusty dla następnego klienta - to samo dzieje się przy starcie, gdyby
poprzednia sesja skończyła się awarią. Podfolder `handoff` nigdy nie jest
"przenoszony sam do siebie" (zbierane są tylko zwykłe pliki).

**To ogranicza ekspozycję, ale nie jest granicą bezpieczeństwa.** Kiosk działa
na koncie operatora, więc wszystko, co operator może odczytać, klient może
otworzyć przez `file:///` - w tym pobrane PDF-y. Ponieważ `handoff` leży
**wewnątrz** folderu pobierania, listing `file:///C:/tmp/` pokazuje go
klientowi od razu, razem z plikami poprzednich sesji (gdy leżał obok, trzeba
było wiedzieć, że istnieje). Szczelne zamknięcie to polityka
`URLBlocklist = file://*` albo osobna tożsamość procesu kiosku. Dotyczy to
dokumentów klientów, więc przy wdrożeniu korporacyjnym warto to potraktować
poważnie.

"Pokaż w folderze" otwierało Eksplorator - to jest już zamykane (patrz wyżej).
Nie sprawdzone i nie zablokowane: "Otwórz plik" na pobranym pliku uruchomi
skojarzony program (np. czytnik PDF) jako operator na desktopie kiosku. To
ta sama klasa luki co Eksplorator - ogólne rozwiązanie to zamykanie każdego
okna najwyższego poziomu, które nie należy do przeglądarki - ale wymaga
starannej białej listy (IME, nakładki systemowe), więc nie jest zrobione.

Testy: `DownloadFolderSmokeTest` uruchamia **prawdziwy Edge w trybie kiosku**
(te same flagi co aplikacja) na tymczasowym desktopie i lokalnym serwerze
HTTP wymuszającym pobranie, w obu trybach. Tryb `download` - plik musi być
kompletny w folderze docelowym i nigdzie indziej (ani w systemowym Pobrane,
ani w `KioskDownloads`); tryb `block` - plik nie może powstać nigdzie. W
obu na ekranie nie może zostać żadne okno. `-DviaPage=true -Dext=pdf`
sprawdza pobranie wywołane skryptem ze strony. `DownloadCollectorTest`
sprawdza przenoszenie plików i odczyt `FileMode` (w tym, że literówka i stary
klucz dają `block`), a `ExplorerWindowTest` uruchamia
`explorer.exe /select,...` na tymczasowym desktopie (tak jak "Pokaż w
folderze") i sprawdza, w obu trybach, że strażnik zamyka okno w ułamku
klatki, nie zostawia procesu, a shell operatora przeżywa.

Test pobierania jest jedynym, który wykrył wyścig z pustą nazwą - przy
jednokrotnym uruchomieniu łatwo go przeoczyć, bo wychodzi ok. raz na kilka
przebiegów. Po poprawce: 15 z 15 przebiegów ze śladem i 8 z 8 bez. Pomiar
mignięcia Eksploratora zależy od obciążenia maszyny - raz zdarzył się
pojedynczy odczyt ponad próg po serii ciężkich testów, ponowne uruchomienie
dawało 2-4 ms:

```bash
java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.DownloadFolderSmokeTest
java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.DownloadCollectorTest
java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.ExplorerWindowTest
```

## Budowanie i uruchamianie

Wymaga JDK 17+ i Mavena (przetestowane na JDK 21 / Maven 3.9).

```bash
mvn package
java -jar target/JKioskLauncher.jar
```

`mvn package` (dzięki `maven-shade-plugin` w `pom.xml`) tworzy jeden
uruchamialny "fat jar" z wbudowanym JNA - `target/JKioskLauncher.jar`.

W przeciwieństwie do self-contained publish z .NET, **to zawsze wymaga
zainstalowanego JRE na docelowej maszynie** (sam JNA nie eliminuje tej
zależności - jar wciąż potrzebuje JVM). Jeśli zależy Ci na jednym pliku
bez instalowania JRE osobno, doczytaj o `jpackage` (dołączony do JDK od
wersji 14) - potrafi spakować jar razem z własnym, okrojonym środowiskiem
uruchomieniowym w jeden `.exe`, podobnie jak `dotnet publish
--self-contained`. Nie jest to tu skonfigurowane.

## Do sprawdzenia po zbudowaniu

Dokładnie te same scenariusze, które łapały realne bugi w wersji C# -
warto je powtórzyć tutaj, bo część z nich (zwłaszcza kolejność
switch-then-lock i przypięcie wątku do desktopu) to subtelności Win32, nie
C#:

1. Tray → "Uruchom kiosk" - czy okno przeglądarki faktycznie wypełnia cały
   ekran.
2. `Ctrl+Alt+Shift+K` w trybie kiosku - czy pojawia się ekran blokady
   Windows (nie od razu odblokowany pulpit), a po wpisaniu hasła wraca się
   na normalny, działający tray.
3. Alt+F4 w trybie kiosku - powinno być zablokowane (nie zamykać
   przeglądarki).
4. Podwójne uruchomienie `.jar` - drugie powinno pokazać komunikat "już
   działa" i zakończyć się, nie otwierać drugiego tray icon.
5. W kiosku w webmailu kliknij "dołącz plik", a także Ctrl+O i Ctrl+S -
   żadne okno wyboru pliku nie może pozostać na ekranie (test okien
   sprawdza to na oknach z PowerShella, ale nie na prawdziwym webmailu).
6. Pobierz załącznik z poczty - nie może się pokazać okno "Zapisz jako", a
   plik ma się znaleźć w folderze pobierania; po `Ctrl+Alt+Shift+K` i
   odblokowaniu - w `handoff\<data>`. Wejdź też w Ctrl+J i "Pokaż w
   folderze" - okno Eksploratora nie może pozostać. Sprawdź "Otwórz plik"
   (patrz "Folder pobierania").

Jeśli `Ctrl+Alt+Shift+K` nie zadziała, najpierw sprawdź `Ctrl+Alt+Del` →
Menedżer zadań → zakończ `java.exe` jako awaryjne wyjście - dokładnie tak
samo jak w wersji C#.
