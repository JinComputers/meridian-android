# Правила R8 для релизной сборки.
#
# ГЛАВНОЕ, ЧТО НАДО ПОНИМАТЬ ПРО ЭТОТ ФАЙЛ. R8 переименовывает классы и
# методы и выбрасывает всё, к чему не видит обращений из кода. Он умеет
# читать байт-код Java и Kotlin — и НЕ УМЕЕТ читать вызовы через JNI.
#
# Наш движок — Go, собранный gomobile. Обмен с ним идёт целиком через
# JNI: нативный код ищет классы и методы ПО ИМЕНАМ, во время работы.
# Для R8 эти имена ниоткуда не используются, и без правил ниже он
# честно их переименует или выбросит.
#
# Сломается это не при сборке. Сборка пройдёт, приложение установится,
# и упадёт при первом же обращении к движку — ClassNotFoundException
# или NoSuchMethodError в момент подключения.

# ----------------------------------------------------------------------
# Обвязка gomobile
# ----------------------------------------------------------------------

# go.Seq — мост между Java и Go. Держит таблицы объектов, которые
# нативная сторона адресует по именам полей и методов, и вызывает
# обратные вызовы через отражение. Трогать нельзя ничего.
-keep class go.** { *; }

# Сгенерированные классы нашего движка: Engine, Ladder, интерфейсы
# Logger, Protector, NetworkGuard, StateListener, CaptchaSolver и
# внутренние proxy-классы вида Engine$proxyCaptchaSolver.
#
# Proxy-классы — это то место, где обратный вызов из Go входит в Java.
# Их имена нативная сторона знает наизусть.
-keep class engine.** { *; }

# Наши реализации интерфейсов движка — анонимные объекты в
# MeridianVpnService, HashScreen и CaptchaGate. Имена их методов обязаны
# совпадать с именами в интерфейсах, которые мы сохранили выше.
-keepclassmembers class * implements engine.Logger { *; }
-keepclassmembers class * implements engine.Protector { *; }
-keepclassmembers class * implements engine.NetworkGuard { *; }
-keepclassmembers class * implements engine.StateListener { *; }
-keepclassmembers class * implements engine.CaptchaSolver { *; }

# Любой нативный метод и класс, в котором он объявлен. Есть в
# умолчаниях AGP, но повторено намеренно: умолчания меняются между
# версиями, а цена потери — падение при подключении.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ----------------------------------------------------------------------
# Android-стороны, до которых R8 не дотягивается
# ----------------------------------------------------------------------

# Компоненты, которые создаёт система по имени из манифеста. AGP
# обычно их сохраняет сам, но манифест мы правим руками, и полагаться
# на догадку не будем.
-keep class org.meridianvpn.app.MeridianVpnService { *; }
-keep class org.meridianvpn.app.MainActivity { *; }
-keep class org.meridianvpn.app.CaptchaActivity { *; }
-keep class org.meridianvpn.app.TrialAlarm$Receiver { *; }

# WebView экрана капчи. JavaScript-интерфейсов мы не заводим, но
# методы WebViewClient вызываются системой.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}

# ----------------------------------------------------------------------
# Что НЕ защищаем намеренно
# ----------------------------------------------------------------------
#
# Наш собственный код, кроме перечисленного выше, переименовывать
# можно и нужно: он вызывается только изнутри, и R8 видит все связи.
#
# Compose, Kotlin и корутины приходят со своими правилами внутри
# библиотек (consumer rules) — дублировать их здесь значило бы
# однажды разойтись с ними.

# Строки исключений оставляем читаемыми: без этого записка о падении,
# которую мы кладём в CrashLog, превращается в набор букв.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
