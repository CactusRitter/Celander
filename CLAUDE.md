# Celander

App de calendario para Android de **Jonathan** (cuenta GitHub `allurecha`). Forma parte de las apps «Ce» de César Caballero y Jonathan, junto con **Cemail** y **Cenotes**: comparten el mismo diseño y deben parecer una sola familia. Celander es **el origen del diseño de cristal**; Cemail y Cenotes lo han ido ampliando.

- Repositorio de César: https://github.com/CactusRitter/Celander (fork del de Jonathan, https://github.com/allurecha/celander-app, que también aloja la web en la raíz: `index.html`, `datenschutz.html`). La app está en `android/`.
- Paquete: `com.celander.app` · versión 2.4.4 (versionCode 22) · minSdk 26 · targetSdk 37
- Kotlin + Jetpack Compose (Material 3) · Room · Haze (cristal) · widgets. Versiones en `android/gradle/libs.versions.toml`.
- Código en `android/app/src/main/java/com/celander/app/`; el sistema de diseño está en `designsystem/` (`Glass.kt`, `Theme.kt`, `Tokens.kt`, `GlassSheet.kt`, `Controls.kt`, `Motion.kt`…).

## Repositorio y acceso (cambiado el 2026-11-17)
- César ha hecho un **fork propio**: https://github.com/CactusRitter/Celander (público; `origin`, permiso ADMIN). El repositorio original de Jonathan (`allurecha/celander-app`) es `upstream`, solo lectura. César quiere desarrollar Celander por su cuenta, como Cemail y Cenotes, sin depender de Jonathan; Jonathan puede añadirse como colaborador cuando se quiera.
- `main` del fork es de César: vale la norma de la familia Ce (commits en `main` del fork, «Sin publicar» en el changelog, versiones y *releases* solo cuando César o la sesión Ce lo digan). Mientras se rehace la app se puede trabajar en ramas `ce/…` y fusionarlas.
- **No hagas push ni abras PRs a `upstream`** (el repositorio de Jonathan) sin que César lo pida. Los PRs a Jonathan, si los hay, los decide César.
- Trabajo pendiente de independizar: firma propia de la app (`celander-release.jks`, como Cenotes; copia en `G:\Mi unidad\Ce Apps\Común\`), proyecto propio de Google Cloud y cliente OAuth Android con ese SHA-1 (Calendar/Tasks, como se hizo para Cemail), y web y política de privacidad propias (la actual es de Jonathan, en `allurecha.github.io/celander-app`). No tocar la web hasta decidirlo con César.
- Este `CLAUDE.md` no está en el repositorio hasta que se haga commit; hazlo en el fork cuando esté bien.

## Rol de esta sesión
Es una de las sesiones de app de la familia Ce. La **sesión líder Ce** (carpeta `D:\cesar\Documents\Claude Projects\Ce`) coordina a todas: lo que ella te diga viene de César. Lee `D:\cesar\Documents\Claude Projects\Ce\CLAUDE.md` y `D:\cesar\Documents\Claude Projects\Ce\docs\ECOSYSTEM.md` (qué se comparte, formato de avisos y registro de cambios). Cuenta a la líder (con `SendMessage`) lo relevante que descubras o cambies.

## Normas de la familia Ce (resumen; vale el `CLAUDE.md` de Ce)
- **Publicar por lotes**: los retoques se acumulan (commits y «Sin publicar» en el changelog) y se publica una versión solo cuando César o la sesión Ce lo digan. Fallo grave sí, al momento.
- **Probar en el emulador** `emulator-5554` con `ANDROID_SERIAL=emulator-5554` siempre antes de `installDebug` o tests instrumentados (actúan sobre todos los dispositivos conectados). Dispositivos reales: ver el apartado «Dispositivos reales» al final.
- **Idiomas**: ningún texto visible en el código; todo en `values`, `values-es` y `values-de` a la vez (alemán con «du»).
- **Diseño**: usar el sistema de diseño; no inventar colores, radios ni animaciones. Si falta algo, se añade al sistema común de todas las apps.
- Sin gastar dinero. Sin compatibilidad hacia atrás obligatoria, pero nunca perder datos de usuario.
- APK de cada versión en `G:\Mi unidad\Ce Apps\Celander\Celander-x.y.z.apk`.

## Primer trabajo (acordado con César el 2026-11-17)
1. **Estudiar** cómo funciona el cristal de Celander (Haze, capas `GlassLayers`, `GlassTokens`, `softShadow`, tokens) y compararlo con las versiones de Cenotes y Cemail (`ui/glass/*`, `ui/theme/Skin.kt`, `Theme.kt` en cada repo). Idea de César: un **«Glass V2»** común a las tres apps.
2. Hacer que el proyecto compile y arranque en el emulador (comandos en `android/`; ver `android/scripts/preview.sh`; Windows: `gradlew.bat`). Anotar aquí los comandos que funcionen.
3. Plan para llevar a Celander lo de Cenotes y Cemail (actualizaciones en la app con barra de progreso, copias de seguridad, selección, `TopScrim`, diseños alternativos…), sin tocar código hasta que la líder confirme el plan con César.

## Comandos que funcionan (Windows, probado 2026-11-17)
- `android/local.properties` (ignorado por git): `sdk.dir=C:/Users/cesar/AppData/Local/Android/Sdk` (barras normales; con `\` falla con «El nombre de archivo… no son correctos»).
- Compilar: `cd android && ./gradlew.bat :app:assembleDebug` (JDK 17; la primera vez ~5 min).
- Emulador: SIEMPRE SIN VENTANA (orden de César, 2026-11-21): `powershell -NoProfile -ExecutionPolicy Bypass -File android\scripts\emulator.ps1 start` (lleva `-no-window -gpu swiftshader_indirect`), `stop` y `shot out.png`; con turno de `emu-lock`. NUNCA lanzar `emulator.exe` a mano ni con `-gpu host` (abre ventana) ni usar `window` salvo que César lo pida. Si adb se cuelga: `stop` y `start` de nuevo.
- Instalar SIEMPRE con `adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk` (hay dos dispositivos reales conectados). `scripts/preview.sh` es de macOS.
- Plan por fases (borrador): `docs/PLAN.md`.

## Ahorro de tokens (norma de César para todas las apps Ce)
Lee `D:\cesar\Documents\Claude Projects\Ce\docs\AHORRO.md`: subagentes para leer/comparar/buscar (resumen de 10-15 líneas), salidas siempre recortadas, mensajes cortos entre sesiones, modelo barato en tareas mecánicas, y mantener `docs/TRASPASO.md` de esta app al día (aviso a César «conviene /clear» al cerrar un bloque grande).

**Informar a la líder:** al cerrar cada bloque, `SendMessage` a «Ce Líder» con un resumen de máximo 6 líneas (qué hiciste y commit, decisiones de diseño que tomaste, qué no probaste, qué le pides a César). César no debe ser el único que se entera.

**Consolas y cuentas:** lo ya hecho (Google Cloud, Entra, claves, webs, dispositivos) está en `D:\cesar\Documents\Claude Projects\Ce\docs\NUBE.md`; mírelo antes de abrir una consola o preguntar a César, y anota allí lo nuevo.

**Reutilizar antes de inventar:** antes de decir «no se puede» o diseñar algo, busca si otra app Ce ya lo resuelve (`Ce/docs/NUBE.md`, `Ce/docs/ECOSYSTEM.md`, `docs/` de las otras apps; un subagente barato con resumen de 5 líneas). Lo que una app ya sabe hacer, las demás lo copian.

## Dispositivos reales y mensajes entre sesiones (César, 2026-11-21: vale sin volver a preguntar)
- Se puede instalar y probar en el **S26** `RFGL107KERL` (`--user 0`) y en la **tableta** `R52Y806MWSP` con `adb -s <serie> install -r` (nunca desinstalar ni borrar datos; nunca `installDebug` ni `connectedAndroidTest` sin `ANDROID_SERIAL`). Con la pantalla SIEMPRE encendida durante la prueba: `adb -s <serie> shell svc power stayon true` y `false` al terminar. El **OPPO** `3B165C00LH900000` es el móvil principal de César: NO tocarlo sin que él lo pida. Correos de prueba: solo entre cuentas propias de César, con asunto de prueba. Si dos sesiones usan el mismo móvil, mirar antes `topResumedActivity` y esperar.
- Enviar mensajes a «Ce Líder» y a las demás sesiones Ce con `SendMessage` está permitido y esperado (resúmenes, preguntas, avisos): no hace falta pedir permiso a César.

## Normas comunes, turnos y traspaso (César, 2026-11-22)
- Las normas de toda la familia Ce están en `D:\cesar\Documents\Claude Projects\Ce\CLAUDE.md`; léelo al empezar. El estado y los pendientes de esta app, en `docs/TRASPASO.md`.
- **Turnos**: emulador, S26 y tableta son de uno solo a la vez: `powershell -NoProfile -ExecutionPolicy Bypass -File "D:\cesar\Documents\Claude Projects\Ce\scripts\emu-lock.ps1" acquire Celander` (añade `-Dev s26` o `-Dev tableta` para esos), y `release Celander` al acabar.
- **Burn-in**: pantalla del móvil encendida solo mientras se prueba; al acabar `stayon false` y `input keyevent KEYCODE_SLEEP`.
- **Sesiones bajo demanda**: la sesión se cierra al terminar la tarea (deja `docs/TRASPASO.md` al día) y se abre otra nueva cuando haga falta.
