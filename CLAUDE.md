# Celander

App de calendario para Android de **Jonathan** (cuenta GitHub `allurecha`). Forma parte de las apps «Ce» de César Caballero y Jonathan, junto con **Cemail** y **Cenotes**: comparten el mismo diseño y deben parecer una sola familia. Celander es **el origen del diseño de cristal**; Cemail y Cenotes lo han ido ampliando.

- Repositorio de César: https://github.com/CactusRitter/Celander (fork del de Jonathan, https://github.com/allurecha/celander-app, que también aloja la web en la raíz: `index.html`, `datenschutz.html`). La app está en `android/`.
- Paquete: `com.celander.app` · versión 1.08 (versionCode 11) · minSdk 26 · targetSdk 37
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
- **Probar en el emulador** `emulator-5554` con `ANDROID_SERIAL=emulator-5554` siempre antes de `installDebug` o tests instrumentados (actúan sobre todos los dispositivos conectados). Nunca instalar en dispositivos reales sin permiso de César.
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
- Emulador: `scripts/*.ps1` están bloqueados por la política de PowerShell; lanzar `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_API_36 -no-snapshot-save -no-audio -no-boot-anim -gpu host`. (Sin ventana y con `swiftshader_indirect` se colgó adb.)
- Instalar SIEMPRE con `adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk` (hay dos dispositivos reales conectados). `scripts/preview.sh` es de macOS.
- Plan por fases (borrador): `docs/PLAN.md`.

## Ahorro de tokens (norma de César para todas las apps Ce)
Lee `D:\cesar\Documents\Claude Projects\Ce\docs\AHORRO.md`: subagentes para leer/comparar/buscar (resumen de 10-15 líneas), salidas siempre recortadas, mensajes cortos entre sesiones, modelo barato en tareas mecánicas, y mantener `docs/TRASPASO.md` de esta app al día (aviso a César «conviene /clear» al cerrar un bloque grande).

**Informar a la líder:** al cerrar cada bloque, `SendMessage` a «Ce Líder» con un resumen de máximo 6 líneas (qué hiciste y commit, decisiones de diseño que tomaste, qué no probaste, qué le pides a César). César no debe ser el único que se entera.

**Consolas y cuentas:** lo ya hecho (Google Cloud, Entra, claves, webs, dispositivos) está en `D:\cesar\Documents\Claude Projects\Ce\docs\NUBE.md`; mírelo antes de abrir una consola o preguntar a César, y anota allí lo nuevo.

**Reutilizar antes de inventar:** antes de decir «no se puede» o diseñar algo, busca si otra app Ce ya lo resuelve (`Ce/docs/NUBE.md`, `Ce/docs/ECOSYSTEM.md`, `docs/` de las otras apps; un subagente barato con resumen de 5 líneas). Lo que una app ya sabe hacer, las demás lo copian.
