# Celander: funciones

Inventario de lo que la app YA hace (2026-11-27). Mirarlo antes de proponer o programar algo; actualizarlo en el mismo commit que añada, cambie o quite una función. Sacado del código y los changelogs, sin probar en dispositivo.
Rutas relativas a `android/app/src/main/java/com/celander/app/`. Lo compartido: `CeKit/docs/FUNCIONES.md`.

## Calendario
- Día, Semana, Mes, Año, Agenda; salto a fecha (calendar/); cajón con calendarios visibles y cuentas
- Búsqueda con rango de fechas y crear desde la búsqueda (search/); atajos de teclado (calendar/KeyboardShortcuts.kt)

## Eventos y tareas
- Evento: todo el día, repetición y series, varios recordatorios (event/)
- Lugar con sugerencias y abrir en el mapa (event/PlaceSearch.kt); compartir como texto o .ics (event/EventSharing.kt)
- Tareas por grupos, alta rápida, mostrar hechas (task/); sincronizadas con Google Tasks (task/google/)

## Cuentas y orígenes
- Google Calendar, Microsoft, suscripción .ics por URL, calendario local, importar/exportar .ics (account/, core/ical/)
- Cumpleaños de contactos de Google, opcional (account/google/BirthdayEvents.kt)

## Desde otras apps (intake/)
- Calendario del sistema para cualquier app: INSERT / EDIT sin id de eventos (título, lugar, descripción, inicio, fin, todo el día, RRULE, EXTRA_EMAIL como línea «Invitados») abre el editor, con aviso de lo que hay a esa hora
- Abrir .ics (text/calendar, content o file): revisión con calendario y recordatorios; mismo UID = actualizar (respeta SEQUENCE), CANCEL = borrar con confirmación (evento o una fecha), varios = lista; máx. 1 MB y 100 entradas; REPLY no se importa
- Extra `com.celander.app.extra.ICS` (texto del .ics en el INSERT): manda sobre los extras estándar

## Avisos y accesos
- Notificaciones con posponer y aviso si falta permiso de alarma exacta (notifications/); recordatorios por defecto
- 5 widgets: mes, día, año, agenda, tareas (widget/); atajos: evento, tarea, agenda

## Ajustes y datos
- Apariencia (kit), calendario por defecto, duración, semana, formato de hora, vista inicial
- Copia .zip manual (settings/Backup.kt); Diagnóstico y actualizaciones (kit); datos de ejemplo solo en depuración

## No tiene
Recibir «Compartir» (SEND) de otras apps, invitados/responder invitaciones, tiempo o trayecto, resumen diario, bloqueo con huella, tile, Wear OS, copia automática.
