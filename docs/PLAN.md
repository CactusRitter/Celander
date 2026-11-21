# Celander: plan por fases (borrador de la sesión Celander, 2026-11-17)

Nada de esto está implementado. Rama local `ce/estilo-ce`. Estado de lo verificado: el proyecto **compila** (`assembleDebug`, 4 min 46 s la primera vez); la app se instaló en el emulador y arranca (pantalla de bienvenida). **No he podido probar los widgets en el emulador**: tras instalar, `adb` dejó de responder (emulador sin ventana colgado; reiniciado con `-gpu host`, sigue sin responder). El análisis de widgets de abajo es por código; falta confirmarlo visualmente.

## 0. Cómo compilar y arrancar (Windows)
```
cd android
# local.properties (ignorado por git):  sdk.dir=C:/Users/cesar/AppData/Local/Android/Sdk   (barras normales)
./gradlew.bat :app:assembleDebug            # JDK 17 ya en JAVA_HOME
# Emulador (la política de PowerShell bloquea scripts/*.ps1; se lanza el exe directamente):
powershell -NoProfile -ExecutionPolicy Bypass -File androidscriptsemulator.ps1 start
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk     # NUNCA installDebug sin ANDROID_SERIAL
adb -s emulator-5554 shell am start -n com.celander.app/.MainActivity
```
Hay dos dispositivos reales conectados (R52Y806MWSP, RFGL107KERL): siempre `-s emulator-5554`.
`scripts/preview.sh` es de macOS (JDK de ~/Library, AVD «Celander_Pixel»); no sirve aquí.

## 1. Estudio del cristal (Glass) y comparación
| | Celander | Cenotes / Cemail (idénticos entre sí salvo paquete) |
|---|---|---|
| Haze | **2.0.1**: `hazeBlur(HazeInput.Sources(state), HazeBlurStyle{…})`, capas `GlassLayers` (BACKGROUND/CONTENT): el cristal dentro del contenido solo desenfoca el fondo | **1.7.3**: `hazeEffect(state){ blurRadius=24; tints; noiseFactor=0 }`, sin capas |
| Blur / ruido | 25 dp fijo, ruido 0.03 | 24 dp, ruido 0 |
| Transparencia | Ajuste de usuario continuo (`GlassTokens`: 0 sólido sin blur, 0.65 por defecto, 1 transparente; alfas por curva lineal con WCAG AA); alfas distintos sin blur (<API 31) | Tintes fijos por «Look»; sin control de transparencia |
| Superficies | `GlassSurface`, `glassCard` (sombra+blur+brillo `sheen`), `glassTile` (nunca desenfoca), `glassEdge` | `glass(shape, kind=Card/Chrome/Pinned/Menu)`, `glassSegment`, `GlassPill`… |
| Sombra | `softShadow`: capas (`Elevation.raised/lifted/floating`), dibujada solo FUERA de la forma (`ClipOp.Difference`) | `shadow()` de Compose o `liftShadow` (3D) del skin |
| Diseños | Solo cristal | 6 skins (`Skin.kt`: GLASS, MODERN, AMOLED, RETRO, PAPER, BRUTAL), `SkinLook` (esquinas, bordes, sombra dura, selección…), `TopScrim`, `SelectionBadge`, `GlassProgress`, `GlassSwitch/Slider` |
| Color | `CelanderColors` (un tema + acento por hue, `AccentPalette`) | `GlassColors`+`Look` + `AccentPalette` |
| Movimiento | `MotionConfig` + `MotionTokens` (resortes, reducir movimiento, rubber band propio) | `Motion.kt` con `popIn`, `listTransition` (+ `ModulateAlpha`) |

