# Lista de publicación de Celander 2.0.0

PUBLICADA el 2026-11-18: v2.0.0 en https://github.com/CactusRitter/Celander/releases/tag/v2.0.0 (main = ce/estilo-ce, versionCode 12, APK firmado en G:Mi unidadCe AppsCelanderCelander-2.0.0.apk). Lo de abajo es el historial de la lista.

## Ya hecho
- [x] Notas de la 2.0.0 en `android/app/src/main/res/raw/changelog.txt`, `raw-es` y `raw-de` (cuentas Google y Microsoft, Tasks por cuenta, suscripción `.ics` e importar archivo, copia ZIP con datos, borrar datos con cuentas, ocurrencias revertidas).
- [x] Tests: 490, fallan 4 (`SettingsCorruptionTest` y 3 de `SettingsRepositoryTest`, error de DataStore al renombrar `.tmp` en Windows). Según el traspaso ya fallaban en el commit original `6d0258d`; no se ha repetido la comprobación hoy.
- [x] Privacidad (lista de Ce): permisos del manifiesto (solo notificaciones, alarmas exactas, arranque, internet y red), tokens cifrados con Keystore, fuera de la copia de Android, hosts contados, sin SDK de terceros. Hosts: accounts.google.com, oauth2.googleapis.com, www.googleapis.com, tasks.googleapis.com, login.microsoftonline.com, graph.microsoft.com, photon.komoot.io, api.github.com y la URL de cada suscripción `.ics`.

## Falta antes de publicar
- [ ] **Probar en el S26 con César** (ver «Pendiente» del traspaso): login Microsoft, eventos y series, cambios hechos en Google Calendar web, borrar datos con cuentas, copia ZIP y restaurar, `.ics`, widgets.
- [ ] Ce Líder: verificar en Entra que `Calendars.ReadWrite` (delegado) y `User.Read` están declarados.
- [ ] Revisar a mano hojas y diálogos al 99 % de transparencia (la prueba de 15 s solo cubrió calendario y Ajustes).
- [ ] **Política de privacidad y web** (`docs/` de `main`, `cactusritter.github.io/Celander/`): hoy hablan de Google. Hay que añadir Microsoft (Graph, permisos), las suscripciones `.ics` (la app descarga la URL que el usuario escribe) y que las cuentas no van en la copia. Sin servidor propio: nada cambia respecto a «no enviamos nada al desarrollador». No tocar la web sin César.
- [ ] Marcar en `Ce/docs/CELANDER-PRIVACIDAD.md` los puntos 2, 7 y 8 (ver «Lista de privacidad» del traspaso).

## Al publicar (solo con la orden de César o de Ce Líder)
- [ ] `android/app/build.gradle.kts`: `versionName = "2.0.0"` (hoy `1.08`) y `versionCode` por encima del actual (11); la 2.0.0 ya tiene sección en el changelog.
- [ ] Compilar release firmado con `celander-release.jks` (`android/keystore`, fuera de git; copia en `G:\Mi unidad\Ce Apps\Común\`).
- [ ] APK en `G:\Mi unidad\Ce Apps\Celander\Celander-2.0.0.apk` (sin reemplazar anteriores) y *release* `v2.0.0` en GitHub de César con las notas «## en / ## es / ## de».
- [ ] Fusionar `ce/estilo-ce` en `main` del fork `CactusRitter/Celander`. Nunca push ni PR a `upstream` (Jonathan).
- [ ] Avisar a Ce Líder con resumen de ≤6 líneas.
