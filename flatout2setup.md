# 🚀 РУКОВОДСТВО РАЗРАБОТЧИКА RETRONEXUS: Добавление игр

Вот полное, пошаговое руководство (Pipeline) для добавления любой новой игры в маркетплейс RetroNexus. Эту инструкцию можно использовать как официальный стандарт разработчика (SOP). 

> **Цель:** Гарантировать, что любая игра будет работать по принципу «Plug-and-Play» (Скачал ➡️ Нажал ➡️ Играешь), без вылетов, ручной настройки и багов.

---

## 🛑 БАЗОВОЕ ПРАВИЛО RETRONEXUS
**Никаких инсталляторов на устройствах пользователей.** 

Телефоны не должны заниматься распаковкой архивов, установкой библиотек или модов. Вся грязная работа делается заранее на ПК (Ubuntu). В S3 загружается только на 100% готовая, "портативная" папка игры.

---

## 🔍 ФАЗА 1: Поиск и отбор игры

### Ищем правильный формат:
Скачиваем игры с пометками **Portable**, **Unpacked**, **No-Steam** или **GOG**.

### Чего избегать:
*   **ISO-образов** (нужно монтировать).
*   Игр с неотключенным **GFWL** (Games for Windows Live) или **Denuvo**.
*   Старых Steam-кэшей (файлы `.gcf`).

---

## 💻 ФАЗА 2: Подготовка на ПК (Ubuntu + Wine)

Если игра скачана в виде репака (например, `setup.exe`):

1.  **Установка:** Запускаем инсталлятор на Ubuntu через Wine: `wine setup.exe` (для старых игр используем префикс `WINEARCH=win32`).
2.  **Завершение:** Дожидаемся окончания установки на ПК.
3.  **Локация:** Переходим в папку `~/.wine/drive_c/Games/Имя_Игры`.
4.  **Очистка:** Это наша "рабочая директория". Сам инсталлятор (`setup.exe`) можно удалить.

---

## 🛠️ ФАЗА 3: Оптимизация (RetroNexus Tweaks)

Это самый важный этап для стабильной работы на эмуляторе (Winlator Glibc-branch).

### 🖥️ Widescreen Fix (Адаптация под 1280x720)

> [!CAUTION]
> **ЗАПРЕЩЕНО:** Использовать инъекции через `dinput8.dll`, `dsound.dll` или `.asi` скрипты (по умолчанию). Они часто вызывают ошибку `EXCEPTION_ACCESS_VIOLATION` в Box64. Исключения делаются только для конкретных игр с настройкой `overrides` в конфиге Winlator.

*   ✅ **МЕТОД 1 (UniWS):** Пропатчить сам исполняемый файл `.exe` через утилиту UniWS на ПК (самый безопасный метод).
*   ✅ **МЕТОД 2 (Аргументы):** Использовать параметры запуска (например, `-width 1280 -height 720` для движка Source/Half-Life).
*   ✅ **МЕТОД 3 (Реестр):** Создать файл `widescreen.reg` в папке игры (как для Warcraft 3).

### 🧹 Очистка от мусора
*   Удаляем файлы деинсталляторов (`unins000.exe`, `unins000.dat`).
*   Удаляем папки `DirectX`, `VCRedist`, `Soundtrack`, неиспользуемые языки озвучки.

### 💿 No-CD
Убеждаемся, что оригинальный `.exe` заменен на взломанный (кряк), чтобы игра не просила диск.

---

## 📦 ФАЗА 4: Создание архива для S3

1.  Открываем папку с готовой игрой.
2.  Выделяем **все файлы внутри папки** (не саму папку, а её содержимое).
3.  Создаем стандартный **ZIP-архив** (например, `game_retronexus.zip`).
4.  Загружаем этот `.zip` и картинку (логотип) в бакет Cloudflare R2 / AWS S3. 
5.  Получаем прямые ссылки (URL).

---

## 🗄️ ФАЗА 5: Добавление в базу данных (Supabase)

Используем стандартный SQL-запрос для интеграции игры в приложение. Самое главное здесь — поле `config_preset`, которое заменяет ручную настройку контейнера Winlator.

### Универсальный SQL-шаблон:

