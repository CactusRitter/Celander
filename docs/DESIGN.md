# Diseño de Celander (familia "Ce")

Celander, **Cemail** y **Cenotes** comparten el mismo sistema de diseño: el mismo código, con los mismos nombres de archivos, clases y funciones. Solo cambian el nombre de la app y el paquete (`com.celander.app`). Si una pieza común cambia en una app, se copia tal cual a las otras dos.

Este documento parte del de Cenotes y dice qué lleva Celander **además** (Glass V2, pensado para pasar a las tres apps) y qué es propio de un calendario.

## Código compartido
| Archivo | Qué es |
|---|---|
| `ui/theme/Theme.kt` | Acentos (los ocho + color libre), claro/oscuro, estilos de fondo (`ThemeStyle`), colores del cristal (`GlassColors`), `CelanderTheme(settings)`, `glassScheme` |
| `ui/theme/Skin.kt` | Los seis diseños: Cristal, Moderno, AMOLED, Retro 98, Papel, Brutalista (`SkinLook`) |
| `ui/theme/Icons.kt` | Iconos propios |
| `ui/glass/Glass.kt` | `Modifier.glass`, `glassSegment`, `glassTile`, `GlassBackground`, `GlassIconButton`, `GlassPill`, `PillIcon`, `TopScrim`, `selectedOutline`, `SelectionBadge`, `LocalHazeState`, `GlassLayers` |
| `ui/glass/GlassTokens.kt` | **Glass V2**: curva de transparencia (ver abajo) |
| `ui/glass/Shadow.kt` | **Glass V2**: `softShadow` (solo exterior), `Elevation` |
| `ui/glass/Fade.kt` | `fadingTopEdge`, `fadingBottomEdge` |
| `ui/glass/Motion.kt` | Resortes (`glassSpring`, `bounceSpring`, `softBounceSpring`), `pressScale`, `popIn`, `bumpOn` |
| `ui/glass/GlassSwitch.kt`, `GlassSlider.kt`, `GlassProgress.kt`, `TaskCheckbox.kt` | Interruptor, deslizador, barra de progreso y casilla |
| `ui/SettingsComponents.kt` | `BigRow`, `SettingsCard`, `SwitchRow`, `ChoiceChip`, `IconBubble` |
| `update/Updates.kt`, `update/ReleaseNotes.kt`, `ui/UpdateViews.kt` | Versiones nuevas desde las *releases* de https://github.com/CactusRitter/Celander (descarga con barra de progreso). Las notas de la release llevan secciones `## en`, `## es`, `## de` |

## Glass V2 (lo que cambia respecto a Cenotes y Cemail)
Cemail y Cenotes usan hoy Haze 1.7.3 y una fórmula de opacidad fija. Celander trae el cristal bueno de su diseño anterior sobre el mismo código común. Para portarlo hay que cambiar estos puntos; el resto de los archivos de `ui/` no cambia.

