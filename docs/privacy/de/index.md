---
title: Datenschutzerklärung - Posts AI Manager
---

[English](../)

# Datenschutzerklärung - Posts AI Manager

**Gültig ab:** **[PLATZHALTER: Datum des Inkrafttretens]**
**App:** Posts AI Manager (Paket `com.postsaimanager`), Version 1.0.0
**Kontakt:** alyabroudy1@gmail.com
**Anbieter (Impressum):** **[PLATZHALTER: Name und Anschrift des Anbieters]**

Posts AI Manager hilft Ihnen, Ihre Briefpost auf Ihrem eigenen Smartphone zu scannen und zu verstehen. Die App ist darauf ausgelegt, offline zu funktionieren. Diese Erklärung beschreibt verständlich, was die App mit Ihren Informationen macht.

## 1. Kurzfassung

- Ihre Dokumente, Fotos, Texte, Chats und persönlichen Angaben werden **ausschließlich auf Ihrem Gerät** verarbeitet. Die App hat keinen Server, wir erhalten diese Daten nie.
- Kein Konto, keine Registrierung, keine Werbung, keine Analyse-Dienste, kein Absturzberichts-Dienst; wir verkaufen oder teilen Ihre Daten nicht.
- Das Internet wird **nur zum Herunterladen von KI-Modelldateien** genutzt (von Hugging Face). Ihre Dokumente werden dabei nie übertragen.
- Eine optionale App-Sperre schützt die App per Fingerabdruck, Gesicht, PIN, Muster oder Passwort.
- Sie können Dokumente, Chats und Profile in der App löschen; durch Deinstallieren werden die App-Daten vom Telefon entfernt.
- Die Geräte-Datensicherung von Android kann App-Daten in Ihr Google-Konto sichern, wenn Sie diese aktiviert haben (siehe Abschnitt 6).

## 2. Was die App auf Ihrem Gerät verarbeitet und speichert

Alles Folgende bleibt im privaten Speicher der App auf Ihrem Telefon:

- **Dokumente:** die gescannten Seiten (Bilder) und von Ihnen erstellte PDFs.
- **Erkannter Text:** Text, den die Texterkennung (OCR) auf dem Gerät aus Ihren Seiten liest.
- **Extrahierte Informationen:** Werte, die die KI auf dem Gerät aus einem Dokument liest, etwa Absender, Empfänger, Daten, Aktenzeichen, Beträge und eine kurze Zusammenfassung, sowie Ihre Entscheidung dazu (Bestätigen, Bearbeiten, Ignorieren).
- **Chats:** Ihre Fragen an den Dokumenten-Assistenten, seine Antworten und die zitierten Textstellen.
- **Profile und gespeicherte Angaben:** Namen und Angaben zu Personen oder Haushalten, die Sie anlegen, einschließlich Angaben, die Sie oder die App aus Dokumenten speichern. Diese können sensibel sein (z. B. Adressen, Geburtsdaten, Ausweis- oder Versicherungsnummern), wenn Sie sie speichern.
- **Einstellungen:** Sprache, App-Sperre und ähnliche Präferenzen.
- **KI-Modelldateien**, die Sie heruntergeladen haben.

Die App liest weder Kontakte, Standort, Mikrofon, SMS, Anrufliste noch Daten anderer Apps. Sie liest nur Fotos oder Dateien, die Sie zum Scannen auswählen.

## 3. Wie Ihre Informationen verarbeitet werden (auf dem Gerät)

- **Scannen:** Seiten werden mit dem Dokumentenscanner von Google Play-Diensten aufgenommen. Das Scannen erfolgt auf Ihrem Gerät; die Bilder werden an unsere App übergeben.
- **Texterkennung:** erfolgt auf dem Gerät mit Google ML Kit Texterkennung, mit einem in der App enthaltenen Modell. Weder Seitenbilder noch Text werden an Google oder uns gesendet.
- **KI-Extraktion, Zusammenfassungen und Chat:** erfolgen mit KI-Modellen, die auf Ihrem Telefon laufen (über llama.cpp; für die Suche ein Einbettungsmodell mit ONNX Runtime). Anfragen und Dokumente verlassen das Gerät nicht.

Da ein kleines Modell auf dem Gerät arbeitet, können Ergebnisse falsch oder unvollständig sein. Prüfen Sie wichtige Werte (Beträge, Fristen, Aktenzeichen) stets am Originalbrief.

