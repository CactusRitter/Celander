# Traspaso de Celander (sesión Celander) — estado al 2026-11-18

Rama de trabajo: `ce/estilo-ce` (local, sin push; ya incluye el diseño común fusionado de `ce/diseno-ce`). Repo de César: `CactusRitter/Celander` (`origin`); `upstream` es de Jonathan, nunca push. Se publica UNA sola versión, **Celander 2.0.0**, con todo, a la vez que Cemail y Cenotes y solo cuando César/Ce Líder lo digan. Paquete final `com.celander.app`.

## Reglas de trabajo vigentes
- César solo decide dinero, publicar, datos de su cuenta real, OPPO y diseño no acordado; lo técnico lo decido yo y lo anoto aquí. Al cerrar cada bloque: `SendMessage` a «Ce Líder» (≤6 líneas: qué + commit, decisiones, no probado, qué se pide a César).
- Antes de diseñar algo: ver si otra app Ce ya lo resuelve (Cemail: Microsoft Graph, AppAuth, push) y copiarlo; subagente con resumen corto.
- Emulador SIEMPRE sin ventana. S26 (`RFGL107KERL`) y tableta (`R52Y806MWSP`) son de pruebas (instalar ahí sin pedir permiso; siempre `adb -s <serie>`); el OPPO nunca. Capturas con datos reales solo en `docs-ce/` (excluida de git).
- **Datos reales de César, no tocar**: el evento «Quedada con Gloria» (19:00–22:30, 18 nov, calendario «Eventos»). Para probar editar/borrar: evento propio de prueba (se crea y se borra), o el servidor falso de los tests.
- Textos solo en `values`, `values-es`, `values-de` (alemán con «du»). Sin compilaciones ni capturas innecesarias.

## Arquitectura de datos (hecho)
- Room v5. Tablas nuevas: `accounts`, `remote_calendars`, `event_links`, `exception_links` (patrón de enlaces como Google Tasks; sin FK en los enlaces = tombstones). `CalendarSource.ACCOUNT`: los eventos de una cuenta viven en `events`/`event_exceptions` como los locales; la UI solo lee Room. Migración 4→5 borra `device_calendar_prefs`.
- `account/CalendarProvider` (interfaz por tipo de cuenta) + `RemoteEvent` envuelve `ICalEvent`; conversión de series/excepciones con `IcsMapper`.
- `account/google`: `GoogleCalendarApi` (calendarList, events con syncToken, insert/patch/delete; 401→token nuevo, 410→recarga completa, 412→conflicto), `GoogleCalendarMapper`, `GoogleCalendarProvider`.
- `account/AccountSync`: pull (series con EXDATE, instancias sueltas, borrados, recarga completa) y push (create/update con etag, ocurrencias por PATCH de instancia `serie_yyyyMMddTHHmmssZ`, delete con tombstone). Conflicto con el servidor: **gana lo local** (aprobado por Ce Líder para 2.0.0). Nunca pisa cambios locales pendientes en el pull.
- `core/database/AccountTriggers`: triggers SQLite (se instalan en `onOpen`) marcan NEW/DIRTY/DELETED en `event_links` desde cualquier escritura (editor, split, deshacer).
- `account/auth`: `GoogleAuth` (AppAuth, scopes: openid email profile calendar.events calendar.calendarlist.readonly tasks), `TokenStore` (AES-GCM, clave Android Keystore, fuera de la copia de Android: `account_tokens.xml` excluido). Cliente OAuth Android en `android/local.properties` (`celander.oauth.google.clientId`, no se sube). La actividad de retorno de AppAuth necesita tema AppCompat (`Theme.Celander.SignInReturn`, si no la app se cierra al volver del navegador).
- `AccountRepository`/`AccountSyncScheduler`: envío a los ~3 s de un cambio local (WorkManager único con red), pull cada 60 s SOLO con la app visible, pull al abrir (≥30 s), periódico de 15 min en segundo plano (red). Widgets y recordatorios se refrescan solos (observan Room). Sin push de Google (calendar.watch) ni de Microsoft: exigirían servidor propio (contra la política de hoy). Sin «deslizar para refrescar» (César lo canceló).
- Google Tasks usa los tokens AppAuth (`task/google/AccountAuthorizer`); `play-services-auth` eliminado. Interruptor «Google Tasks» en la página de cada cuenta; quitar cuenta desconecta Tasks.
- Calendario del teléfono eliminado: sin `CalendarContract` ni permisos READ/WRITE_CALENDAR, SYNC_SETTINGS.
- UI: Ajustes › Cuentas (como Cemail): fila por cuenta, botón Añadir, página de la cuenta (cabecera, Sincronizar ahora / Iniciar sesión de nuevo, Google Tasks, Quitar). Gestionar calendarios muestra los calendarios de cada cuenta (editables según el rol; festivos = solo lectura).
- Widgets: sin sombra ni desenfoque, borde `widget_rim` (necesita `<solid>` transparente), deslizador de transparencia completo con suelo de opacidad 0,5.