Conclusiones para «Glass V2»:
1. **Celander tiene lo mejor de la parte técnica**: Haze 2, capas, transparencia ajustable, curva de contraste, sombra solo exterior, tokens de tamaño/movimiento. Cenotes/Cemail tienen **skins, selección, TopScrim, controles**. V2 = Celander como base técnica + Skin/SkinLook de Cenotes/Cemail.
2. **Subir Cenotes y Cemail a Haze 2** (API distinta: `hazeSource`/`hazeBlur`/`HazeInput`) es una migración compartida: la hace la líder, en las tres a la vez.
3. Los tres Glass.kt ya divergen (Cenotes≠Cemail por `unreadFill`/`wall`, etc.). V2 debería dividir en: `Glass.kt` común (idéntico) + extras de la app en archivo aparte.
4. Celander usa paquete `designsystem/` y Cenotes/Cemail `ui/glass`+`ui/theme`: unificar nombres de paquete/archivos facilita copiar.

## 2. Widgets: por qué se ven mal con transparencia (por código; sin confirmar en emulador)
Cadena actual (`WidgetStyle.kt`/`WidgetGlass.kt`/`WallpaperBlur.kt`): tarjeta de Glance con `background(color con alpha)` + `cornerRadius` (solo API 31+) + máscara de borde (rim) + sombra pre-renderizada en bitmap ALPHA_8 teñida + brillo; todo en bitmaps por tamaño reportado.
Causas probables:
1. **No hay desenfoque real**: un widget no puede desenfocar el fondo de pantalla. A transparencia alta el texto queda directamente sobre el fondo (sin blur → pierde contraste). `GlassTokens.widgetAlpha` garantiza AA solo hasta el valor por defecto sobre negro o blanco puros, no sobre fondos con detalle o colores medios.
2. **Desenfoque de fondo opcional que exige permisos enormes**: leer el fondo de pantalla pide «Acceso a todos los archivos» + fotos (Android 13+), y además un toque en cada widget para «alinear» (`WidgetAlignReceiver`, `WidgetPlacements`). Sin eso (o con fondo animado, o < API 31) cae a relleno plano. Es frágil: depende de la posición del widget, tamaño de pantalla y launcher (el móvil de César, Samsung/One UI, puede reportar tamaños/posiciones distintos) y del recorte del fondo (`centerCrop`, sin desplazamiento de páginas del launcher).
3. **Sombra y borde dibujados como imágenes** dentro del propio widget: con tarjeta translúcida, el borde (rim) y el brillo (`sheen`) quedan sobre el fondo y el «halo» de la sombra recorta o ensucia en launchers que escalan/recortan el widget; el margen `WidgetElevation` (10/8/12 dp) reduce el área útil y no coincide con los márgenes del launcher.
4. **Una tarjeta, una transparencia**: `widgetTransparency` mezcla relleno, rim y sheen; en modo sistema el día/noche lo cambia el launcher con `DayNightColorProvider`, pero los bitmaps teñidos (ink/sunday/accent) usan una sola pasada: contraste distinto entre día/noche y fondos claros/oscuros.
5. Antes de Android 12 no hay esquinas redondeadas (usa un drawable).
Decisión a tomar con César (qué ve «muy mal» en su móvil: captura necesaria): 
 - Opción A (recomendada): **quitar el desenfoque de fondo** y sus permisos; widget = tarjeta de cristal «honesta»: relleno translúcido con límite inferior de opacidad para texto (suelo de contraste), sin sombra/halo en bitmap (o muy discreta), borde fino; transparencia 0–100 % pero con tope de legibilidad y texto con ligera sombra/halo si baja de cierto umbral. Menos código y sin permiso de archivos.
 - Opción B: mantener el blur del fondo pero hacerlo robusto (detección de posición por `AppWidgetManager` + `getAppWidgetOptions`, fondo por páginas). Más riesgo, necesita pruebas en el móvil real y en un launcher concreto.
 - Verificar con capturas en emulador (fondos claro/oscuro/detallado) y, con permiso de César, en el móvil.

## 3. Reutilización del código para la nueva fuente de datos (sin CalendarContract)
Estado actual: el modelo ya tiene **Room** con `calendars`, `events` (con `rrule`, `timeZone`, `reminders`), `event_exceptions` y `tasks`, fuente `LOCAL` (propia), `DEVICE` (CalendarContract) y `GOOGLE_TASKS` (tareas ya sincronizadas con la API de Google Tasks con `play-services-auth`, 11 archivos / 1490 líneas + 3 de `core/googletasks`). Es decir: **la base de «Room como fuente de verdad» y una sincronización con la API de Google ya existen para las tareas.**

