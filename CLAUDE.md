# CLAUDE.md — PictureCleaner

Android-приложение: находит в мессенджерных папках сгенерированные картинки
(открытки «Доброе утро», AI-арт, мемы), переносит их в карантин для просмотра и
удаляет разом. Настоящие фото и документы не трогает.

## Как работает

- Источники (`files/Sources.kt`): `Android/media/com.whatsapp/.../WhatsApp Images`,
  `Pictures/Viber`, `Pictures/Telegram`, `Pictures/ChatGPT`.
  `DCIM/Camera` и скриншоты не сканируются намеренно.
- Исключены (`Sources.isExcluded`): `WhatsApp Images/Private` — медиа
  заблокированных чатов — и `WhatsApp Images/Sent` — отправленное самим
  владельцем. Если запись оттуда есть в базе, `purgeExcluded` при запуске
  возвращает файл на место и стирает запись, метку и кэш миниатюр: карантин
  виден в Галерее, а закрытый чат там появляться не должен.
- Классификатор: MobileCLIP2-S0, эмбеддинг каждой картинки хранится в
  `images.embedding` (512 float, little-endian). Старт — zero-shot по трём
  группам промптов (photo / generated / document): балл = вероятность
  `generated`, плюс `document`, если включена настройка «скриншоты, флаеры,
  меню, документы — тоже мусор».
- Дообучение на телефоне (`Ranker`, `LogisticRegression`): «Удалить» пишет
  метку junk, «Вернуть» — keep в таблицу `labels`; метка переживает удаление
  файла. После каждого решения регрессия переобучается и все баллы
  пересчитываются по сохранённым эмбеддингам без прогона CLIP. Вес персональной
  модели n/(n+100) от числа меток, включается от 10 меток каждого класса.
- Эмбеддинг досчитывается перед удалением (`withEmbeddings`): после удаления
  файла метку уже не получить.
- «Поделиться → В мусор (Чистка)» из Галереи (`ShareActivity`): файл по
  `MediaStore.DATA` уходит в карантин и сразу получает метку junk. Исключённые
  папки не принимаются. Обучение и пересчёт общие с главным экраном —
  `classify/Scoring` (один на процесс, под замком).
- Порог по умолчанию 0.5 (подобран на выборке 264 картинок).
- Карантин — физический перенос в `Pictures/PictureCleaner` (виден в Галерее
  альбомом). Исходный путь хранится в Room, «Вернуть» кладёт файл обратно и
  помечает `KEPT` — повторное сканирование такие файлы не трогает.
- Удаление — насовсем (`File.delete` + MediaScanner), без корзины Samsung.
- Нужно разрешение `MANAGE_EXTERNAL_STORAGE`: без него в `Android/media` не
  переносить. Приложение личное, в Play не публикуется.

## Модель

`app/src/main/assets/clip_visual.onnx` и `clip_text.json` собираются скриптом и
лежат в git, чтобы сборка APK не требовала torch:

```bash
cd calibration
uv venv .venv -p 3.10
VIRTUAL_ENV=.venv uv pip install --index-strategy unsafe-best-match \
  --extra-index-url https://download.pytorch.org/whl/cpu \
  torch torchvision open_clip_torch onnx onnxruntime pillow numpy
.venv/bin/python score.py data out          # калибровка: баллы + контактные листы
.venv/bin/python export.py ../app/src/main/assets data/<любая картинка>
```

Промпты живут в `calibration/score.py`; `export.py` берёт их оттуда. Изменил
промпты — перезапусти оба скрипта. `ClipScorer` (Kotlin) повторяет формулу
`score.py`: logit scale 100, softmax по всем промптам, сумма по группе.
Вход модели: RGB 0..1 без нормализации, короткая сторона 256, центр-кроп 256.

`calibration/data` и `out` — личные картинки с телефона, в git не попадают.

## Сборка и установка

```bash
export JAVA_HOME=<path-to-jdk-17>
./gradlew testDebugUnitTest assembleDebug
```

Установка через `adb.exe` Windows, как в `../pumpshot/CLAUDE.md`:

```bash
ADBW=/mnt/c/path/to/platform-tools/adb.exe
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Temp/picturecleaner-debug.apk
"$ADBW" install -r 'C:\Temp\picturecleaner-debug.apk'
"$ADBW" shell appops set de.drepp.picturecleaner MANAGE_EXTERNAL_STORAGE allow
```

В цикле `while read` вызывай `adb.exe` с `</dev/null` — иначе он съедает stdin.
