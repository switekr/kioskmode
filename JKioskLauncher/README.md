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
   klasycznego), więc niezależnie od języka Windows - okno jest ukrywane i
   dostaje `WM_CLOSE` (dla przeglądarki to zwykłe "Anuluj").

Wcześniejsza wersja reagowała dopiero na krok 2, czyli już po pokazaniu
ramki okna - okno było widoczne ok. 11-19 ms (jedna klatka 60 Hz), co
było widać jako mrugnięcie. Teraz sonda w teście mierzy 0 ms widoczności.

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

Test bez klikania (otwiera prawdziwe `OpenFileDialog` na tymczasowym,
nieaktywnym desktopie): przebieg kontrolny bez strażnika musi "zawisnąć",
5 przebiegów ze strażnikiem musi zwrócić "Cancel" bez widocznego mignięcia
(sonda mierzy, jak długo okno jest widoczne; próg ok. jednej klatki), a
zwykły `MessageBox` ze strażnikiem musi zostać otwarty i znów być widoczny:

```bash
mvn package
java -cp "target/JKioskLauncher.jar;target/test-classes" com.kiosklauncher.FileDialogWatchdogSmokeTest
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
   żadne okno wyboru pliku nie może pozostać na ekranie (test dymny
   sprawdza to na `OpenFileDialog` z PowerShella, ale nie na prawdziwym
   Edge - to wymaga ręcznego przeklikania).

Jeśli `Ctrl+Alt+Shift+K` nie zadziała, najpierw sprawdź `Ctrl+Alt+Del` →
Menedżer zadań → zakończ `java.exe` jako awaryjne wyjście - dokładnie tak
samo jak w wersji C#.