| Parte | Líneas | Decisión |
|---|---|---|
| `core/recurrence` (RRule, RecurrenceExpander, OccurrenceCalculator, SeriesEdits) | 703 | **Conservar.** Es independiente del origen. Revisar que expanda RRULE/EXDATE/RDATE/zonas con el criterio de Google y Graph. |
| `core/ical` (parser, writer, IcsMapper, fechas) | 711 | **Conservar**; es la base de la importación y suscripciones .ics; reutilizable para CalDAV (Yahoo). |
| `core/model` (Event, EventTiming, EventException, Task…) | 254 | **Conservar y ampliar**: `CalendarSource`/cuenta, campos remotos. |
| `core/database` (Room v3) | 948 | **Conservar y ampliar** con migración: tabla `accounts`; en `calendars`: `accountId`, `remoteId`, `syncToken`, `accessRole`, `isPrimary`, color remoto, `provider`; en `events`: `remoteId`, `etag`, `remoteUpdated`, `syncState` (clean/dirty/pendingDelete), `recurringEventId`/`originalStart` para excepciones. `device_calendar_prefs` → desaparece. |
| `core/device/*` (DeviceMapping, DeviceWritePlanner, ProviderColumns, ProviderExDates) | 805 | **Se tira** (mapeo a CalendarContract). Parte de la lógica de `DeviceWritePlanner` (qué operaciones requiere cada alcance «solo este / este y siguientes / todos») se reaprovecha como idea en el planificador de escrituras por proveedor. |
| `calendar/device/*` (ProviderCalendarSource, DeviceCalendarSource, DeviceEventStore, PendingDeviceDeletes, DeviceCalendarAccess…) | 1075 | **Reescribir**: las interfaces `DeviceCalendarSource`/`DeviceEventStore` son ya una buena forma para una capa abstracta; se renombran a `CalendarStore`/`CalendarProvider`, y la implementación sobre Room sustituye a `ProviderCalendarSource`. Permisos READ/WRITE_CALENDAR y SYNC desaparecen. |
| Pantallas (`calendar/*`, `event`, `search`, `notifications`, `widget`) | ~6500+ | **Conservar**; cambian solo los repositorios que alimentan (`CalendarContentRepository`, `CalendarsRepository`). Notificaciones/alarmas: ya existen (`USE_EXACT_ALARM`); con Room las programa Celander misma a partir de `reminders`. |
| `task/google` | 1490 | **Conservar**, pasar al nuevo inicio de sesión común. |
| Tests (97 archivos) | | Conservar los de recurrencia/ical/UI; reescribir los de device. |

## 4. Capa de proveedores
```
interface CalendarProvider {                       // una implementación por tipo; el resto de la app no la ve
    val type: ProviderType                          // GOOGLE, MICROSOFT, YAHOO (CalDAV), ICS_URL, ICS_FILE(local)
    suspend fun listCalendars(account): List<RemoteCalendar>
    suspend fun syncEvents(calendar, token: String?): SyncResult      // incremental; token inválido → completo
    suspend fun create(calendar, Event): RemoteRef
    suspend fun update(ref, Event, scope: SeriesScope): RemoteRef
    suspend fun delete(ref, scope: SeriesScope)
    fun capabilities(): ProviderCaps                // recordatorios por evento, escritura, invitados…
    suspend fun signOut(account)
}
```
Room es la única fuente para la pantalla. Cola de escritura (`pending_ops` o estado `dirty` por evento) y `WorkManager`: `SyncWorker` periódico + inmediato al cambiar + al recuperar red. Estados de cuenta (OK / necesita reautorizar / sin red).

