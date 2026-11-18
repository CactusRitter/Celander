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

## Hecho el 2026-11-18 (rama `ce/estilo-ce`, sin publicar; 490 tests, fallan solo los 4 de DataStore)
- Fuentes Haze auditadas: sin anidamiento; emulador 15 s sin cierres al 65 % y 99 % (calendario y Ajustes). Sin probar: hojas y diálogos al 99 %.
- Revertir una ocurrencia ya enviada (`AccountSync.pushOne`, `deleteExceptionLink`, `status: confirmed`).
- «Borrar todos los datos» quita cuentas, tokens y calendarios de cuenta (`AccountsReset.removeAll`).
- Microsoft (Graph): `MicrosoftAuth`, `GraphCalendarApi`, `GraphCalendarMapper` (RRULE↔`patternedRecurrence`), `MicrosoftCalendarProvider`, botones en Cuentas; clave `celander.oauth.microsoft.clientId` en `local.properties` (ID `6e17cfa1-6713-4308-bd81-0a7de0e97b0c`). Una sync relista todo y pide las instancias de cada serie; sin `If-Match` (gana lo local); un recordatorio por evento. **Sin probar contra Graph real.** Huecos a verificar en vivo: ocurrencias canceladas en Outlook no llegan; día entero se lee con las fechas tal cual; zona de series = `originalStartTimeZone`; una petición por serie en cada sync (cada minuto con la app visible: vigilar límites); recurrencias sin equivalente (cada hora, varios días del mes) dan `UnsupportedRecurrenceException` y el evento queda con error.
- iCalendar con VTODO; `core/ical/LocalDataIcs` (export/import de eventos, series con excepciones y tareas, con su calendario en `X-CELANDER-CALENDAR`; dedupe por UID en eventos y por título+fecha en tareas, que no tienen UID); la copia ZIP lleva `data.ics` (casillas Ajustes / Calendarios, eventos y tareas); las cuentas NO van en la copia.
- Suscripciones `.ics` por URL (`account/ics/IcsUrlProvider`, tipo `ICS_URL`, solo lectura, relee todo en cada sync; `webcal://` vale) y botón «Importar archivo .ics» en la página de Copia.
- Notas de la 2.0.0 reescritas en `res/raw*/changelog.txt` (en/es/de). Lista de publicación: `docs/PUBLICACION-2.0.0.md`.

## Pendiente, EN ORDEN
1. **Probar en el S26 con César** (instalar ahí está permitido; el login Microsoft/Google lo hace César en el navegador): login Microsoft, calendarios y eventos simples, serie con una ocurrencia movida y otra cancelada, día entero; cambios hechos en Google Calendar web llegando en ≤60 s; «Borrar todos los datos» con cuentas; copia ZIP con datos y restaurarla; suscripción `.ics` y archivo suelto; widgets Mes/Tareas/Año/Agenda.
2. 2.1.1 PUBLICADA (2026-11-18): arregla el cierre al pulsar Descargar (faltaba REQUEST_INSTALL_PACKAGES en el manifiesto; Cemail y Cenotes lo tienen). 2.1.0: cuentas como Cemail. Pendiente: Diagnóstico de Cemail, icono al estilo Ce (sin publicar), 4 tests de DataStore en Windows, probar en S26/OPPO.
3. Más adelante (aprobado por César, después de lo anterior): push de calendario reutilizando Worker de Cloudflare + Firebase de Cemail (`Cemail/docs/PUSH.md`, `Ce/docs/NUBE.md`); implica actualizar la política de privacidad (hoy dice que no hay servidor). NO empezar antes.
4. Menores: capturas de los otros widgets en emulador; revisar `RemindersSection`/`maxCount` y `CalendarInfo.maxReminders` sin uso; fin de línea CRLF/LF mezclado en algunos archivos (hace fallar `perl -0` multilínea: usa Edit); quitar los `Log.i("Accounts", …)` si molestan (sin datos personales); Microsoft: usar delta con ventana si el número de peticiones molesta.
- Yahoo: descartado por César. Capturas de los 6 diseños: no hacen falta.


## Lista de privacidad (`Ce/docs/CELANDER-PRIVACIDAD.md`) — estado
1. Ámbitos de Google: hecho en el código (`GoogleAuth.SCOPES`); pantalla de consentimiento declarada por Ce Líder/César, **«En producción» sin verificar** (3/100 usuarios; sin caducidad de 7 días).
2. Microsoft: hecho en el código (scope `Calendars.ReadWrite User.Read` delegados, el usuario los concede al entrar; Ce Líder lo verificará en Entra); sin probar contra Graph real.
3. Permisos del manifiesto: **hecho** (solo notificaciones, alarmas exactas, arranque, internet, red).
4. Tokens cifrados con Keystore: **hecho** (AES-GCM; no están en Room ni en logs).
5. Fuera de la copia de Android: **hecho** (`backup_rules.xml` y `data_extraction_rules.xml`).
6. Widgets sin red: por diseño (leen Room); verificar leyendo `widget/` al final.
7. Lista de hosts y sin SDK de terceros: **hecho (grep 2026-11-18)**: accounts.google.com, oauth2.googleapis.com, www.googleapis.com, tasks.googleapis.com, login.microsoftonline.com, graph.microsoft.com, photon.komoot.io (lugares), api.github.com (actualizaciones) y la URL que el usuario pone en una suscripción `.ics`. Sin Play Services, Firebase ni análisis.
8. Quitar cuenta y «Borrar todos los datos»: hecho y probado con tests; falta comprobarlo en el S26.
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
