# Imágenes a PDF (Android)

App Android (Kotlin) que convierte una o varias fotos de la galería en un documento PDF,
sin conexión y sin anuncios.

## Funciones

- Seleccionar varias imágenes con el selector de fotos del sistema (o recibirlas con "Compartir" desde la galería).
- Reordenar manteniendo presionada una imagen y arrastrándola; quitar imágenes con la ✕.
- Nombre del archivo personalizable.
- Tamaño de página: A4, Carta o ajustado a la proporción de cada imagen; orientación automática, vertical u horizontal.
- Control de calidad JPEG (30–100 %) para equilibrar nitidez y peso.
- El PDF se guarda en **Descargas / Imagenes a PDF** y se puede abrir o compartir al terminar.

Requisitos: Android 8.0 (API 26) o superior.

## Cómo está hecho

- `ImagePdfWriter.kt` escribe el PDF directamente (una imagen JPEG por página con `/DCTDecode`),
  corrigiendo la rotación EXIF y submuestreando imágenes grandes para no agotar la memoria.
- `PdfSaver.kt` guarda en Descargas con MediaStore (Android 10+) o como archivo (Android 8–9).
- `MainActivity.kt` + `ImageAdapter.kt`: cuadrícula de imágenes con arrastre para reordenar.
- `OptionsSheet.kt`: hoja inferior con las opciones; recuerda las últimas usadas.

## Compilar

Doble clic en `compilar.ps1` (o ejecutarlo desde PowerShell). Genera `salida\ImagenesAPdf.apk`.

El script usa el JDK 17 y el Android SDK instalados en `%LOCALAPPDATA%` (`Programs\jdk-17…`,
`Android\Sdk`, `Android\gradle-8.9`). También se puede abrir la carpeta en Android Studio.

## Firma

`keystore/imagenes-a-pdf.jks` es la llave con la que se firma el APK (alias `imagenesapdf`,
contraseña definida en `app/build.gradle.kts`). **No está en el repositorio** (está en `.gitignore`):
guárdala en un lugar seguro, porque cualquier actualización futura debe firmarse con la misma llave
para poder instalarse sobre la versión anterior. Si la carpeta `keystore/` no existe, el build
firma el release con la llave de depuración (sirve para probar, no para distribuir actualizaciones).

## Instalar en el teléfono

1. Copia `salida\ImagenesAPdf.apk` al teléfono (cable, Drive, WhatsApp…).
2. Ábrelo desde el explorador de archivos y acepta instalar desde "orígenes desconocidos" cuando Android lo pida.