```sql
INSERT INTO public.games (id, title, description, thumbnail_url, download_url, config_preset)
VALUES (
  gen_random_uuid(),
  'Название Игры',
  'Крутое описание игры...',
  'https://ТВОЙ_S3_BUCKET/game_logo.jpg',
  'https://ТВОЙ_S3_BUCKET/game_retronexus.zip',
  '{
    "exe": "Game.exe",
    "arguments": "-windowed -width 1280", 
    "graphics": {
      "driver": "turnip", 
      "dxWrapper": "dxvk", 
      "screenSize": "1280x720"
    },
    "wine": {
      "version": "win7",
      "audio": "alsa"
    }
  }'::jsonb
);
```

> **Примечание:** Если игра очень старая (до 2005 года), меняем `dxWrapper` на `wined3d`, а `version` на `winxp`.

---

## ✅ ФАЗА 6: Чек-лист проверки (QA) на телефоне

После добавления в базу скачиваем игру через приложение RetroNexus и проверяем 4 пункта:

1.  **Запуск:** Открывается ли игра без лаунчеров и меню настроек?
2.  **Экран:** Занимает ли игра весь экран телефона (16:9) без черных полос?
3.  **Звук:** Нет ли хрипов или крашей?
4.  **Управление:** Подходит ли стандартный геймпад-пресет?

---

# 📖 ПРИМЕРЫ УСПЕШНЫХ ПОРТОВ

## 🏎️ Need for Speed: Most Wanted (2005)
*   **Установка:** Используем репак R.G. Mechanics (Русская версия). Устанавливаем в чистый префикс `WINEARCH=win32`.
*   **Очистка:** Удаляем папку `Redist`, деинсталляторы и лишние языки из `LANGUAGES`.
*   **Widescreen:** В идеале применять патч **UniWS** к исполняемому файлу, чтобы не использовать `dinput8.dll`, который может приводить к крашам в Box64.

---

## 💥 FlatOut 2

### Статус: Box64 0.4.2 ИНТЕГРАЦИЯ (тестирование)

FlatOut 2 вылетает с `EXCEPTION_ACCESS_VIOLATION` (code=c0000005) в Box64 JIT-коде. Адрес краша стабильно `7Axx26A9` — смещение `26A9` фиксировано, база `7Axx` меняется от запуска к запуску (ASLR). Это указывает на конкретную инструкцию внутри JIT-скомпилированного кода, а не на случайную memory corruption.

### Хронология краша (из logcat)

```
RPC_S_SERVER_UNAVAILABLE exception (code=6ba)        ← RpcSs сервис (решено)
init_peb starting FlatOut2.exe in experimental wow64 mode
DXVK: No state cache file found
D3D9DeviceEx::SetRenderState: Unhandled render state 26
Compiling shader FS_13f371a6...                      ← Последнее действие перед крашем
EXCEPTION_ACCESS_VIOLATION (code=c0000005) on thread 017c
wine: Unhandled page fault on read access to 7A9126A9 at address 7A9126A9
[BOX64] winedbg detected, not launching it!
```

### Корневые проблемы

**Проблема 1 (решена): RPC_S_SERVER_UNAVAILABLE**
Wine-сервис `RpcSs` не запускался вовремя. FlatOut 2 под win7 использует COM/DCOM для аудио (через `mmdevapi` → `IAudioSessionControl`). Решено переходом на `winxp` + ранней записью в реестр.

**Проблема 2 (решена): IndirectSound несовместим с winxp**
Нативный `dsound.dll` (IndirectSound) требует XAudio2/mmdevapi для перечисления аудиоустройств. Под winxp не работает. Решено удалением IndirectSound и переходом на Wine builtin dsound (`dsound=b`).

**Проблема 3 (НЕ РЕШЕНА): Box64 dynarec crash в wow64**
Краш происходит в JIT-скомпилированном коде (адрес `7Axx26A9`) сразу после компиляции шейдеров DXVK. Крашится не главный поток, а вторичный (номер потока меняется каждый запуск: `0194`, `017c`, `0184` и т.д.). Ни STABILITY preset Box64, ни отключение esync/LAA, ни отключение DXVK state cache/async не помогают.

**Текущая гипотеза (уточнено по тестам):** это race condition / баг WOW64-трансляции в Box64/Wine, который проявляется при компиляции шейдера DXVK. Полное отключение dynarec (`BOX64_DYNAREC=0`) **не устраняет** проблему (краш сохраняется, адрес меняется на `7Axx2620/7Axx26A9`). Единственный параметр, который стабильно убирал краш — `BOX64_DYNAREC_LOG=2`, но он делает запуск/работу игры слишком медленными (проходит меню, затем зависание/вечная загрузка).