## 4. Internetnutzung

Die App deklariert die Berechtigung INTERNET für einen Zweck: **Herunterladen von KI-Modelldateien**.

- **Wohin:** `huggingface.co` (Hugging Face, Inc.), per HTTPS. Die in der integrierten Liste genannten Modelldateien werden aus Hugging-Face-Repositories geladen. Ein kleines Text-Einbettungsmodell wird ebenfalls von Hugging Face geladen.
- **Was gesendet wird:** eine übliche HTTPS-Anfrage nach einer Datei (mit der IP-Adresse Ihres Geräts und üblichen technischen Headern, wie sie jede Web-Anfrage enthält; bei fortgesetzten Downloads zusätzlich ein Byte-Bereichs-Header). **Es werden keine Dokumente, Texte, Chats, Profile oder persönlichen Daten gesendet.** Die App sendet keine Kennung Ihrer Person.
- **Was Hugging Face tun kann:** Hugging Face erhält als Host die Anfrage und kann sie nach der eigenen Datenschutzerklärung (https://huggingface.co/privacy) protokollieren. Darauf haben wir keinen Einfluss.
- **Integrität:** Downloads werden vor der Nutzung mit einer hinterlegten Prüfsumme geprüft.
- Nach dem Download der Modelle arbeitet die App vollständig offline.
- Google Play-Dienste können eigenständig und nach Googles Datenschutzerklärung Komponenten wie den Dokumentenscanner laden oder aktualisieren. Das ist ein Google-Systemdienst; Ihre Dokumente werden dorthin nicht gesendet.

Die App enthält keine Werbe-, Analyse-, Tracking- oder Absturzberichts-Bibliotheken. Wir kontaktieren keinen anderen Server. Die App hat keinen Bildschirm zum Durchsuchen oder Hinzufügen von Modellquellen: Sie fordert nur die oben genannten festen Modelldateien an.

## 5. Berechtigungen und Zweck

| Berechtigung | Zweck |
|---|---|
| Kamera | Zum Fotografieren von Briefen. Optional: Sie können stattdessen Bilder importieren. |
| Internet / Netzwerkstatus | Zum Herunterladen von KI-Modelldateien und zur Prüfung, ob eine Verbindung besteht. |
| Vordergrunddienst (Datensynchronisierung) | Damit ein großer Modell-Download mit sichtbarer Benachrichtigung auch bei ausgeschaltetem Bildschirm weiterläuft. |
| Benachrichtigungen | Zur Anzeige von Download-Fortschritt und -Abschluss. Ab Android 13 fragt die App unmittelbar vor dem ersten Modell-Download, mit einem erklärenden Satz. Optional: Der Download funktioniert auch, wenn Sie ablehnen. |
| Wake-Lock, Start nach Neustart | Von der Hintergrundarbeits-Bibliothek von Android (WorkManager) deklariert, damit unfertige Downloads und Verarbeitungen fortgesetzt werden können. |

Die App fordert keine Berechtigungen für Speicher, Kontakte, Standort oder Mikrofon an.

## 6. Android-Datensicherung (bitte lesen)

Die App erlaubt die Standard-Datensicherung von Android (`allowBackup`). Das bedeutet: **Wenn die Sicherung auf Ihrem Telefon aktiviert ist** (z. B. "Sicherung durch Google One"), kann Android App-Daten in Ihre Google-Konto-Sicherung aufnehmen und beim Einrichten eines neuen Telefons wiederherstellen. Je nach Android-Version und Gerät können dazu die App-Datenbank (Dokumente, extrahierte Felder, Chats, Profile und gespeicherte Angaben) und Dateien gehören. Die Sicherung wird von Google erstellt und gespeichert, nach Googles Bedingungen; wir haben keinen Zugriff.

So verhindern Sie das:
- Geräte-Sicherung ausschalten: **Einstellungen > Google > Alle Dienste > Sicherung** (Bezeichnungen je nach Gerät unterschiedlich), oder
- dort vorhandene Sicherungen löschen.

KI-Modelle sind groß; Android nimmt sie eventuell nicht in die Sicherung auf, sie lassen sich erneut herunterladen.

## 7. Weitergabe

Wir geben Ihre Informationen nicht weiter. Daten verlassen die App nur, wenn **Sie** es veranlassen, etwa mit "Als PDF teilen" oder "Öffnen", wodurch die Datei an die von Ihnen gewählte App übergeben wird. Für deren Umgang gilt deren Datenschutzerklärung.

**KI-Antwort melden.** Jede KI-Antwort im Chat und die KI-Zusammenfassung eines Dokuments hat eine Schaltfläche "Diese Antwort melden". Sie öffnet einen Entwurf in Ihrer E-Mail-App, adressiert an alyabroudy1@gmail.com, mit einer kurzen Frage, was falsch war. Die App sendet nichts: Sie lesen den Entwurf und senden ihn selbst ab oder schließen ihn. Der Text der Antwort wird dem Entwurf nur beigefügt, wenn Sie im Bestätigungsdialog "Antworttext beifügen (kann persönliche Daten enthalten)" ankreuzen; standardmäßig ist das aus. Wenn Sie die E-Mail absenden, erhalten wir, was darin steht (Ihre Adresse und Ihren Text) und nutzen es nur, um dem Problem nachzugehen und die App zu verbessern. Ihr E-Mail-Anbieter behandelt die Nachricht nach seiner eigenen Datenschutzerklärung.

## 8. Speicherdauer und Löschung

- Daten bleiben auf Ihrem Gerät, bis Sie sie löschen. Wir setzen keine Frist und haben keine Kopie.
- **Beim Löschen eines Dokuments** wandert es in den **Papierkorb**. Dort bleibt es 30 Tage, kann wiederhergestellt werden und wird danach endgültig gelöscht. Sie können auch sofort aus dem Papierkorb löschen.
- Chats, Profile und gespeicherte Angaben können Sie in der App löschen.
- **Deinstallieren** löscht die gespeicherten App-Daten vom Telefon. Kopien in einer Android-/Google-Sicherung (Abschnitt 6) verwalten Sie in Ihrem Google-Konto.
- Da wir keine Daten über Sie halten, gibt es bei uns nichts zu löschen. Nutzen Sie die Löschfunktionen oder die Deinstallation. Fragen an alyabroudy1@gmail.com.

## 9. Sicherheit

- Daten liegen im privaten App-Speicher, den andere Apps normalerweise nicht lesen können.
- **App-Sperre (optional):** In den Einstellungen können Sie Fingerabdruck, Gesicht, PIN, Muster oder Passwort zum Öffnen verlangen und festlegen, wie lange die App im Hintergrund bleiben darf, bevor sie erneut sperrt. Die App-Sperre nutzt die Bildschirmsperre und Biometrie Ihres Telefons; wir sehen oder speichern diese nie.
- Modell-Downloads nutzen HTTPS und werden per Prüfsumme verifiziert.
- Hinweis: Die lokale Datenbank wird von der App nicht zusätzlich verschlüsselt; der Schutz beruht auf der Geräteverschlüsselung von Android und Ihrer Bildschirmsperre. Halten Sie Ihr Telefon gesperrt und aktuell.
- Kein System ist vollkommen sicher; absolute Sicherheit können wir nicht garantieren.

## 10. Kinder

Die App richtet sich nicht an Kinder und ist nicht für Personen unter 16 Jahren (bzw. dem Mindestalter in Ihrem Land) gedacht. Wir erheben wissentlich keine Daten von Kindern - und von niemandem sonst.

## 11. Ihre Rechte

Da wir Ihre personenbezogenen Daten weder erheben noch speichern, gibt es in der Regel nichts, was wir auskunftsweise offenlegen, berichtigen oder exportieren könnten. Ihre Daten steuern Sie direkt in der App. Falls Sie in der EU/im EWR/in Großbritannien sind und glauben, dass wir Daten über Sie haben (z. B. weil Sie uns kontaktiert haben), haben Sie die Rechte auf Auskunft, Berichtigung, Löschung, Einschränkung, Datenübertragbarkeit und Widerspruch sowie das Recht auf Beschwerde bei Ihrer Datenschutzbehörde. Kontakt: alyabroudy1@gmail.com.

## 12. Änderungen

Ändert sich diese Erklärung, wird die neue Fassung unter https://alyabroudy1.github.io/PostsAiManager/privacy/de/ mit neuem Datum veröffentlicht, bei wesentlichen Änderungen auch in den Versionshinweisen.

## 13. Kontakt

alyabroudy1@gmail.com  
**[PLATZHALTER: Name und Anschrift des Anbieters]**
