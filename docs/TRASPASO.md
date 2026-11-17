# Traspaso de Celander: datos y cuentas (sesión Celander, 2026-11-17)

Rama de trabajo: `ce/estilo-ce` (local; sin push). El diseño compartido (Skin, Ajustes, actualizaciones, copia ZIP) lo hace Ce Líder en `Celander-diseno` (rama `ce/diseno-ce`); no tocar `designsystem/` ni `settings/` sin avisarle.

## Hecho (commits en ce/estilo-ce)
- Widgets (1a): sin sombra ni desenfoque, borde `widget_rim` (con `<solid>` transparente: sin él se pinta opaco), suelo de opacidad 0,5, deslizador completo. Probado el widget Día en emulador.
- Room v4: tablas `accounts`, `remote_calendars`, `event_links`, `exception_links` (patrón de enlaces como Google Tasks; sin FK en los enlaces = tombstones). `CalendarSource.ACCOUNT`: los eventos de una cuenta se guardan como los locales en `events`/`event_exceptions`; la pantalla lee solo Room.
- `account/CalendarProvider` (interfaz) + `RemoteEvent` envuelve `ICalEvent`: cada proveedor convierte a ICalEvent y `IcsMapper.fromIcal` hace series/excepciones.
- `account/google`: `GoogleCalendarApi` (calendarList, events con syncToken, 401 -> token nuevo, 410 -> recarga completa), `GoogleCalendarMapper`, `GoogleCalendarProvider` (solo lectura, `canWrite=false`).
- `account/AccountSync`: calendarios, pull de eventos (series con EXDATE, instancias que llegan solas, borrados, recarga completa quita lo que falta), no pisa cambios locales pendientes, `remove(accountId)`.
- `account/auth`: `GoogleAuth` (AppAuth, scopes de la política), `TokenStore` (AES-GCM, clave Keystore, `account_tokens.xml` fuera de la copia de Android). Cliente OAuth en `android/local.properties` (`celander.oauth.google.clientId`).
- Tests: AccountDaoTest, GoogleCalendarMapperTest, GoogleCalendarProviderTest, AccountSyncTest (todos pasan).

## Pendiente (orden)
1. Conectar: `AccountRepository` (alta con el resultado de GoogleAuth, tokens, primera sync), módulo Hilt con el registro de proveedores, sync periódica (WorkManager) y al abrir, `CalendarsRepository` mostrando calendarios `ACCOUNT` (writable=false de momento).
2. Pantalla de cuentas (añadir Google, estado, quitar) y entrada desde Ajustes (pedir a Ce Líder el hueco en `settings/`).
3. Probar con la cuenta real de César en el emulador (instala `com.celander.app` firmada con `celander-release.jks`; el login lo hace él en el navegador).
4. Escritura offline (create/update/delete con etag, alcance de serie), luego quitar `core/device` y permisos de calendario.
5. Microsoft (Graph delta), suscripciones/importación .ics. Yahoo: descartado por César.

## Decisiones de César
- Una sola versión, Celander 2.0.0, con todo; se publican las tres apps a la vez. Haze 2 se queda en Celander. Paquete `com.celander.app` se mantiene. Sin datos locales en la 1.08.
- La política de privacidad debe ser verdad: lista en `Ce/docs/CELANDER-PRIVACIDAD.md` (marcar al avanzar).
- Emulador siempre sin ventana. Instalar en S26/tableta solo avisando antes a César; el OPPO no se toca.

## Comandos
Ver `CLAUDE.md` (compilar, emulador). Prueba con paquete `.test`: `-PidSuffix=.test` (el login de Google no sirve ahí: el cliente OAuth es de `com.celander.app`).