### Google Calendar API v3
- `calendarList.list` (syncToken propio) para listas, color, `accessRole`, `primary`, `selected`.
- `events.list(calendarId, syncToken, singleEvents=false, showDeleted=true)`: **sincronización incremental** con `nextSyncToken`; HTTP 410 → borrar caché del calendario y sincronizar completo. Primera sincronización con ventana (p. ej. −1 año … +2 años) con `timeMin/timeMax`; con `syncToken` no se pueden usar `timeMin/timeMax` (se rellena la ventana luego con otra lista si hace falta).
- Recurrencia: se guardan el evento maestro (`recurrence: [RRULE, EXDATE…]`, `start.timeZone`) y las **instancias modificadas** (`recurringEventId` + `originalStartTime`) como `event_exceptions`; cancelaciones = `status: cancelled`. Se expande localmente con `core/recurrence` (con `singleEvents=false`, menos datos y funciona offline).
- Escrituras: `insert`, `patch` con `If-Match` (etag), `delete`; «solo este evento» = patch de la instancia (`events.instances` para obtener su id), «este y siguientes» = cortar RRULE (`UNTIL`) + crear nueva serie, «todos» = patch del maestro.
- Recordatorios: `reminders.overrides` (minutos, método `popup`) o `useDefault`; Celander programa ella misma las alarmas.
- Conflictos: etag/`updated`; si hay 412, se vuelve a bajar el evento y se reaplica el cambio local si no tocó los mismos campos; si lo tocó, **gana el servidor y se avisa** (o gana lo local si es más reciente y no hay solapamiento; a decidir). Borrados offline: marca `pendingDelete` hasta confirmar.
- Zonas horarias y todo-el-día (fechas sin hora) ya modelados en `EventTiming`.

### Inicio de sesión
- Cemail usa **AppAuth** (navegador del sistema, código+PKCE, sin secreto; cliente Android de Google ligado al paquete y al SHA-1; esquema de redirección = client id invertido) para Google y Microsoft (`GoogleAuth.kt` 129 líneas, `MicrosoftAuth.kt` 106, `TokenStore` 92, `AccountTokenProvider`). Cenotes/Celander-tareas usan Play Services (`AuthorizationClient`). **Recomendación: AppAuth (el de Cemail)**, porque da refresh token propio, funciona igual con Microsoft, no depende de Google Play Services y permite varias cuentas sin la UI de Play Services. Se copia/comparte `data/auth` de Cemail (candidato a código común) y se migra `task/google` a ese proveedor.
- El cliente OAuth y la firma son los de Celander (ver §6).

### Microsoft (Graph)
- Escopes delegados: `Calendars.ReadWrite`, `offline_access`, `User.Read`, `openid profile email`; cuentas personales no necesitan consentimiento de administrador.
- `GET /me/calendars`; eventos con **delta query** `GET /me/calendarView/delta` (ventana de fechas obligatoria; `deltaToken`) o `/me/events/delta` (sin expandir, con `type=seriesMaster/exception/occurrence`). Escrituras `POST/PATCH/DELETE /me/events`, etag en `@odata.etag`/`If-Match`. Recordatorio: un solo `reminderMinutesBeforeStart` por evento (menos que Google: la capa debe declararlo en `capabilities()`).
- Reutilizar `MicrosoftAuth` de Cemail (ya funciona con AppAuth/Graph).

### Suscripciones .ics e importación
- URL .ics: solo lectura, `WorkManager` periódico (p. ej. 6–24 h, configurable), `If-None-Match`/`If-Modified-Since`, se reemplaza el contenido del calendario; reutiliza `IcsParser`. Archivo .ics: importación a un calendario local (fuente `LOCAL`, ya existe). Conviven con los de las cuentas porque el modelo lo distingue por `source/provider/account`.

### Yahoo (investigar antes de comprometerse; a la última)
- Mi conocimiento (sin verificar hoy): Yahoo no ofrece API abierta de calendario; el acceso real es **CalDAV** (`caldav.calendar.yahoo.com`) con **contraseña de aplicación** (la OAuth de Yahoo para calendario es restringida/cerrada a partners). Requiere un cliente CalDAV propio (PROPFIND/REPORT sobre `core/ical`; `sync-collection`/ctag para incremental) y guardar la contraseña cifrada. Es viable pero con coste alto y beneficio bajo. **Propuesta: hacer primero suscripción por .ics (Yahoo permite compartir/publicar un calendario por URL) y dejar CalDAV para el final, si César lo quiere.**

