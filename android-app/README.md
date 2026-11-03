# DroidVault Android app

Первый Android-компаньон DroidVault.

## Возможности 0.1.0

- показывает сторонние установленные приложения;
- позволяет выбрать несколько приложений;
- сохраняет base APK и split APK в папку, выбранную пользователем через системный SAF picker;
- создаёт manifest.json с версиями и SHA-256.

## Ограничения

Это APK exporter, а не полный переносчик данных. Приватные данные других приложений, аккаунты, токены и Android Keystore не экспортируются. Для списка приложений используется QUERY_ALL_PACKAGES; распространение через Google Play требует отдельной проверки соответствия политике видимости пакетов.

## Сборка

Откройте корень репозитория в Android Studio либо запустите:

~~~bash
gradle :android-app:assembleDebug
~~~

APK: android-app/build/outputs/apk/debug/android-app-debug.apk
