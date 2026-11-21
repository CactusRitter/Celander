# Historial de versiones de Celander

Cada versión se guarda en Google Drive (`Mi unidad/Ce Apps/Celander/Celander-x.y.z.apk`, sin reemplazar la anterior) y, desde la 2.1.2, se publica en GitHub (la app avisa leyendo la última release). Las versiones anteriores están en las releases de GitHub y en `res/raw*/changelog.txt`.

## Sin publicar
- Inicio de sesión (Google, Microsoft) y almacén de tokens pasan al kit (CeKit F4): mismo cifrado, mismo alias del Keystore (`celander_tokens`), mismas preferencias (`account_tokens`) y claves: las sesiones existentes no se cierran. Sin probar con un inicio de sesión real tras el cambio.
- Módulo común CeKit (submódulo git `android/CeKit/`): `ui/glass`, tema, GlassDialog/GlassSheet/Controls/WheelPicker, movimiento, Apariencia y componentes de Ajustes, Actualizaciones (aviso, Novedades, página), Diagnóstico, AppLog/CrashLog/Updates; textos comunes en en/es/de. Los acentos pasan a llamarse Ocre y Herrumbre (como en Cemail y Cenotes).
- Retro 98: chips «10 minutos antes» y «+ Añadir» como botones rectos biselados (del kit).
- Proceso: `CHANGELOG.md`, `android/scripts/emulator.ps1` (se borra `preview.sh` de macOS), `.gitignore` con `*.jks` y `.claude/settings.local.json`.
