# Historial de versiones de Celander

Cada versión se guarda en Google Drive (`Mi unidad/Ce Apps/Celander/Celander-x.y.z.apk`, sin reemplazar la anterior) y, desde la 2.1.2, se publica en GitHub (la app avisa leyendo la última release). Las versiones anteriores están en las releases de GitHub y en `res/raw*/changelog.txt`.

## Sin publicar
- Ajustes: Datos ya no se corta por abajo (la tarjeta espera a saber si hay datos de ejemplo); la fila de Calendario es una fila normal de la tarjeta; se quita el interruptor del push (siempre activo, con el sondeo de respaldo); quitar una cuenta o «Borrar todos los datos» detiene los canales/suscripciones y llama a /cal/unregister.
- Arreglo: las novedades de la versión actual salían vacías en Actualizaciones (el changelog empezaba con BOM y la versión más nueva no se leía). El kit ignora el BOM y release.ps1 escribe sin BOM. Probado en emulador.

## 2.3.0 — 2026-11-22
- Copia de seguridad: usa el contenedor común del kit (BackupFormat: manifiesto, límites, comprobaciones), sin cambiar el formato del zip. Probado en S26: crear y restaurar.

## 2.2.0 — 2026-11-21
- Pasada de temas: «Todo el día», calendarios y recordatorios por defecto con GlassSwitch del kit; copia de seguridad con GlassDialog del kit (estado en BackupController); chips de fecha y hora con SelectableChip; Retro 98: Guardar legible y selector Evento/Tarea biselado (kit). Sin probar en emulador.
- Módulo común CeKit (submódulo git `android/CeKit/`): `ui/glass`, tema, GlassDialog/GlassSheet/Controls/WheelPicker, movimiento, Apariencia y componentes de Ajustes, Actualizaciones (aviso, Novedades, página), Diagnóstico, AppLog/CrashLog/Updates; textos comunes en en/es/de. Los acentos pasan a llamarse Ocre y Herrumbre (como en Cemail y Cenotes).
- Retro 98: chips «10 minutos antes» y «+ Añadir» como botones rectos biselados (del kit).
- Proceso: `CHANGELOG.md`, `android/scripts/emulator.ps1` (se borra `preview.sh` de macOS), `.gitignore` con `*.jks` y `.claude/settings.local.json`.