## Medido / probado
- S26 con la cuenta real (evento de prueba propio, creado y borrado): Guardar → en Google en ~4,5 s; Eliminar → borrado en Google en ~4,5 s. Login, tokens, primera sync, calendarios y Tasks (interruptor) correctos. Migración Room 4→5 con la base real: bien.
- Tests: ~464; fallan solo `SettingsCorruptionTest` y 3 de `SettingsRepositoryTest` (error de DataStore al renombrar `.tmp` en Windows; ya fallaban en el commit original 6d0258d) y `GlassDialogTest` (solo en la suite completa; pasa sola).
- **No probado**: cambios hechos fuera (Google Calendar web) llegando a Celander en ≤60 s (se lo pide Ce Líder a César); WorkManager con la app cerrada durante horas; crear/editar/completar una tarea y verla en Google Tasks; series con ocurrencias movidas contra la cuenta real; widgets Mes/Tareas/Año/Agenda y fondos con mucho detalle (solo se vio el widget Día en emulador).

## Pendiente, EN ORDEN
1. **Revertir una ocurrencia ya enviada** (ahora no se envía): en `AccountSync.pushOne`, para cada `exception_links` CLEAN cuya excepción local ya no existe, reescribir esa instancia con los valores de la serie (`IcsMapper.toIcal(event, listOf(EventException(...)))` genera el override; añadir `status: confirmed` en `toJson` de ocurrencias) y borrar el enlace; añadir `deleteExceptionLink(id)` al DAO y un test en `AccountPushTest`.
2. **«Borrar todos los datos»** (Ajustes › Datos, `settings/DataReset.kt`) debe borrar también cuentas, tokens (`TokenStore.clear`), calendarios de cuenta y listas de Tasks; verificar (punto 8 de privacidad).
3. **Microsoft** (Graph, delta): partir de `Cemail/.../data/auth/MicrosoftAuth.kt` y `data/graph/GraphApi.kt` (subagente con resumen de 5 líneas). Registro Entra ya hecho por Ce Líder: ID de cliente `6e17cfa1-6713-4308-bd81-0a7de0e97b0c`, redirección `com.celander.app://oauth2redirect` (clave sugerida en `local.properties`), permisos Calendars.ReadWrite User.Read email openid profile offline_access. Un solo recordatorio por evento (`maxReminders=1` en `ProviderCapabilities`). Añadir el esquema `com.celander.app` al `RedirectUriReceiverActivity` (manifiesto) y el tema ya existente.
4. **Suscripciones `.ics` (URL, solo lectura, periódicas) e importación de archivos `.ics`**; y **`LocalDataIcs`** (`export(): String` con VEVENT y VTODO de calendarios LOCAL; `import(text): ImportResult(events, tasks, skipped)`, dedupe por UID; hay que añadir VTODO al parser/escritor, hoy solo hay eventos) y conectarlo a la página de Copia (ZIP) de Ajustes (`settings/BackupPage.kt`, hoy solo ajustes).
5. Más adelante (aprobado por César, después de lo anterior): push de calendario reutilizando Worker de Cloudflare + Firebase de Cemail (`Cemail/docs/PUSH.md`, `Ce/docs/NUBE.md`); implica actualizar la política de privacidad (hoy dice que no hay servidor). NO empezar antes.
6. Menores: capturas de los otros widgets (Mes, Tareas, Año, Agenda) en emulador antes de fusionar; revisar `RemindersSection`/`maxCount` y `CalendarInfo.maxReminders` sin uso; fin de línea CRLF/LF mezclado en algunos archivos; quitar los `Log.i("Accounts", …)` si molestan (no llevan datos personales).
- Yahoo: descartado por César. Capturas de los 6 diseños: no hacen falta.

