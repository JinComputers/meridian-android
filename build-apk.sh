#!/bin/sh
# Сборка отладочного APK с поднятием номера версии.
#
# Зачем скрипт: отладочные сборки приходилось ставить с УДАЛЕНИЕМ
# старой, а удаление стирает SharedPreferences — то есть пул хешей,
# память пути, режим транспорта и хвост лога. Ровно то, что мы
# отлаживаем. С растущим versionCode APK ставится поверх, и накопленное
# переживает установку.
#
# Номер лежит в app/version.properties и виден на экране приложения.
#
# AAR этот скрипт НЕ собирает: движок меняется реже, у него свой
# engine/build-aar.sh со своим обязательным флагом компоновщика.

set -e
cd "$(dirname "$0")"

VF=app/version.properties
CUR=$(sed -n 's/^code=//p' "$VF" 2>/dev/null || echo 0)
[ -n "$CUR" ] || CUR=0
NEXT=$((CUR + 1))
echo "code=$NEXT" > "$VF"

export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bioto/AppData/Local/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"

./gradlew.bat assembleDirectDebug

echo "собрано: версия 0.1.$NEXT, app/build/outputs/apk/direct/debug/app-direct-debug.apk"
