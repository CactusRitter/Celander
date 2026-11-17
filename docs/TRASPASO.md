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
1. (hecho) Conexión: AccountRepository, Hilt, sync al arrancar y cada 15 min (solo mientras vive el proceso; falta WorkManager en segundo plano), CalendarsRepository muestra ACCOUNT (writable=false). Pantalla account/ui y fila «Cuentas» arriba de Ajustes (probado en emulador: se ve, el login aún no).
2. (hecho) Pantalla de cuentas.
3. (hecho 2026-11-17 en el S26) Login real de Google con la cuenta de César: sincroniza calendarios y eventos sin permisos de calendario del teléfono. Bug resuelto: RedirectUriReceiverActivity de AppAuth exige tema AppCompat (Theme.Celander.SignInReturn en el manifiesto); sin él la app se cerraba al volver del navegador.
4. (hecho en código, probado con servidor falso) Escritura: disparadores SQLite (core/database/AccountTriggers) marcan NEW/DIRTY/DELETED en event_links; AccountSync.push envía antes de cada pull (create/update con etag, conflicto = gana lo local, ocurrencias con writeOccurrence, borrado con tombstone). Pendiente: probar con la cuenta real (César crea un evento), revertir una excepción, y luego quitar core/device y permisos, Microsoft y .ics.
5. Microsoft (Graph delta), suscripciones/importación .ics. Yahoo: descartado por César.

## Decisiones de César
- Una sola versión, Celander 2.0.0, con todo; se publican las tres apps a la vez. Haze 2 se queda en Celander. Paquete `com.celander.app` se mantiene. Sin datos locales en la 1.08.
- La política de privacidad debe ser verdad: lista en `Ce/docs/CELANDER-PRIVACIDAD.md` (marcar al avanzar).
- Emulador siempre sin ventana. Instalar en S26/tableta solo avisando antes a César; el OPPO no se toca.

## Comandos
Ver `CLAUDE.md` (compilar, emulador). Prueba con paquete `.test`: `-PidSuffix=.test` (el login de Google no sirve ahí: el cliente OAuth es de `com.celander.app`).

## Decisiones técnicas (las toma la sesión Celander; César solo decide dinero, publicar, datos de su cuenta, OPPO, diseño no acordado)
- Escritura: local primero en Room y se envía en el siguiente sync (cada 15 min con la app abierta o «Sincronizar ahora»). Conflicto con el servidor: gana el cambio hecho aquí (calendario de una sola persona).
- Los eventos de una serie se envían completos (RRULE + EXDATE de canceladas) y las ocurrencias cambiadas como PATCH de la instancia (id = serie_yyyyMMddTHHmmssZ).
- Revertir una ocurrencia ya enviada a Google no se envía todavía.
- Orden: escritura -> quitar core/device y permisos de calendario -> Microsoft -> .ics.

## Datos reales de César: no tocar
- En su calendario de Google y en la base local del S26 hay un evento REAL, «quedada con Gloria» (creado desde Celander y sincronizado el 2026-11-18). No borrarlo, editarlo ni moverlo en ninguna prueba. Para probar editar/borrar: un evento de prueba propio que se crea y se borra, o el servidor falso de los tests.
- La cuenta real solo se usa para lo que César pida; los tests usan datos simulados.

## Sincronización en segundo plano y «tiempo real» (decidido con Ce Líder, 2026-11-18)
- Sin servidor propio ni notificaciones push de Google (calendar.watch) ni de Microsoft: exigirían un servidor público y FCM, contra la política de privacidad («no enviamos nada a servidores nuestros»).
- Envío inmediato: cualquier cambio local (los disparadores de AccountTriggers lo marcan) lanza en ~3 s un trabajo único de WorkManager con red (`AccountSyncScheduler.requestSoon`); si no hay red, reintenta con backoff.
- Recibir: pull al abrir/volver a la app (mínimo 30 s entre pulls, `AccountRepository.onForeground`), al arrancar, y periódico cada 15 min en segundo plano (mínimo de Android, solo con red y mientras haya una cuenta).
- Widgets y recordatorios se actualizan solos: observan las tablas que escribe el sync.
- Deslizar para refrescar: CANCELADO por César (no lo quiere en ningún sitio). Con la app visible se pregunta por cambios cada 60 s (parado en segundo plano), además del pull al abrir (mín. 30 s) y el periódico de 15 min; «Sincronizar ahora» queda en Ajustes › Cuentas.
- Medido en el S26 con la cuenta real (evento de prueba propio, creado y borrado): Guardar -> creado en Google en ~4,5 s (3 s de espera para agrupar cambios + ~1,5 s); Eliminar -> borrado en Google en ~4,5 s.
