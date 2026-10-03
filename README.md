# CamTV — cámaras Imou en el Fire TV Stick

App para ver hasta 4 cámaras Imou/Dahua (Cruiser, Bullet, Ranger…) en la tele,
por la red local (RTSP), sin la nube de Imou.

## Qué hace
- Cuadrícula de 4 cámaras (calidad baja, fluida). OK = pantalla completa (calidad alta, con audio).
- En pantalla completa, ◀ ▶ cambia de cámara.
- Mantener OK o botón ☰ sobre un cuadro = agregar / editar / eliminar cámara.
- Usuario y contraseña se guardan cifrados en el Fire Stick.
- **IP dinámica resuelta:** si el router reinicia y la cámara cambia de IP, la app la
  busca sola en la red (protocolo de descubrimiento de Dahua/Imou + escaneo del puerto 554),
  la reconoce por su MAC/número de serie o por su contraseña, y guarda la IP nueva.

## Compilar el APK (GitHub, gratis, sin instalar nada)
1. Creá una cuenta en github.com y un repositorio nuevo **público** llamado `CamTV`
   (público para que el Fire Stick pueda bajar el APK sin iniciar sesión; no contiene contraseñas).
2. En el repositorio: **Add file → Upload files** y arrastrá **todo el contenido** de esta carpeta
   (incluida la carpeta `.github`). Confirmá con **Commit changes**.
3. Andá a la pestaña **Actions**: se compila solo (~5 minutos). Cuando termina en verde,
   el APK queda en **Releases** con este link fijo:
   `https://github.com/TU_USUARIO/CamTV/releases/download/latest/CamTV.apk`

## Instalar en el Fire Stick
1. Configuración → Mi Fire TV → Opciones para desarrolladores → **Instalar apps desconocidas** →
   activar para **Downloader**. (Si no aparece "Opciones para desarrolladores": Mi Fire TV → Acerca de →
   presioná 7 veces sobre el nombre del dispositivo.)
2. Instalá la app **Downloader** desde la tienda de Amazon.
3. En Downloader escribí el link del APK (podés acortarlo con is.gd para tipear menos) → Instalar.

## Configurar una cámara
- Usuario: `admin`. Contraseña: el **Safety Code** de la etiqueta de la cámara
  (o la que pusiste si la cambiaste).
- Usá **Buscar en la red** para elegir la cámara sin saber la IP.

## Recomendado (opcional)
Reservar IP fija para cada cámara en el router ("DHCP reservation" / "IP estática por MAC").
La app ya resuelve el cambio de IP sola, pero así conecta al instante.