## 5. Permisos de Google Calendar (para la consola)
| Scope | Qué da | Notas |
|---|---|---|
| `https://www.googleapis.com/auth/calendar.events` | leer y escribir eventos de todos los calendarios | **mínimo para leer/editar eventos** |
| `https://www.googleapis.com/auth/calendar.calendarlist.readonly` | listar calendarios (nombre, color, rol) | necesario además de `calendar.events` |
| `https://www.googleapis.com/auth/calendar.readonly` | solo leer eventos **y** calendarios | si solo hubiera lectura |
| `https://www.googleapis.com/auth/calendar` | todo, incluida crear/borrar calendarios y compartir | **no hace falta** |
| `https://www.googleapis.com/auth/tasks` | ya usado por la app | ya declarado |
Propuesta: `calendar.events` + `calendar.calendarlist.readonly` + `tasks` (+ `openid email profile`).
Verificación de Google: los de Calendar y Tasks son **sensibles** (no restringidos como Gmail): pantalla de consentimiento en producción sin verificar → aviso «app no verificada» y tope de 100 usuarios; **en modo «Pruebas» los refresh tokens caducan a los 7 días** (hay que volver a iniciar sesión). Para uso familiar lo práctico es publicar «En producción» sin verificar (aviso, 100 usuarios, sin caducidad); verificar exige dominio, política de privacidad propia y vídeo de demostración (ver §6). Hay que habilitar «Google Calendar API» y «Google Tasks API» en el proyecto. (Detalles de política a confirmar en la consola.)

