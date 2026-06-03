# Dieses Dockerfile wird verwendet, um einen Container (eine isolierte Umgebung) für den Server zu erstellen.
# Ein Container ist wie ein kleiner, eigenständiger Computer, auf dem nur unser Programm läuft.

# Wir verwenden Java 17 als Basis für unser Programm. Das ist wie das Betriebssystem für unseren Code.
FROM eclipse-temurin:17-jdk-alpine

# Das Arbeitsverzeichnis im Container wird auf /app festgelegt. Hier werden unsere Dateien liegen.
WORKDIR /app

# Wir kopieren alle Dateien aus unserem aktuellen Ordner auf dem PC in den Container (in den Ordner /app).
COPY . .

# Dieser Befehl übersetzt unseren geschriebenen Java-Code (AgarServer.java) in eine Datei, die der Computer ausführen kann.
RUN javac AgarServer.java

# Wenn der Container gestartet wird, wird dieser Befehl ausgeführt, um unser Spiel endgültig zu starten.
CMD ["java", "AgarServer"]
