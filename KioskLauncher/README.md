# Kiosk Launcher

Aplikacja WinForms (.NET 9), która blokuje ekran do samej przeglądarki bez
wylogowywania bieżącego użytkownika i bez zakładania drugiego konta
Windows - w przeciwieństwie do podejścia z `..\Apply-KioskConfig.ps1`
(Assigned Access), które wymaga osobnego konta i edycji Enterprise.

## Jak to działa

Program tworzy nowy obiekt **desktopu** Win32 (`CreateDesktop`) - dokładnie
ten sam mechanizm, którego Windows używa do bezpiecznego ekranu UAC. Okna
utworzone na jednym desktopie są całkowicie niewidoczne i nieosiągalne
(Alt+Tab, pasek zadań, enumeracja okien) z innego desktopu w tej samej
sesji. Przeglądarka jest uruchamiana bezpośrednio na nowym desktopie
(`--kiosk`), po czym aplikacja się na niego przełącza (`SwitchDesktop`).
Twoje otwarte okna nie znikają - po prostu nie są już aktywnym desktopem.

Dodatkowo, globalny hook klawiatury (`WH_KEYBOARD_LL`) blokuje na czas
trwania kiosku klawisz Windows, Alt+Tab, Alt+F4 i Ctrl+Esc - to działa
niezależnie od aktywnego desktopu, bo przechwytuje surowe wejście zanim
system je skieruje do konkretnego desktopu.

**Ctrl+Alt+Del nigdy nie jest przechwytywane** (obsługuje je Winlogon na
chronionym desktopie) - to zamierzona furtka bezpieczeństwa, nie luka.

## Powrót do swojej sesji

`Ctrl+Alt+Shift+K` - działa zawsze, także gdy aktywny jest desktop kiosku.
Przełącza na Twój Default desktop i w tej samej chwili blokuje sesję
Windows (`LockWorkStation`, to samo co Win+L) - dokładnie tak, jak wygląda
zwykłe Win+L wywołane z Default. Dopiero potem, w tle, zamyka przeglądarkę
kioskową i sam desktop kiosku. Żeby wrócić do pracy, trzeba wpisać hasło na
standardowym ekranie blokady Windows.

Kolejność (przełącz, potem zablokuj) ma znaczenie: Winlogon po odblokowaniu
wraca na desktop, który był aktywny *w momencie wywołania LockWorkStation*,
nie zawsze na Default. Blokowanie, gdy wciąż aktywny był desktop kiosku (a
potem jego niszczenie), zostawiało operatora na martwym, pustym desktopie
bez działającego skrótu wyjścia.

Menu w zasobniku ("Zakończ kiosk") robi dokładnie to samo - dostępne
oczywiście dopiero po odblokowaniu, bo wcześniej Twój desktop nie jest
aktywny.

Uwaga: to oznacza też, że nieoczekiwane zamknięcie przeglądarki (awaria,
przycisk "End session" w Edge) **nie** jest dziś obsługiwane automatycznie -
klient zostanie na pustym desktopie kiosku, dopóki Ty nie użyjesz
Ctrl+Alt+Shift+K. To świadomy kompromis: priorytetem jest to, żeby powrót do
sesji zawsze wymagał hasła, a nie wygoda automatycznego odzyskiwania po
awarii.

## Konfiguracja

Przy pierwszym uruchomieniu obok pliku .exe powstaje `kiosk.settings.json`:

```json
{
  "Url": "https://outlook.com",
  "BrowserPath": null,
  "KioskType": "public-browsing",
  "IdleTimeoutMinutes": 5
}
```

- `BrowserPath: null` - autodetekcja Edge (potem Chrome) w standardowych
  ścieżkach instalacji. Ustaw ręcznie, jeśli przeglądarka jest gdzie
  indziej.
- `KioskType`: `public-browsing` (wiele kart, można nawigować - do
  sprawdzania poczty/bankowości) albo `fullscreen` (jedna, ustalona
  strona).

## Budowanie i uruchamianie

Do developmentu (wymaga .NET 9 SDK na tej maszynie):

```bash
dotnet build
dotnet bin/Debug/net9.0-windows/KioskLauncher.dll
```

### Wdrożenie na docelowy kiosk

Domyślny `dotnet publish` jest *framework-dependent* - docelowy komputer
musiałby mieć zainstalowany .NET 9 Desktop Runtime. Żeby tego uniknąć i
móc po prostu skopiować gotowy `.exe` na czysty Windows, publikuj jako
*self-contained*:

```bash
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

Wynik: `bin/Release/net9.0-windows/win-x64/publish/KioskLauncher.exe` (ok.
110 MB - to wbudowany runtime, nie rozdymka kodu). Ten jeden plik działa na
dowolnym 64-bitowym Windows bez instalowania czegokolwiek dodatkowego.
Skopiuj go razem z `kiosk.settings.json` (jeśli już go dostosowałeś) do
docelowej maszyny.

Jeśli zarządzasz wieloma kioskami centralnie (Intune/SCCM) i tak czy
inaczej dbasz o to, żeby .NET Desktop Runtime był tam zainstalowany,
framework-dependent publish (`--self-contained false`) da dużo mniejszy
plik (~kilka MB) kosztem tej zależności.

## Ograniczenia i uwagi

- Nie wymaga uprawnień administratora ani Windows Enterprise - działa na
  Home/Pro, bo `CreateDesktop` jest dostępne zwykłemu użytkownikowi we
  własnej sesji.
- Aplikacja musi zostać uruchomiona w tej samej sesji, w której chcesz
  chronić okna (nie da się jej odpalić zdalnie i "objąć" cudzej sesji).
- Jeśli chcesz, żeby uruchamiała się automatycznie przy starcie Windows,
  dodaj skrót do `shell:startup` albo zadanie w Harmonogramie zadań.
- To własny kod, nie funkcja natywna Windows - przetestuj scenariusze
  brzegowe (awaria przeglądarki, wielokrotne uruchamianie/kończenie kiosku)
  przed realnym użyciem z klientami.