1. **Haze 2.0.1** (`haze` y `haze-blur` en `libs.versions.toml`; dependencias `libs.haze` y `libs.haze.blur`). En `Glass.kt` el desenfoque ya no es `hazeEffect { … }` sino `hazeBlur(input = HazeInput.Sources(state), style = HazeBlurStyle { blurRadius(…); backgroundColor(…); colorEffects(listOf(HazeColorEffect.tint(fill))); noiseFactor(…); progressive(…) })`. `TopScrim` usa `progressive(HazeProgressive.verticalGradient(…))`. Imports: `dev.chrisbanes.haze.HazeInput`, `HazeProgressive`, `HazeState`, `blur.HazeBlurStyle`, `blur.HazeColorEffect`, `blur.hazeBlur`.
2. **Capas (`GlassLayers`)**: `BACKGROUND = 0f` y `CONTENT = 1f`. Una pantalla registra el fondo (`GlassBackground(Modifier.hazeSource(state, zIndex = BACKGROUND)) {}`) y el contenido (`Modifier.hazeSource(state, zIndex = CONTENT)`) como **hermanos**, nunca uno dentro del otro (Haze falla con «hazeEffect nodes cannot draw an ancestor hazeSource»). El cristal dentro del contenido solo desenfoca el fondo; el cristal flotante fuera de las fuentes desenfoca todo.
3. **`LocalHazeState`**: `glass()`, `GlassIconButton`, `GlassPill` y `TopScrim` toman `hazeState` de `LocalHazeState.current` por defecto. La pantalla lo provee una vez (`CompositionLocalProvider(LocalHazeState provides state)`) y no hay que pasarlo a cada llamada. Sin él (null) el cristal solo tiñe, sin desenfocar.
4. **Curva de transparencia (`GlassTokens`)**: la transparencia (0–100) se convierte en opacidad con una curva lineal a trozos: opaco a 0 %, el valor calibrado a 65 % (el defecto) y nada a 100 %. Los valores a 65 % están calculados para que el texto cumpla **WCAG AA** (4,5:1) en claro y oscuro; sin desenfoque (API < 31) los rellenos son más llenos. Funciones: `fillAlpha` (tarjetas y píldoras), `pinnedAlpha` (resaltados), `sheetFillAlpha` (diálogos, menús y hojas, menos transparentes), `tileAlpha` (celdas que no desenfocan), `widgetAlpha`. `GlassColors` guarda además `transparency` y los colores opacos (`cardSolid`, `pinnedSolid`, `menuSolid`) y `GlassColors.fill(kind, blurred)` calcula el relleno de cada `GlassKind`.
5. **Sombra solo exterior (`softShadow`)**: capas de sombra dibujadas fuera de la forma (`ClipOp.Difference`), así el cristal translúcido no se oscurece desde abajo. `glass(elevation = …)` la usa en lugar de `Modifier.shadow`. Desde 10 dp se usan las capas `Elevation.floating`; si no, `Elevation.raised`.
6. **Fondo**: `GlassBackground` sigue siendo el de Cenotes. El `AppBackground` antiguo de Celander (tres manchas suaves) equivale al estilo `ThemeStyle.CLASSIC`.
7. Piezas genéricas **añadidas** al sistema común: `Modifier.glassTile(shape, tint)` (celda translúcida que no desenfoca ni lleva borde), `fadingTopEdge`/`fadingBottomEdge`, y los parámetros opcionales `tint` y `content` de `GlassIconButton` y `onValueChangeFinished` de `GlassSlider`.
8. `AppSettings` guarda `transparency` como entero 0–100 (como Cenotes). Las pruebas de la curva están en `GlassTokensTest`.

## Lo propio de un calendario (vive aparte, sobre el sistema común)
Nada de esto dibuja cristal por sí mismo; usa `MaterialTheme.colorScheme`, `LocalSkin` y `Modifier.glass`.
| Archivo | Qué es |
|---|---|
| `ui/CalendarTheme.kt` | Colores sin nombre en Material (`sunday`, `nowLine`, `today`, `success`), `MotionConfig` (reducir animaciones), `Sizes`, `WindowTokens`, `MotionTokens`, `CalendarEnvironment`, `LimitedFontScale` |
| `ui/CalendarColor.kt` | Colores de los calendarios (con versión clara y oscura) |
| `ui/CalendarMotion.kt` | `blockTouchesBelow`, `springClickable`, entradas escalonadas, pulso |
| `ui/Controls.kt` | Botones, chips, selector segmentado, paso, `CalendarCheckbox` (casilla redonda con el color del calendario; Retro 98 dibuja la suya) |
| `ui/GlassSheet.kt`, `ui/GlassDialog.kt`, `ui/Feedback.kt` | Hojas arrastrables, diálogo de confirmación, snackbar, esqueletos y estados vacíos |
| `ui/WheelPicker.kt`, `ui/RubberBandOverscroll.kt`, `ui/WindowLayout.kt`, `ui/Haptics.kt` | Rueda de selección, rebote elástico, tamaño de ventana, vibración |

## Ajustes
Árbol corto de pantallas, como en Cemail y Cenotes: la principal tiene una fila grande (`BigRow`) por sección.
- **Apariencia**: diseño (6), claro u oscuro, sombra 3D, estilo de fondo, color (ocho + barra de tono libre), transparencia con vista previa, transparencia de los widgets y reducir animaciones.
- **Calendario**: vista inicial, primer día, números de semana, formato de hora, calendario y lista de tareas por defecto, duración, gestionar calendarios.
- **Google**, **Recordatorios**, **Datos** (ejemplos y borrar todo), **Copia de seguridad**, **Actualizaciones**, **Licencias**.
- **Copia de seguridad**: un solo `.zip` con `manifest.json` (`"app": "celander"`, `"container": 1`) y `settings.json` (mismo formato que Cemail y Cenotes); casillas al crear y al restaurar. No incluye los ids de calendarios de este teléfono.
- Los textos están en `values`, `values-es` y `values-de` (alemán con «du»).

## Pendiente
- Copia de eventos y tareas locales (`calendar.ics` dentro del mismo zip): falta que la sesión de datos exponga exportar e importar; ahí solo hay que añadir una entrada al contenedor y una casilla en `BackupPage.kt`.
- Pasar Glass V2 a Cemail y Cenotes (Haze 2) siguiendo la lista de arriba.