## 6. Independizar de Jonathan
- **Firma**: generar `celander-release.jks` (como `cenotes-release.jks`, copia en `G:\Mi unidad\Ce Apps\Común\`, nunca en git), `keystore.properties` ya soportado por `build.gradle.kts`. Pasar a otra firma **obliga a reinstalar una vez** y a exportar/importar los datos (la base está en Room; hace falta una copia de seguridad: Celander hoy no tiene; hay que hacerla antes de cambiar la firma si César tiene datos propios en la versión de Jonathan).
- **Google Cloud**: proyecto nuevo «Celander»; habilitar Calendar API y Tasks API; pantalla de consentimiento (externa, tipo de usuario externo, nombre «Celander», correo de soporte, scopes de §5, usuarios de prueba o producción); cliente OAuth **Android**: paquete `com.celander.app` + SHA-1 de `celander-release.jks` (y otro cliente con el SHA-1 de depuración, o `applicationIdSuffix` `.debug`, para desarrollar). Mismo procedimiento que Cemail (`docs/OAUTH_SETUP.md` de Cemail). Para Microsoft: registro de aplicación en Entra ID (cuentas personales), redirect `msauth://…` con el hash de firma, permisos §4.
- **Identidad de la app**: ¿mantener `com.celander.app`? Si se mantiene, sustituye la de Jonathan al instalar con la misma firma… pero la firma cambia, así que instalará como conflicto: hay que desinstalar la de Jonathan. Alternativa: otro `applicationId` (p. ej. `com.celander.app` se queda para el fork de César y Jonathan sigue con la suya en su móvil). Decisión de César.
- **Web y política de privacidad** (de Jonathan, `allurecha.github.io/celander-app`): la política hay que redactarla propia (menciona Google, Microsoft, tokens locales, sin servidores propios); la web puede ser una página en GitHub Pages de `CactusRitter/Celander` (`_config.yml`, `index.html`, `datenschutz.html` en la raíz del repo ya existen: son de Jonathan; reescribirlos con el nombre de César). Hace falta para verificación de Google (URL de política y dominio). No tocar hasta decidirlo.
- Licencias/atribución: el repo no declara licencia; conviene revisarlo con Jonathan al independizar.

## 7. Fases y riesgo
| Fase | Contenido | Compartido (lo hace Ce Líder en las tres) | Propio de Celander | Riesgo |
|---|---|---|---|---|
| **0** | Preparación: rama, entorno, `CLAUDE.md` en el fork, firma propia (`celander-release.jks`), copia de seguridad de datos, nombre/paquete decididos | — | todo | bajo |
| **1a** | **Widgets**: capturas del fallo en el móvil de César → elegir A/B; arreglo y pruebas en emulador (fondos claro/oscuro/detalle) | — (los widgets son de Celander; Cemail tiene `Widgets.kt` aparte) | todo | medio (depende del launcher) |
| **1b** | **Diseño Ce común** con el calendario del teléfono tal como está: `Skin/SkinLook`, `TopScrim`, selección (`SelectionBadge`), controles (`GlassSwitch/Slider/Progress`), Ajustes comunes (`SettingsComponents`), `Updates` con barra de progreso + `UpdateBanner`/`WhatsNew`, copia de seguridad en zip con manifiesto | Skin.kt, Glass.kt, Theme.kt, Motion.kt, Controls compartidos, Updates, textos `pick_*/update_*/whatsnew_*/skin_*/accent_*`, formato de copia. Antes, **Haze 2 en Cenotes/Cemail** y alinear nombres de paquete (V2) | adaptar `GlassLayers`, transparencia, `GlassSheet`, `WheelPicker`, pantallas del calendario | medio-alto: Celander tiene `designsystem/` con otras APIs (hay que fusionar sin perder lo suyo) |
| **2** | **Independizar**: Google Cloud + cliente OAuth Android, nuevo inicio de sesión (AppAuth) para Tasks (migración de `task/google`), publicar con firma propia (reinstalar una vez), web/política propias | `data/auth` (GoogleAuth, TokenStore) candidato a común con Cemail | rotación de firma, política de privacidad, web | medio (consola y reinstalación) |
| **3** | **Cuentas + proveedores**: capa `CalendarProvider`, Room v4 (cuentas, tokens, `syncToken`, estados), `SyncWorker`, cola offline; **Google Calendar** completo (calendarList, eventos, series/excepciones, recordatorios, escrituras, 410, conflictos); quitar `core/device` y permisos de calendario | `data/auth` | casi todo (modelo, sync, UI de cuentas con «Añadir cuenta») | **alto**: recurrencias/excepciones y escritura offline; la mayor fuente de errores; migrar datos de usuarios |
| **4** | **Microsoft** (Graph, delta) | `MicrosoftAuth` ya existe en Cemail | `GraphCalendarProvider` | medio |
| **5** | **Suscripciones .ics + importación** (convive con cuentas) | — | `IcsSubscriptionProvider`, UI | bajo (ya hay parser) |
| **6** | **Yahoo** (CalDAV con contraseña de app) solo si merece la pena | — | `CalDavProvider` | medio-alto |
| **7** | Lo demás: más skins, ajustes de calendario por cuenta, reintentos, tablet/plegables, pruebas instrumentadas | | | |

**¿Es viable «widgets + diseño Ce con el calendario del teléfono, luego cambiar la fuente»?** Sí y es lo que recomiendo (fases 1a/1b antes de 3): el diseño y los widgets no dependen del origen de datos; así César ve resultados pronto y la fase de alto riesgo (3) llega con el diseño ya estable. Única pega: los widgets leen `CalendarContentRepository`; si ese repositorio es la frontera (como parece), el cambio de fuente no los toca. Orden recomendado: 0 → 1a → 1b → 2 → 3 → 4 → 5 → 6.

## 8. Cosas que necesito de César
1. Capturas de los widgets «muy mal» en su móvil (modelo/launcher, fondo de pantalla, transparencia usada).
2. ¿Mantener `com.celander.app` o cambiar de paquete? ¿Tiene datos propios en Celander 1.08 que haya que conservar?
3. Confirmar scopes de §5 y si se publica «En producción» sin verificar o se verifica.
