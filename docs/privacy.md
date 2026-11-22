---
title: Privacy Policy - Celander
permalink: /privacy/
---

# Privacy Policy

*Last updated: 22 November 2026* · [Español](#español) · [Deutsch](#deutsch)

Celander ("the app") is an Android calendar and task app, developed by César Caballero ("I", "me"). This policy explains what information the app accesses, how it is used and stored, and your choices. Celander is a small personal project distributed to a few people as an APK; it is not published on Google Play.

## Summary
- Celander talks **directly** between your phone and Google or Microsoft. The only server of mine is a small, free relay for instant notifications (see "Instant change notifications" below) that never receives your calendars, events or tasks.
- Your calendars, events and tasks are kept **on your device**. I never see them, store them, sell them or share them.
- No advertising, no analytics, no tracking, no crash-reporting services.

## Information the app accesses
When you add a Google account, you grant the app these permissions:

| Permission | What it is used for |
|---|---|
| Your Google account email address and basic profile (`openid`, `email`, `profile`) | To show which account is signed in and to tell accounts apart, with your name and profile picture. |
| Google Calendar events (`https://www.googleapis.com/auth/calendar.events`) | To read your events and to create, edit and delete them, at your request. |
| Your list of Google calendars (`https://www.googleapis.com/auth/calendar.calendarlist.readonly`) | To show which calendars you have, with their names and colours. The app cannot change this list. |
| Google Tasks (`https://www.googleapis.com/auth/tasks`) | To read your task lists and tasks and to create, edit, complete and delete them, at your request. |
| Your Google contacts, read only (`https://www.googleapis.com/auth/contacts.readonly`), **only if you switch on "Birthdays from contacts"** | To read the names and birth dates of your contacts so the app can show their birthdays in a calendar of its own. It is requested separately, when you switch that setting on; without it the app never asks for it. The contacts are not stored or sent anywhere: only the resulting birthdays are kept, on your phone. |

When you add a Microsoft account (Outlook.com, Hotmail, Microsoft 365), you grant these permissions: your sign-in identity, email address and basic profile (`openid`, `email`, `profile`, `User.Read`), read and write access to your calendars and events (`Calendars.ReadWrite`) and keeping you signed in (`offline_access`).

The app uses this access only to provide the features you see in the app, at your request.

The app also asks Android for permission to show **notifications**, to schedule **exact alarms** so that event reminders arrive on time, and to re-schedule them after your phone restarts. The app does not read the calendar of the Android system (the calendar provider) and does not ask for access to your files or photos. Your contacts are read only through Google, with the separate permission above, if you switch on "Birthdays from contacts".

## How information is stored
- Your calendars, events, tasks and reminders are kept in a **local database on your phone** so the app works quickly and offline.
- Sign-in tokens are stored on your phone **encrypted** with a key kept in the Android Keystore.
- Your settings (theme, colours, default reminders, and so on) stay on your phone.
- Home screen widgets show data from that local database and make no network connection of their own.
- If Android backup is turned on on your phone, Android may back up the app's local data to your Google account under your phone's backup settings; sign-in tokens are excluded from that backup. Backup files you create yourself in the app (ZIP) contain your settings and, if you tick the box, your local calendars, events and tasks; they never contain your Google or Microsoft accounts or sign-in tokens, and they are saved where you choose.
- Nothing is sent to me or to any third party, except what the instant-notification relay receives (see below). The only network connections the app makes are to:
  - Google's services (`accounts.google.com`, `oauth2.googleapis.com`, `www.googleapis.com`, `tasks.googleapis.com`, and `people.googleapis.com` only if you switch on "Birthdays from contacts"), when you use a Google account;
  - Microsoft's services (`login.microsoftonline.com`, `graph.microsoft.com`), when you use a Microsoft account;
  - the web address of a calendar you subscribe to with a link (`.ics`), only to download that calendar, from whichever server hosts it;
  - the place-suggestion service Photon by komoot (`photon.komoot.io`, built on OpenStreetMap data), only while you type a location for an event: it receives the text you typed and the app's language, never your position or any other data;
  - GitHub (`api.github.com`), only to check whether a newer version of the app is available: it receives no personal data.

## Instant change notifications
- So that changes made in Google or Microsoft appear within seconds, the app asks Google Calendar (and Microsoft Graph) to send a change notification to a small relay that I run on Cloudflare Workers (free plan). The notification only says that a calendar changed; it contains no event content.
- The relay wakes your phone through Firebase Cloud Messaging (Google), and the app then syncs directly with Google or Microsoft.
- While an account is added, the relay stores your **email address**, random identifiers of the notification channels or subscriptions (and a random secret for Microsoft ones) and your phone's **Firebase Cloud Messaging token** (an anonymous identifier of the app on your phone). It never receives or stores event titles, descriptions, attendees, tasks or sign-in tokens.
- Firebase Analytics is not used. Removing an account, or using "Delete all data", stops the notifications and removes that account's data from the relay. If the relay is unavailable, the app keeps working and checks for changes regularly (every minute while it is open, every 15 minutes otherwise).

## Sharing
I do not sell, rent, share or transfer your data to anyone. Google and Microsoft receive the requests the app makes to their services, as described in [Google's Privacy Policy](https://policies.google.com/privacy) and [Microsoft's Privacy Statement](https://privacy.microsoft.com/privacystatement).

## Google API Services User Data Policy
Celander's use and transfer of information received from Google APIs adheres to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements. In particular, the data is used only to provide and improve the user-facing features of the app, is not used for advertising, is not transferred to others except as needed to provide those features or by law, and is not read by humans except where you explicitly share it or it is required for security or by law.

## Retention and deletion
- Data stays on your phone until you remove the account in the app, use "Delete all data" in Settings, or uninstall the app. Removing an account deletes its local calendars, events, tasks and tokens; the data stays at Google or Microsoft.
- You can revoke Celander's access to your Google account at any time at [myaccount.google.com/permissions](https://myaccount.google.com/permissions), and to your Microsoft account at [account.live.com/consent/Manage](https://account.live.com/consent/Manage) (personal accounts) or [myapps.microsoft.com](https://myapps.microsoft.com) (work or school accounts).

## Children
The app is not directed at children under 13 and I do not knowingly collect information from them.

## Changes
If this policy changes, the new version will be published on this page with an updated date.

## Contact
César Caballero · cesar.a.caballerot@gmail.com

---

# Política de privacidad {#español}

*Última actualización: 22 de noviembre de 2026* · [English](#privacy-policy) · [Deutsch](#deutsch)

Celander ("la app") es una aplicación de calendario y tareas para Android, desarrollada por César Caballero ("yo"). Esta política explica a qué información accede la app, cómo se usa y se guarda, y qué puedes decidir tú. Celander es un pequeño proyecto personal que se reparte como APK a unas pocas personas; no está publicada en Google Play.

## Resumen
- Celander se comunica **directamente** entre tu teléfono y Google o Microsoft. El único servidor mío es un pequeño servicio gratuito de avisos instantáneos (ver «Avisos instantáneos de cambios» más abajo) que nunca recibe tus calendarios, eventos ni tareas.
- Tus calendarios, eventos y tareas se quedan **en tu dispositivo**. Yo nunca los veo, los guardo, los vendo ni los comparto.
- Sin publicidad, sin analíticas, sin seguimiento y sin servicios de informes de errores.

## Información a la que accede la app
Al añadir una cuenta de Google concedes a la app estos permisos:

| Permiso | Para qué se usa |
|---|---|
| Dirección de correo de tu cuenta de Google y perfil básico (`openid`, `email`, `profile`) | Para mostrar qué cuenta está activa y distinguir cuentas, con tu nombre y tu foto de perfil. |
| Eventos de Google Calendar (`https://www.googleapis.com/auth/calendar.events`) | Para leer tus eventos y crearlos, editarlos y borrarlos, a petición tuya. |
| Lista de tus calendarios de Google (`https://www.googleapis.com/auth/calendar.calendarlist.readonly`) | Para mostrar qué calendarios tienes, con su nombre y su color. La app no puede cambiar esta lista. |
| Google Tasks (`https://www.googleapis.com/auth/tasks`) | Para leer tus listas de tareas y tareas y crearlas, editarlas, completarlas y borrarlas, a petición tuya. |
| Tus contactos de Google, solo lectura (`https://www.googleapis.com/auth/contacts.readonly`), **solo si activas «Cumpleaños de los contactos»** | Para leer los nombres y las fechas de nacimiento de tus contactos y mostrar sus cumpleaños en un calendario propio. Se pide aparte, cuando activas ese ajuste; si no lo activas, la app nunca lo pide. Los contactos no se guardan ni se envían a ningún sitio: solo se conservan los cumpleaños resultantes, en tu teléfono. |

Al añadir una cuenta de Microsoft (Outlook.com, Hotmail, Microsoft 365) concedes estos permisos: tu identidad de inicio de sesión, correo y perfil básico (`openid`, `email`, `profile`, `User.Read`), lectura y escritura de tus calendarios y eventos (`Calendars.ReadWrite`) y mantener la sesión iniciada (`offline_access`).

La app usa este acceso solo para ofrecer las funciones que ves en ella, a petición tuya.

La app también pide a Android permiso para mostrar **notificaciones**, programar **alarmas exactas** para que los recordatorios de eventos lleguen a su hora y reprogramarlos cuando el teléfono se reinicia. La app no lee el calendario del sistema Android (el proveedor de calendarios) y no pide acceso a tus archivos ni fotos. Tus contactos se leen solo a través de Google, con el permiso aparte de arriba, si activas «Cumpleaños de los contactos».

## Cómo se guarda la información
- Tus calendarios, eventos, tareas y recordatorios se guardan en una **base de datos local en tu teléfono** para que la app funcione rápido y sin conexión.
- Los tokens de inicio de sesión se guardan en tu teléfono **cifrados** con una clave del Android Keystore.
- Tus ajustes (tema, colores, recordatorios por defecto, etc.) se quedan en tu teléfono.
- Los widgets de la pantalla de inicio muestran datos de esa base de datos local y no hacen conexiones de red por su cuenta.
- Si la copia de seguridad de Android está activada en tu teléfono, Android puede guardar los datos locales de la app en tu cuenta de Google según los ajustes de copia de tu teléfono; los tokens de inicio de sesión no se incluyen en esa copia. Los archivos de copia (ZIP) que crees tú mismo en la app contienen tus ajustes y, si marcas la casilla, tus calendarios, eventos y tareas locales; nunca incluyen tus cuentas de Google o Microsoft ni los tokens de inicio de sesión, y se guardan donde elijas.
- No se envía nada a mí ni a terceros, salvo lo que recibe el servicio de avisos instantáneos (ver abajo). Las únicas conexiones de red de la app son con:
  - los servicios de Google (`accounts.google.com`, `oauth2.googleapis.com`, `www.googleapis.com`, `tasks.googleapis.com` y, solo si activas «Cumpleaños de los contactos», `people.googleapis.com`), si usas una cuenta de Google;
  - los servicios de Microsoft (`login.microsoftonline.com`, `graph.microsoft.com`), si usas una cuenta de Microsoft;
  - la dirección web de un calendario al que te suscribes con un enlace (`.ics`), solo para descargarlo, del servidor que lo aloje;
  - el servicio de sugerencias de lugares Photon de komoot (`photon.komoot.io`, con datos de OpenStreetMap), solo mientras escribes el lugar de un evento: recibe el texto que escribes y el idioma de la app, nunca tu posición ni otros datos;
  - GitHub (`api.github.com`), solo para comprobar si hay una versión nueva de la app: no recibe datos personales.

## Avisos instantáneos de cambios
- Para que los cambios hechos en Google o Microsoft aparezcan en segundos, la app pide a Google Calendar (y a Microsoft Graph) que envíe un aviso de cambio a un pequeño servicio que gestiono en Cloudflare Workers (plan gratuito). El aviso solo dice que un calendario ha cambiado; no contiene el contenido de ningún evento.
- Ese servicio despierta tu teléfono a través de Firebase Cloud Messaging (Google), y la app sincroniza entonces directamente con Google o Microsoft.
- Mientras haya una cuenta añadida, el servicio guarda tu **dirección de correo**, identificadores aleatorios de los canales o suscripciones de avisos (y un secreto aleatorio en los de Microsoft) y el **token de Firebase Cloud Messaging** de tu teléfono (un identificador anónimo de la app en tu teléfono). Nunca recibe ni guarda títulos, descripciones ni asistentes de eventos, tareas ni tokens de inicio de sesión.
- No se usa Firebase Analytics. Quitar una cuenta, o usar «Borrar todos los datos», detiene los avisos y borra los datos de esa cuenta del servicio. Si el servicio no está disponible, la app sigue funcionando y comprueba los cambios con regularidad (cada minuto con la app abierta, cada 15 minutos si no).

## Compartir datos
No vendo, alquilo, comparto ni transfiero tus datos a nadie. Google y Microsoft reciben las peticiones que la app hace a sus servicios, según la [Política de Privacidad de Google](https://policies.google.com/privacy) y la [Declaración de privacidad de Microsoft](https://privacy.microsoft.com/privacystatement).

## Política de datos de usuario de los servicios de las API de Google
El uso y la transferencia que Celander hace de la información recibida de las API de Google cumple la [Política de datos de usuario de los servicios de las API de Google](https://developers.google.com/terms/api-services-user-data-policy), incluidos los requisitos de uso limitado. En concreto, los datos se usan solo para ofrecer y mejorar las funciones de la app visibles para el usuario, no se usan para publicidad, no se transfieren a otros salvo lo necesario para ofrecer esas funciones o por ley, y no los lee ninguna persona, salvo que tú lo compartas expresamente o sea necesario por seguridad o por ley.

## Conservación y eliminación
- Los datos permanecen en tu teléfono hasta que quites la cuenta en la app, uses «Borrar todos los datos» en Ajustes o desinstales la app. Quitar una cuenta borra sus calendarios, eventos, tareas y tokens locales; los datos siguen en Google o en Microsoft.
- Puedes revocar el acceso de Celander a tu cuenta de Google en cualquier momento en [myaccount.google.com/permissions](https://myaccount.google.com/permissions), y a tu cuenta de Microsoft en [account.live.com/consent/Manage](https://account.live.com/consent/Manage) (cuentas personales) o [myapps.microsoft.com](https://myapps.microsoft.com) (cuentas de trabajo o escuela).

## Menores
La app no está dirigida a menores de 13 años y no recopilo conscientemente información de ellos.

## Cambios
Si esta política cambia, la nueva versión se publicará en esta página con la fecha actualizada.

## Contacto
César Caballero · cesar.a.caballerot@gmail.com

---

# Datenschutzerklärung {#deutsch}

*Zuletzt aktualisiert: 22. November 2026* · [English](#privacy-policy) · [Español](#español)

Celander („die App“) ist eine Kalender- und Aufgaben-App für Android, entwickelt von César Caballero („ich“). Diese Erklärung beschreibt, auf welche Informationen die App zugreift, wie sie verwendet und gespeichert werden und welche Möglichkeiten du hast. Celander ist ein kleines privates Projekt, das als APK an ein paar Personen weitergegeben wird; es ist nicht bei Google Play veröffentlicht.

## Kurz gesagt
- Celander kommuniziert **direkt** zwischen deinem Telefon und Google bzw. Microsoft. Mein einziger Server ist ein kleiner, kostenloser Vermittlungsdienst für Sofortbenachrichtigungen (siehe „Sofortbenachrichtigungen bei Änderungen“ unten), der nie deine Kalender, Termine oder Aufgaben erhält.
- Deine Kalender, Termine und Aufgaben bleiben **auf deinem Gerät**. Ich sehe, speichere, verkaufe oder teile sie nie.
- Keine Werbung, keine Analyse, kein Tracking und keine Dienste zur Absturzberichterstattung.

## Auf welche Informationen die App zugreift
Wenn du ein Google-Konto hinzufügst, erteilst du der App diese Berechtigungen:

| Berechtigung | Wofür sie genutzt wird |
|---|---|
| E-Mail-Adresse deines Google-Kontos und Basisprofil (`openid`, `email`, `profile`) | Um anzuzeigen, welches Konto angemeldet ist, und Konten zu unterscheiden, mit deinem Namen und Profilbild. |
| Google-Kalendertermine (`https://www.googleapis.com/auth/calendar.events`) | Um deine Termine zu lesen und auf deinen Wunsch anzulegen, zu ändern und zu löschen. |
| Liste deiner Google-Kalender (`https://www.googleapis.com/auth/calendar.calendarlist.readonly`) | Um anzuzeigen, welche Kalender du hast, mit Namen und Farben. Die App kann diese Liste nicht ändern. |
| Google Tasks (`https://www.googleapis.com/auth/tasks`) | Um deine Aufgabenlisten und Aufgaben zu lesen und auf deinen Wunsch anzulegen, zu ändern, abzuhaken und zu löschen. |
| Deine Google-Kontakte, nur lesend (`https://www.googleapis.com/auth/contacts.readonly`), **nur wenn du „Geburtstage aus Kontakten“ einschaltest** | Um Namen und Geburtsdaten deiner Kontakte zu lesen und ihre Geburtstage in einem eigenen Kalender anzuzeigen. Die Berechtigung wird getrennt angefragt, wenn du diese Einstellung einschaltest; ohne sie fragt die App nie danach. Die Kontakte werden nirgendwohin gesendet: nur die daraus entstehenden Geburtstage bleiben auf deinem Handy gespeichert. |

Wenn du ein Microsoft-Konto hinzufügst (Outlook.com, Hotmail, Microsoft 365), erteilst du diese Berechtigungen: deine Anmeldeidentität, E-Mail-Adresse und das Basisprofil (`openid`, `email`, `profile`, `User.Read`), Lese- und Schreibzugriff auf deine Kalender und Termine (`Calendars.ReadWrite`) sowie das dauerhafte Angemeldetbleiben (`offline_access`).

Die App nutzt diesen Zugriff nur, um dir die Funktionen bereitzustellen, die du in der App siehst, auf deinen Wunsch.

Außerdem bittet die App Android um die Erlaubnis, **Benachrichtigungen** anzuzeigen, **exakte Alarme** zu planen, damit Termin-Erinnerungen pünktlich kommen, und sie nach einem Neustart des Telefons neu zu planen. Die App liest nicht den Kalender des Android-Systems (den Kalenderanbieter) und fragt nicht nach Zugriff auf deine Dateien oder Fotos. Deine Kontakte werden nur über Google gelesen, mit der oben genannten separaten Berechtigung, wenn du „Geburtstage aus Kontakten“ einschaltest.

## Wie Informationen gespeichert werden
- Deine Kalender, Termine, Aufgaben und Erinnerungen liegen in einer **lokalen Datenbank auf deinem Telefon**, damit die App schnell und auch offline funktioniert.
- Anmelde-Tokens werden auf deinem Telefon **verschlüsselt** gespeichert, mit einem Schlüssel im Android Keystore.
- Deine Einstellungen (Design, Farben, Standard-Erinnerungen usw.) bleiben auf deinem Telefon.
- Widgets auf dem Startbildschirm zeigen Daten aus dieser lokalen Datenbank und bauen selbst keine Netzwerkverbindung auf.
- Wenn die Android-Datensicherung auf deinem Telefon eingeschaltet ist, kann Android die lokalen Daten der App gemäß den Sicherungseinstellungen deines Telefons in deinem Google-Konto sichern; Anmelde-Tokens sind von dieser Sicherung ausgenommen. Sicherungsdateien (ZIP), die du selbst in der App erstellst, enthalten deine Einstellungen und, wenn du das Kästchen anhakst, deine lokalen Kalender, Termine und Aufgaben; sie enthalten nie deine Google- oder Microsoft-Konten oder Anmelde-Tokens und werden dort gespeichert, wo du es festlegst.
- Es wird nichts an mich oder an Dritte gesendet, außer dem, was der Vermittlungsdienst für Sofortbenachrichtigungen erhält (siehe unten). Die App verbindet sich nur mit:
  - den Diensten von Google (`accounts.google.com`, `oauth2.googleapis.com`, `www.googleapis.com`, `tasks.googleapis.com` und, nur wenn du „Geburtstage aus Kontakten“ einschaltest, `people.googleapis.com`), wenn du ein Google-Konto nutzt;
  - den Diensten von Microsoft (`login.microsoftonline.com`, `graph.microsoft.com`), wenn du ein Microsoft-Konto nutzt;
  - der Webadresse eines Kalenders, den du per Link (`.ics`) abonnierst, nur um diesen Kalender herunterzuladen, vom Server, der ihn bereitstellt;
  - dem Ortsvorschlagsdienst Photon von komoot (`photon.komoot.io`, auf Basis von OpenStreetMap-Daten), nur während du einen Ort für einen Termin eintippst: Er erhält den eingetippten Text und die Sprache der App, nie deinen Standort oder andere Daten;
  - GitHub (`api.github.com`), nur um zu prüfen, ob eine neuere Version der App verfügbar ist: Es werden keine personenbezogenen Daten übermittelt.

## Sofortbenachrichtigungen bei Änderungen
- Damit Änderungen in Google oder Microsoft innerhalb von Sekunden erscheinen, bittet die App Google Kalender (und Microsoft Graph), eine Änderungsmeldung an einen kleinen Vermittlungsdienst zu senden, den ich auf Cloudflare Workers (kostenloser Tarif) betreibe. Die Meldung sagt nur, dass sich ein Kalender geändert hat; sie enthält keine Termininhalte.
- Der Dienst weckt dein Telefon über Firebase Cloud Messaging (Google), und die App synchronisiert dann direkt mit Google bzw. Microsoft.
- Solange ein Konto hinzugefügt ist, speichert der Dienst deine **E-Mail-Adresse**, zufällige Kennungen der Benachrichtigungskanäle bzw. Abonnements (bei Microsoft zusätzlich ein zufälliges Geheimnis) und das **Firebase-Cloud-Messaging-Token** deines Telefons (eine anonyme Kennung der App auf deinem Telefon). Er erhält und speichert nie Termintitel, Beschreibungen, Teilnehmer, Aufgaben oder Anmelde-Tokens.
- Firebase Analytics wird nicht verwendet. Wenn du ein Konto entfernst oder „Alle Daten löschen“ wählst, enden die Benachrichtigungen und die Daten dieses Kontos werden aus dem Dienst gelöscht. Ist der Dienst nicht erreichbar, funktioniert die App weiter und prüft regelmäßig auf Änderungen (jede Minute, solange sie geöffnet ist, sonst alle 15 Minuten).

## Weitergabe
Ich verkaufe, vermiete, teile oder übertrage deine Daten an niemanden. Google und Microsoft erhalten die Anfragen, die die App an ihre Dienste stellt, gemäß der [Datenschutzerklärung von Google](https://policies.google.com/privacy) und der [Datenschutzbestimmungen von Microsoft](https://privacy.microsoft.com/privacystatement).

## Google API Services User Data Policy
Die Nutzung und Übertragung von Informationen, die Celander aus Google-APIs erhält, entspricht der [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), einschließlich der Anforderungen zur eingeschränkten Nutzung (Limited Use). Insbesondere werden die Daten nur verwendet, um die für dich sichtbaren Funktionen der App bereitzustellen und zu verbessern, nicht für Werbung genutzt, nicht an andere übertragen, außer soweit für diese Funktionen oder per Gesetz nötig, und von niemandem gelesen, außer wenn du sie ausdrücklich teilst oder es aus Sicherheitsgründen oder per Gesetz erforderlich ist.

## Speicherdauer und Löschung
- Die Daten bleiben auf deinem Telefon, bis du das Konto in der App entfernst, in den Einstellungen „Alle Daten löschen“ wählst oder die App deinstallierst. Beim Entfernen eines Kontos werden seine lokalen Kalender, Termine, Aufgaben und Tokens gelöscht; die Daten bleiben bei Google bzw. Microsoft erhalten.
- Du kannst den Zugriff von Celander auf dein Google-Konto jederzeit unter [myaccount.google.com/permissions](https://myaccount.google.com/permissions) widerrufen, auf dein Microsoft-Konto unter [account.live.com/consent/Manage](https://account.live.com/consent/Manage) (private Konten) oder [myapps.microsoft.com](https://myapps.microsoft.com) (Geschäfts- oder Schulkonten).

## Kinder
Die App richtet sich nicht an Kinder unter 13 Jahren, und ich erhebe wissentlich keine Informationen von ihnen.

## Änderungen
Wenn sich diese Erklärung ändert, wird die neue Fassung mit aktualisiertem Datum auf dieser Seite veröffentlicht.

## Kontakt
César Caballero · cesar.a.caballerot@gmail.com
