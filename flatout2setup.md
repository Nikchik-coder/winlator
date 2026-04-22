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

### Статус: НЕ РЕШЕНО (диагностика)

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

### Последний результат (22 Apr 2026)

Даже с `BOX64_DYNAREC_STRONGMEM=3` + `BOX64_DYNAREC_WAIT=1` и `DXVK_CONFIG_FILE` (пер-игровой `dxvk.conf`) краш сохраняется **на компиляции того же шейдера**:

```
Compiling shader FS_13f371a659423fd077cb793765c397f9b918fd14
EXCEPTION_ACCESS_VIOLATION (code=c0000005)
wine: Unhandled page fault on read access to 7A7626A9 at address 7A7626A9
```

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

1. **Факт**: На текущем стеке (Wine + Box64 WOW64 + DXVK) FlatOut 2 стабильно падает на shader compile с `7Axx26A9`.
2. **Единственный подтверждённый обход**: `BOX64_DYNAREC_LOG=2` (Heisenbug), но он не пригоден для продакшена из‑за производительности.
3. **Реальный путь решения**: обновление/замена Box64 (или Wine WOW64) на версию, где исправлен этот race/crash, либо отдельный per-app workaround на уровне Box64 (если появится поддержка лёгкого “yield/delay” без LOG-спама).

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