### Все фиксы в коде (DownloadEngine.java)

| # | Фикс | Что делает | Статус |
|---|-------|-----------|--------|
| 1 | **Compilation fix: `exeFile`** | Ссылка на `exeFile` заменена на `getGameInstallDir(game)`. | Применён |
| 2 | **Compilation fix: `deleteRecursively`** | Заменен на `FileUtils.delete()`. | Применён |
| 3 | **DLL overrides не сохранялись** | Добавлен повторный `container.setEnvVars()` после финальных `env.put()`. | Применён |
| 4 | **Удаление dinput8.dll + scripts/** | Автоматически удаляет ThirteenAG loader из папки игры. | Применён |
| 5 | **startupSelection = NORMAL** | `startupSelection=0` + `WineUtils.changeServicesStatus(false)` + extra. | Применён |
| 6 | **winxp вместо win7** | `WineUtils.setWinVersion(container, xpIdx)` — обходит mmdevapi/COM. | Применён |
| 7 | **mmdevapi=b (builtin)** | Не отключает mmdevapi полностью (иначе null pointer), но форсит Wine builtin. | Применён |
| 8 | **Ранняя запись RpcSs в реестр** | `WineUtils.changeServicesStatus(false)` в `ensureContainer()` до `applyPreset()`. | Применён |
| 9 | **Удаление IndirectSound** | Удаляет `dsound.dll` + `dsound.ini` из папки игры (несовместимы с winxp). | Применён |
| 10 | **Переименование video/** | `video/` → `video_disabled/` для пропуска интро-видео (WMV крашит Box64). | Применён |
| 11 | **Box64 STABILITY preset** | `SAFEFLAGS=2, BIGBLOCK=0, STRONGMEM=2, CALLRET=0, NATIVEFLAGS=0`. | Применён, не помог |
| 12 | **WINEESYNC=0** | Отключает esync (eventfd-синхронизация), потенциальные проблемы с wow64. | Применён, не помог |
| 13 | **WINE_LARGE_ADDRESS_AWARE=0** | Запрещает маппинг 32-bit exe в высокую память. | Применён, не помог |
| 14 | **DXVK_STATE_CACHE=disable** | Отключает pre-compilation шейдеров из state cache. | Применён, не помог |
| 15 | **DXVK_ASYNC=0, NUM_COMPILER_THREADS=1** | Отключает async shader compilation, ограничивает потоки. | Применён, не помог |
| 16 | **BOX64_DYNAREC=0** | Полностью отключает JIT dynarec (чистый интерпретатор). ДИАГНОСТИКА. | Применён, **краш остаётся** |
| 17 | **BOX64_DYNAREC_LOG=2** | Heisenbug: логирование добавляет задержки и убирает race/crash. | **Проходит меню**, но **слишком медленно / зависает** |
| 18 | **BOX64_DYNAREC_STRONGMEM=3 + WAIT=1** | Лёгкая альтернатива `LOG=2` (барьеры памяти + ожидание блоков). | Применён, **краш остаётся** |
| 19 | **DXVK_CONFIG_FILE + per-game `dxvk.conf`** | Правильное ограничение потоков DXVK через `dxvk.numCompilerThreads` (а не через `DXVK_NUM_COMPILER_THREADS`). | Применён, **краш остаётся** |
| 20 | **Предсгенерированный `Savegame/device.cfg` через `-setup`** | Идея: убрать “первый автодетект” D3D/видео, поставляя готовые конфиги. | Протестировано, **не помогло** |
| 21 | **dgVoodoo2 (D3D9→D3D11 обход)** | Подмена `D3D9.dll` на dgVoodoo2 v2.87.1 x86 (D3D9→D3D11→DXVK→Vulkan). Тестировано v3 (`bestavailable`) и v4 (`d3d11_fl10_0` + `DisableD3DTnLDevice=true`). | Протестировано, **краш изменился** (`C51769B4 at 7B834F7A`), но **не ушёл** |
| 22 | **`BOX64_MMAP32=1`** | Принудительно ограничивает mmap ниже 4 ГБ для WOW64-совместимости. Гипотеза: усечение 64-бит указателей. | Протестировано, **краш изменился** (`5A70F426 at 7B834F7A`), но **не ушёл** |

### Финальный блок env vars (текущий)

```
WINEDLLOVERRIDES=wineandroid.drv=d;dsound=b;dinput8=b;mmdevapi=b;avrt=b;d3d9=n,b
WINEESYNC=0
WINE_LARGE_ADDRESS_AWARE=0
BOX64_DYNAREC_STRONGMEM=3
BOX64_DYNAREC_WAIT=1
DXVK_CONFIG_FILE=F:\\RetroNexus\\Games\\FlatOut_2\\dxvk.conf
```

`dxvk.conf` (создаётся в папке игры автоматически):

```ini
[FlatOut2.exe]
dxvk.numCompilerThreads = 1
dxvk.enableAsync = False
```

### Последний результат (22 Apr 2026, вечер)

Протестировано три новых подхода подряд:

**dgVoodoo2 (v3/v4 ZIP):** Подмена `D3D9.dll` на dgVoodoo2 x86, чтобы путь стал D3D9→D3D11→DXVK→Vulkan. Краш изменился (новый адрес `C51769B4 at 7B834F7A` — это Wine `ntdll.dll`), но не ушёл.

**`BOX64_MMAP32=1` (Supabase envVars):** Принудительный 32-бит mmap. Краш снова изменился (`5A70F426 at 7B834F7A`), адрес `7B83xxxx` = Wine ntdll. Это подтверждает, что проблема **не в усечении указателей WOW64** (иначе MMAP32 бы помогло), а глубже — в самом Wine WOW64 thunking или Box64.

**Ключевое наблюдение:** Все три подхода (dgVoodoo2, MMAP32, стандартный DXVK) крашатся по адресу `7B83xxxx` (Wine ntdll.dll). Это значит, что проблема **не специфична для DXVK D3D9**, а в том, как Box64 0.4.0 транслирует WOW64-код Wine.

```
# dgVoodoo2 (v3):
wine: Unhandled page fault on read access to C51769B4 at address 7B834F7A
# BOX64_MMAP32=1 (v4):
wine: Unhandled page fault on read access to 5A70F426 at address 7B834F7A
# Стандартный DXVK (ранее):
wine: Unhandled page fault on read access to 7A8226A9 at address 7A8226A9
```

### Вывод по итогам всех попыток

Все env var фиксы, dgVoodoo2, MMAP32, device.cfg — **исчерпаны**. Единственный реальный путь:

1. **Box64 v0.4.2** — содержит коммит `#3083` с явным фиксом для FlatOut/FlatOut2 (`native_fprem/native_fprem1`). Требует установки нового компонента Box64 на устройство.
2. **Другая сборка Wine WOW64** (8.x / 9.x) — если Box64 0.4.2 не поможет.

### Box64 0.4.2 интеграция (22 Apr 2026)

**Попытка 1: Bionic WCP (ПРОВАЛ)**
Скачан `box64-bionic-0.4.2.wcp` из WinlatorWCPHub. Бинарник оказался собран под Android Bionic (`/system/bin/linker64`), а наш rootfs — glibc. Ошибка: `CANNOT LINK EXECUTABLE: has bad ELF magic: 2f2a2047`. Все игры перестали запускаться.

**Попытка 2: Кросс-компиляция из исходников (УСПЕХ сборки)**
- Установлен `gcc-aarch64-linux-gnu` на Ubuntu.
- Клонирован `ptitSeb/box64` tag `v0.4.2`.
- Собран с флагами: `-DARM64=ON -DWINLATOR_GLIBC=ON -DNOLOADADDR=ON`.
- Linker flags: `--dynamic-linker=/data/data/com.winlator/files/rootfs/lib/ld-linux-aarch64.so.1 -rpath=/data/data/com.winlator/files/rootfs/lib`.
- Stripped binary: 25 MB (vs 26 MB у 0.4.0). Структура ELF идентична 0.4.0 (те же NEEDED libs, тот же interpreter).
- Файл `box64-0.4.2.tzst` добавлен в assets, `DefaultVersion.BOX64 = "0.4.2"`.

**Результат на устройстве:**
- ✅ NFS Most Wanted — работает (Box64 0.4.2 glibc совместим с rootfs).
- ❌ FlatOut 2 — **новый краш**, отличный от старого:

```
# Старый краш (Box64 0.4.0):
wine: Unhandled page fault on read access to 7A9126A9 at address 7A9126A9

# Новый краш (Box64 0.4.2):
wine: Unhandled page fault on write access to 01B5E02B at address 01B5E02E (thread 0128)
```

**Анализ нового краша:**
- Адрес `01B5E02B` — **низкая память** (собственное адресное пространство игры), не Wine WOW64 range `7Axx`.
- Операция **write** (не read). Другой тип ошибки.
- Краш через ~3 сек после `init_peb`, **до** компиляции шейдеров и инициализации аудио.
- Адрес **детерминированный** (одинаковый при каждом запуске) → конкретная инструкция, не случайная порча памяти.
- Старый краш `7Axx26A9` (fprem/WOW64 race) **больше не появляется** → коммит #3083 сработал.
- Краш `01B5E02B` **не связан** с MMAP32, не связан с конкретной версией Box64 (v0.4.2 tag и latest main дают идентичный краш).
- **Гипотеза**: проблема в самом бинарнике `FlatOut2.exe` — возможно UniWS widescreen патч модифицировал инструкции, которые Box64 0.4.2 обрабатывает иначе, чем 0.4.0. На 0.4.0 эти инструкции случайно "проходили", а краш происходил позже (fprem). На 0.4.2 fprem исправлен, и теперь видна вторая проблема.

**Дополнительные тесты (все дали `01B5E02B`):**
1. Box64 v0.4.2 tag + `BOX64_MMAP32=1` → краш `01B5E02B`
2. Box64 v0.4.2 tag **без** `BOX64_MMAP32=1` → краш `01B5E02B`
3. Box64 latest main (post-0.4.2, включает IMUL fix #3795 + WOW64 EAX fix #3782) → краш `01B5E02B`

**Вывод:** адрес `01B5E02B` детерминирован и не зависит от Box64 env vars или версии (v0.4.2+). Проблема в **exe файле** или в том, как Wine WOW64 загружает этот конкретный бинарник.

**Дополнительные фиксы в коде (DownloadEngine.java):**
- Удалён `d3d9=n,b` из WINEDLLOVERRIDES.
- Добавлена автоочистка dgVoodoo2 (`D3D9.dll`, `D3DImm.dll`, `dgVoodoo.conf`).
- Удалены `BOX64_DYNAREC_LOG=2`, `BOX64_TRACE_FILE`, `dxvk.conf` limiter.
- Добавлен `BOX64_DYNAREC_WAIT=1`.

**Текущие env vars:**
```
WINEDLLOVERRIDES=wineandroid.drv=d;dsound=b;dinput8=b;mmdevapi=b;avrt=b
WINEESYNC=0
WINE_LARGE_ADDRESS_AWARE=0
BOX64_DYNAREC_WAIT=1
```

### Критическая находка: Box64 0.4.2 ломает NFS Most Wanted (22 Apr 2026)

**Контекст:** после интеграции Box64 0.4.2 (glibc) NFS Most Wanted первоначально запустилась, но после нескольких перезапусков / переустановок FlatOut 2 стала **крашиться с тем же паттерном**, что и FlatOut 2:

```
00d8:warn:seh:dispatch_exception "EZ Wheel Wrapper v4.60.001\n"
wine: Unhandled page fault on write access to 05E76554 at address 00819654 (thread 0134)
```

**Ключевые наблюдения:**
- `EZ Wheel Wrapper v4.60.001` — компонент **FlatOut 2** (dinput8.dll wrapper), его не должно быть в NFS MW.
- Адрес `00819654` — **одинаковый** в обоих играх (FlatOut 2 показывал `05F76554`/`05E76554` с тем же кодом `00819654`).
- Логи NFS MW показывают `dinput8=n,b;d3d9=n,b` в WINEDLLOVERRIDES — эти настройки из **FlatOut 2 конфига**, а не NFS.
- Причина: **контаминация контейнеров/Wine prefix** — настройки и DLL из FlatOut 2 утекли в NFS MW.

**Решение:**
- ✅ **Откат на Box64 0.4.0** → NFS Most Wanted снова работает нормально.
- `DefaultVersion.BOX64` возвращён на `"0.4.0"`.

**Вывод:**
- Box64 0.4.2 **ломает обе игры** — FlatOut 2 (новый краш `01B5E02B`) и NFS MW (контаминированный краш через утёкшие DLL/настройки).
- Box64 0.4.0 — **единственная рабочая версия** для NFS MW на данный момент.
- FlatOut 2 не работает ни на 0.4.0 (краш `7Axx26A9`), ни на 0.4.2 (краш `01B5E02B`).

**Следующие шаги:**
1. **Попробовать чистый (непатченный) FlatOut2.exe v1.2** без UniWS widescreen патча — проверить, является ли патч причиной краша `01B5E02B`.
2. **Попробовать другую сборку exe** — например, GOG или Steam 2014 оригинал.
3. **Попробовать Box64 preset INTERMEDIATE или PERFORMANCE** вместо STABILITY — другие dynarec настройки могут обойти проблемную инструкцию.
4. **Wine WOW64 8.x/9.x** — если проблема в Wine loader, а не Box64.
5. **Удалить оба контейнера (FlatOut 2 + NFS MW) и скачать заново** — чтобы гарантировать чистые Wine prefix без контаминации.

### Что не работает на устройстве

- **WineD3D**: Нет `libEGL.so.1` в rootfs → WineD3D не может создать GL-контекст. На данном устройстве только DXVK (Vulkan) работает.
- **mmdevapi= (disabled)**: Вызывает null pointer crash — игра ожидает валидный COM-объект от `CoCreateInstance`.
- **dsound=n,b с IndirectSound**: IndirectSound не может перечислить аудиоустройства через XAudio2/mmdevapi под winxp.
- **Только `BOX64_DYNAREC_LOG=2` убирает краш**, но цена — непригодная скорость (прохождение меню без нормального старта игры).

### Подготовка папки игры на ПК

| Шаг | Зачем |
|-----|--------|
| **No-CD v1.2** (US/EU fixed exe) | Иначе игра требует диск. |
| **НЕ класть IndirectSound** | `dsound.dll` + `dsound.ini` автоматически удаляются кодом, но лучше не класть в архив. |
| **НЕ класть dinput8.dll + scripts/** | ThirteenAG loader автоматически удаляется. Использовать UniWS патч вместо этого. |
| **Папку video/ можно оставить** | Код автоматически переименует в `video_disabled/`. |
| **Файл `filesystem`** | Окончания строк `\r\n`, без пустых строк в конце, без пробелов в именах `.bfs`. |
| **Widescreen** | Использовать **UniWS** патч на `FlatOut2.exe`. |

### Эксперимент (22 Apr 2026): `-setup` → `Savegame/device.cfg` в архиве

**Идея:** сгенерировать конфиги на ПК (через `wine FlatOut2.exe -setup`) и положить папку `Savegame/` в ZIP, чтобы игра на Android пропустила проблемный “первый автодетект” (как потенциальную причину 7Axx26A9).

**Что сделали:**
- На ПК в папке игры запущено `wine FlatOut2.exe -setup`, выставлены “эмулятор-safe” параметры (AA/anisotropy/triple buffering/post-processing выключены, 1280×720).
- Проверено, что появились/обновились файлы: `Savegame/device.cfg` и `Savegame/options.cfg` (у `device.cfg` обновился timestamp).
- Собран новый архив с корнем на уровне `FlatOut2.exe`: `flatout2_retronexus_rus_v2.zip` (включает `Savegame/`).

**Наблюдение на устройстве:**
- При запуске действительно **появляется окно Setup** (меню настроек).
- После этого игра **всё равно зависает/падает** с тем же паттерном Box64 WOW64:
  - `wine: Unhandled page fault on read access to 7Axx26A9 at address 7Axx26A9`

**Технический вывод (по содержимому файла):**
- `Savegame/device.cfg` — **бинарный** файл, в котором явно встречается строка `Primary Sound Driver` (т.е. он в основном фиксирует аудио/устройства, а не гарантированно “запирает” видеодетект).
- Этот подход **не устраняет** crash `7Axx26A9`, значит причина остаётся на уровне **Box64 WOW64 / JIT / timing** (а не только “нет конфигов на первый запуск”).

### Текущий `config_preset` для Supabase

```json
{
  "exe": "FlatOut2.exe",
  "graphics": {
    "driver": "turnip",
    "dxwrapper": "dxvk",
    "screenSize": "1280x720",
    "winVersion": "winxp",
    "audioDriver": "alsa"
  },
  "performance": {
    "driver": "turnip",
    "dxwrapper": "dxvk",
    "screenSize": "1024x576",
    "winVersion": "winxp",
    "audioDriver": "alsa"
  },
  "enableDinput8Override": false
}
```

> **Примечание:** Все env vars, Box64 preset, удаление DLL и переименование video/ выполняются автоматически в `DownloadEngine.applyPreset()` при обнаружении FlatOut 2. Config preset должен содержать `dxvk` (не `wined3d` — нет libEGL на устройстве).

### Следующие шаги

1. **Попробовать без `BOX64_MMAP32=1`** — MMAP32 меняет виртуальный layout памяти, возможно адрес `01B5E02B` попадает в protected region из-за этого.
2. **Попробовать preset INTERMEDIATE** вместо STABILITY — другие настройки dynarec могут обходить баг.
3. **Попробовать другой exe** — чистый v1.2 без widescreen патча UniWS.
4. **Собрать Box64 из `main` branch** (latest HEAD) — могут быть дополнительные фиксы поверх v0.4.2.
5. **Попробовать Wine WOW64 8.x/9.x** — если проблема в Wine thunking, а не Box64.

### Что скачать и какие версии (матрица тестирования)

Цель — быстро понять, **в какой части стека** (Box64 или Wine WOW64) лежит регресс, а не «перебирать всё подряд».

#### Box64 (минимальный набор)

- **Box64 `0.4.0`** — baseline (текущая основная версия в RetroNexus/Winlator).
- **Box64 `0.3.6`** — старая стабильная линия (часто ведёт себя иначе в WOW64/race сценариях).

#### Wine (только WOW64 сборки)

Нужны именно **WOW64 Wine** сборки (32-bit Windows app внутри 64-bit Wine без box86).

- **Wine WOW64 `8.x` (ориентир: `8.12`)**
- **Wine WOW64 `9.x`**
- **Wine WOW64 `10.x`** (baseline)

Источник: релизы `Kron4ek/Wine-Builds` (архивы с суффиксом `wow64`).

#### Рекомендуемый порядок тестов

1. Box64 `0.4.0` + Wine `10.x` (baseline / воспроизведение).
2. Box64 `0.3.6` + Wine `10.x` (если стало лучше — виноват Box64).
3. Box64 `0.4.0` + Wine `8.x` (WOW64) (если стало лучше — виноват Wine WOW64).
4. Box64 `0.4.0` + Wine `9.x` (WOW64).

### Как сделать это «доступным при установке» (инженерная заметка)

- **Box64 / DXVK**: ставятся как *installable components* (список версий + установка/удаление).
- **Wine**: импортируется как отдельная Wine-сборка, и контейнер должен создаваться из `container-pattern-<wineVersion>.tzst`.

> Сейчас на устройстве альтернативные runtime отсутствуют — переключать «версии» нечего, пока версии не будут установлены.


## Приложение: исследование / заметки (не все пункты подтверждены тестами)

### Короткая выжимка (что важно)

- **Наблюдение (подтверждено логами)**: падение происходит **сразу после** `Compiling shader FS_13f371a6...` с `EXCEPTION_ACCESS_VIOLATION` и адресом вида `7Axx26A9`.
- **Почему это похоже на гонку**: `BOX64_DYNAREC_LOG=2` убирает падение (Heisenbug), т.е. задержки/тайминги меняют поведение.
- **Почему это не “просто DXVK”**: ограничения DXVK через `DXVK_CONFIG_FILE` и `dxvk.numCompilerThreads=1` **не устраняют** падение.
- **Почему это не “просто dynarec”**: `BOX64_DYNAREC=0` (интерпретатор) **не устраняет** падение.
- **Вывод**: нужен фикс/обход на уровне **Box64 WOW64** (или другая версия Box64/Wine), т.к. текущая связка даёт стабильный краш.

### Полезные идеи из исследования (не подтверждены тестами)

- **Selective no-dynarec**: `BOX64_NODYNAREC=0x7a000000-0x7c000000` (может помочь, если 7Axx — проблемный диапазон).
- **Native XAudio2 компоненты** (`xapofx`, `x3daudio`) — иногда лечат аудио‑цепочку, но в нашем кейсе краш уже происходит на графике.