## Lista de privacidad (`Ce/docs/CELANDER-PRIVACIDAD.md`) — estado
1. Ámbitos de Google: hecho en el código (`GoogleAuth.SCOPES`); pantalla de consentimiento declarada por Ce Líder/César, **«En producción» sin verificar** (3/100 usuarios; sin caducidad de 7 días).
2. Microsoft: pendiente (fase Microsoft).
3. Permisos del manifiesto: **hecho** (solo notificaciones, alarmas exactas, arranque, internet, red).
4. Tokens cifrados con Keystore: **hecho** (AES-GCM; no están en Room ni en logs).
5. Fuera de la copia de Android: **hecho** (`backup_rules.xml` y `data_extraction_rules.xml`).
6. Widgets sin red: por diseño (leen Room); verificar leyendo `widget/` al final.
7. Lista de hosts y sin SDK de terceros: Play Services eliminado; falta el recuento final de hosts (accounts.google.com, oauth2.googleapis.com, www.googleapis.com, login.microsoftonline.com, graph.microsoft.com, URL `.ics`, photon.komoot.io, api.github.com) con un `grep` de URLs.
8. Quitar cuenta y «Borrar todos los datos»: quitar cuenta probado con tests (`AccountSyncTest`); «Borrar todos los datos» pendiente (punto 2 de arriba).
9. Nada al desarrollador: sin servidores propios hoy.
10. Consentimiento y datos de contacto: web y política publicadas en `cactusritter.github.io/Celander/` (fuente `docs/` de `main`); coinciden con la política salvo lo que cambie con Microsoft/.ics/push.
- Verificación final: leer código y manifiesto (vale un subagente) y marcar en el archivo de Ce.

## Cómo se prueba en el S26
```
cd android && ./gradlew.bat :app:assembleDebug        # firma celander-release.jks (android/keystore, fuera de git)
adb -s RFGL107KERL install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
adb -s RFGL107KERL shell am start -n com.celander.app/com.celander.app.MainActivity
adb -s RFGL107KERL logcat -d -s Accounts            # trazas del flujo de cuentas y del envío
```
- Paquete de prueba junto a otro firmado distinto: `-PidSuffix=.test` (el login de Google NO sirve ahí: el cliente OAuth es de `com.celander.app`). Emulador: `emulator-5554` (sin ventana: `emulator.exe -avd Pixel_API_36 -no-window -gpu swiftshader_indirect`); los `.ps1` están bloqueados por la política de PowerShell.
- El login de Google lo hace César en el navegador (nunca escribir contraseñas). El S26 tiene el apagado de pantalla en 30 min (original 10 min = 600000; restaurar al terminar: ver `docs-ce/CAMBIOS-MOVIL.md`).
- Gradle: ejecutar SIEMPRE desde `android/` y con la salida recortada (`| grep -E "^e: |BUILD|FAILED"`); `local.properties` lleva `sdk.dir=C:/Users/cesar/AppData/Local/Android/Sdk` (barras normales) y la clave del cliente OAuth.

## Otros
- Estado del repo: ver `git log --oneline` en `ce/estilo-ce`. `docs/PLAN.md` es el plan original (histórico). `docs-ce/` (capturas con datos reales y `CAMBIOS-MOVIL.md`) está excluida de git.
