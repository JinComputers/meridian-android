# Запасной вариант: тип службы `systemExempted`

Правка **готова и проверена, но НЕ применена**. Применять — только если
ревью Play придерётся к обоснованию `specialUse`.

Проверено 26.08.2026 на дереве версии 0.1.61: манифест собирается,
`lintVital` проходит, APK и бандл выходят, в собранном пакете стоит
`FOREGROUND_SERVICE_SYSTEM_EXEMPTED`. После проверки всё возвращено
к `specialUse` байт в байт.

---

## Что менять

Три места, все в `app/src/main/AndroidManifest.xml`. Больше нигде
ничего не меняется: `startForeground` зовётся двухаргументной формой
(`MeridianVpnService.kt:231`), тип служба берёт из манифеста.

### 1. Разрешение, строка 10

```diff
-    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
```

### 2. Тип службы, строка 122

```diff
-            android:foregroundServiceType="specialUse"
+            android:foregroundServiceType="systemExempted"
```

### 3. Свойство подтипа — УДАЛИТЬ ЦЕЛИКОМ

Оно относится только к `specialUse`; при другом типе это мусор в
манифесте, на который ревью тоже смотрит.

```diff
-            <property
-                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
-                android:value="vpn" />
```

Одной командой, из корня дерева:

```bash
perl -i -0pe 's/android\.permission\.FOREGROUND_SERVICE_SPECIAL_USE/android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED/; s/android:foregroundServiceType="specialUse"/android:foregroundServiceType="systemExempted"/; s/\n            <property\n                android:name="android\.app\.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"\n                android:value="vpn" \/>//' app/src/main/AndroidManifest.xml
```

Комментарий в манифесте после применения надо переписать: он сейчас
объясняет, почему выбран `specialUse`, и станет враньём.

---

## ЧЕМ ЭТО ОПАСНО И ЧТО ПРОВЕРЯТЬ

Здесь и была причина не менять заранее.

У `specialUse` проверка идёт при **установке и на ревью**. У
`systemExempted` — **во время работы**: не подпадаешь под основание,
система бросает `ForegroundServiceTypeNotAllowedException`, то есть
**падение**, а не отказ.

Основание для VPN сформулировано как «настроен как VPN в системных
настройках». В какой именно момент система считает это выполненным —
после выдачи разрешения VPN или только когда туннель уже поднят — не
сказано нигде.

А `startForeground()` мы зовём **первой строкой подъёма**, до
`establish()`. Если система в этот момент нас VPN ещё не считает,
приложение будет падать при **каждом** подключении.

### Сценарий проверки, обязательный

Проверять на устройстве, не в эмуляторе, и именно в таком порядке:

1. **Удалить приложение полностью** — не обновить поверх. Разрешение
   VPN должно быть не выдано.
2. Установить сборку с `systemExempted`.
3. Открыть, ввести ключ, нажать подключение **первый раз**.

Вот здесь оно упадёт, если упадёт. Признак: приложение исчезает с
экрана сразу после нажатия, в `logcat` —
`ForegroundServiceTypeNotAllowedException`. Наш `CrashLog` записку об
этом положит.

4. Если первое подключение прошло — отключить, подключить ещё раз,
   перезагрузить телефон и подключиться после перезагрузки.

**Одного удачного подключения мало.** Опасен ровно первый заход после
чистой установки, и повторить его нельзя, не удалив приложение снова.

---

## Что это НЕ даёт

Play требует декларацию для **любого** типа службы и заявляет, что все
типы проходят ревью. Выигрыша «меньше вопросов на ревью» у
`systemExempted` нет — он только другой ответ на тот же вопрос.

Менять стоит, только если ревью прямо скажет, что `specialUse` их не
устраивает, и лучше со знанием, **что именно** не устроило.
