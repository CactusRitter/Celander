# Historial de versiones de Celander

Cada versión se guarda en Google Drive (`Mi unidad/Ce Apps/Celander/Celander-x.y.z.apk`, sin reemplazar la anterior) y, desde la 2.1.2, se publica en GitHub (la app avisa leyendo la última release). Las versiones anteriores están en las releases de GitHub y en `res/raw*/changelog.txt`.

## Sin publicar
- Celander es el calendario de cualquier app: acepta el «nuevo evento» estándar de Android (INSERT, y EDIT sin id) y abre archivos .ics. Siempre se revisa antes de guardar, con aviso de lo que hay a esa hora. Reconoce el UID: ofrece actualizar en vez de duplicar (no retrocede de SEQUENCE) o borrar si está cancelado (METHOD:CANCEL, con confirmación); varios eventos salen en lista. Contrato con Cemail: extra `com.celander.app.extra.ICS`.
- Ajustes: la sección «Datos» (datos de ejemplo y «Borrar todos los datos») y el botón de datos de ejemplo de la tarjeta de bienvenida solo existen en las compilaciones de depuración; la app publicada ya no los muestra.

## 2.4.5 — 2026-11-23
- Notificaciones: el icono pequeño de la barra de estado y el de la tarjeta de bienvenida pasan a ser el calendario con el punto (como el icono de la app), en vez de la «C» antigua.
- Diagnóstico: el registro anota cada alarma de recordatorio que se programa (exacta o aproximada) y cuántos segundos tarde llega cada aviso, para saber si el móvil los retrasa.
- Atajos al mantener pulsado el icono: «Nuevo evento», «Nueva tarea» y «Agenda» (mismas acciones que los widgets). Probados en emulador.

## 2.4.4 — 2026-11-23
- Ajustes: se quitan «Licencias de código abierto» y el logo con la versión del final (como en Cemail y Cenotes; la versión sigue en Actualizaciones).
- Recordatorios: un evento (o tarea) tiene una sola notificación; si tiene varios avisos («1 hora antes» y «10 minutos antes») el nuevo sustituye al anterior en vez de apilarse. Tests unitarios; sin probar en móvil.

## 2.4.3 — 2026-11-22
- Cuentas y red (CeKit F4, parte 2): la foto de cuenta pasa al kit (AccountPhotos, con el reintento a las 6 h y la clave `checked_v2_` de la 2.4.2), el proveedor de tokens (TokenProvider; el consentimiento incremental de contactos va ahora en el GoogleAuth del kit), un único cliente HTTP (CeHttp: IPv4 primero, tiempos de espera y User-Agent «Celander/versión (Android)») y los canales/permiso de notificaciones (Notifications). Compila y pasan los tests; sin probar en emulador ni móvil.
- Inicio de sesión (Google, Microsoft) y almacén de tokens pasan al kit (CeKit F4): mismo cifrado, mismo alias del Keystore (`celander_tokens`), mismas preferencias (`account_tokens`) y claves: las sesiones existentes no se cierran. Sin probar con un inicio de sesión real tras el cambio.

## 2.4.2 — 2026-11-22
- Arreglo: los cumpleaños de Google ya guardados desaparecen siempre al activar «Cumpleaños de los contactos», aunque la sincronización larga de una cuenta recién añadida acabe después (antes podían quedar duplicados). Probado en el S26.
- Arreglo: la foto de la cuenta de Google no salía en algún móvil (un intento fallido se daba por bueno durante 7 días). Ahora un fallo se reintenta a las 6 horas y el motivo queda en Diagnóstico. Probado en la OPPO: la foto aparece.

## 2.4.1 — 2026-11-22
- Arreglo: al activar «Cumpleaños de los contactos» se vuelve a leer todo el calendario de la cuenta, para que los cumpleaños de Google ya guardados desaparezcan (antes se duplicaban). Compila; sin probar en dispositivo.

## 2.4.0 — 2026-11-22
- Cumpleaños de los contactos (Ajustes > Cuentas > cuenta Google, apagado por defecto): pide aparte el permiso `contacts.readonly` (AppAuth, sin tocar el login base ni los tokens existentes), lee nombres y fechas con la People API como mucho una vez al día y los muestra en un calendario propio «Cumpleaños» (solo lectura, rosa, icono de pastel, «Ana · 34» si hay año) en cajón, Gestionar calendarios, vistas, búsqueda y widgets. Un evento de Google `eventType=birthday` se descarta mientras esté activo (sin duplicados). Si la People API falla (401/403) el interruptor se apaga con aviso y la sincronización sigue. Sin cambios de BD (sin migración). Política de privacidad en en/es/de. Tests unitarios; sin probar en emulador ni móvil. Requiere habilitar People API y el ámbito en Google Cloud (ver `Ce/docs/NUBE.md`).
- Cajón ☰: la lista de calendarios muestra solo los activados en Cuentas → Gestionar calendarios; la casilla del cajón sigue siendo solo para mostrar u ocultar al vuelo. «Gestionar calendarios» tiene ahora su propio interruptor (`enabled`, BD v6; lo que estaba apagado sigue apagado); lo desactivado no sale en cajón, vistas, búsqueda ni lo ofrece como calendario o lista predeterminados (sus avisos siguen llegando). Tests unitarios y de migración; sin probar en emulador.
- Ajustes: Datos ya no se corta por abajo (la tarjeta espera a saber si hay datos de ejemplo); la fila de Calendario es una fila normal de la tarjeta; se quita el interruptor del push (siempre activo, con el sondeo de respaldo); quitar una cuenta o «Borrar todos los datos» detiene los canales/suscripciones y llama a /cal/unregister.
- Arreglo: las novedades de la versión actual salían vacías en Actualizaciones (el changelog empezaba con BOM y la versión más nueva no se leía). El kit ignora el BOM y release.ps1 escribe sin BOM. Probado en emulador.

## 2.3.0 — 2026-11-22
- Copia de seguridad: usa el contenedor común del kit (BackupFormat: manifiesto, límites, comprobaciones), sin cambiar el formato del zip. Probado en S26: crear y restaurar.

## 2.2.0 — 2026-11-21
- Pasada de temas: «Todo el día», calendarios y recordatorios por defecto con GlassSwitch del kit; copia de seguridad con GlassDialog del kit (estado en BackupController); chips de fecha y hora con SelectableChip; Retro 98: Guardar legible y selector Evento/Tarea biselado (kit). Sin probar en emulador.
- Módulo común CeKit (submódulo git `android/CeKit/`): `ui/glass`, tema, GlassDialog/GlassSheet/Controls/WheelPicker, movimiento, Apariencia y componentes de Ajustes, Actualizaciones (aviso, Novedades, página), Diagnóstico, AppLog/CrashLog/Updates; textos comunes en en/es/de. Los acentos pasan a llamarse Ocre y Herrumbre (como en Cemail y Cenotes).
- Retro 98: chips «10 minutos antes» y «+ Añadir» como botones rectos biselados (del kit).
- Proceso: `CHANGELOG.md`, `android/scripts/emulator.ps1` (se borra `preview.sh` de macOS), `.gitignore` con `*.jks` y `.claude/settings.local.json`.
