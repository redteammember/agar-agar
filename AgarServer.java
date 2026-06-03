import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

// Dies ist das Hauptprogramm für unseren Spielserver. 
// Ein Server ist wie ein unsichtbarer Schiedsrichter, der alle Spieler miteinander verbindet.
public class AgarServer {

    // Der "Port" ist wie eine Türnummer an einem Haus. 
    // Wenn ein Spieler sich verbinden will, klopft er an Türnummer 8080.
    private static final int PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
    
    // In diesem Ordner ("public") liegen unsere Bilder, Webseiten (HTML) und Designs (CSS).
    // Der Server wird diese Dateien an die Browser der Spieler schicken.
    private static final String WEB_ROOT = "public";

    // Das ist der "Startknopf" für das Programm. Wenn wir den Server starten, läuft als erstes diese Funktion.
    public static void main(String[] args) {
        // Erstelle eine neue Spielwelt (GameEngine) und starte sie im Hintergrund
        GameEngine engine = new GameEngine();
        engine.start();

        System.out.println("Starte AgarServer an der Türnummer (Port) " + PORT);
        
        // Öffne die Tür (ServerSocket) und warte auf Klopfzeichen von Spielern
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                // Ein Spieler (client) hat angeklopft und wurde hereingelassen!
                Socket client = serverSocket.accept();
                // Wir weisen dem neuen Spieler einen persönlichen "Betreuer" (ClientHandler) zu, 
                // der sich im Hintergrund um ihn kümmert, damit der Schiedsrichter (Server) weiter auf neue Spieler warten kann.
                new Thread(new ClientHandler(client, engine)).start();
            }
        } catch (IOException e) {
            // Falls etwas schiefgeht, zeige den Fehler an
            e.printStackTrace();
        }
    }

    // Dies ist der "Betreuer" für einen einzelnen Spieler.
    static class ClientHandler implements Runnable {
        private final Socket socket; // Die Telefonleitung zum Spieler
        private final GameEngine engine; // Das eigentliche Spiel
        private InputStream in; // Hier kommt rein, was der Spieler sagt (z.B. Mausbewegungen)
        private OutputStream out; // Hier schicken wir Antworten an den Spieler raus (z.B. neues Bild der Karte)

        public ClientHandler(Socket socket, GameEngine engine) {
            this.socket = socket;
            this.engine = engine;
        }

        // Das ist die Arbeit, die der Betreuer macht
        @Override
        public void run() {
            try {
                // Verbinde die "Ohren" (in) und "Mund" (out) des Servers mit der Leitung
                in = socket.getInputStream();
                out = socket.getOutputStream();

                // Lese, was der Browser des Spielers als Erstes verlangt
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String requestLine = reader.readLine();
                if (requestLine == null) return; // Wenn er nichts sagt, beenden.

                System.out.println("Browser fragt nach: " + requestLine);

                // Lese zusätzliche Notizen (Headers), die der Browser uns schickt
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(":");
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                    }
                }

                // Prüfe, ob der Browser einfach nur eine normale Webseite will (HTTP) 
                // oder ob er eine dauerhafte schnelle "Standleitung" für das Spiel aufbauen will (WebSocket)
                if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                    handleWebSocket(headers); // Standleitung aufbauen!
                } else {
                    handleHttp(requestLine); // Normale Webseite (index.html) schicken
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Diese Funktion kümmert sich darum, Dateien wie Bilder oder HTML an den Browser zu senden
        private void handleHttp(String requestLine) throws IOException {
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1]; // Welche Datei will der Browser haben?
            // Wenn er keine genaue Datei nennt, geben wir ihm unsere Startseite (index.html)
            if (path.equals("/")) path = "/index.html";

            File file = new File(WEB_ROOT + path); // Suche die Datei im Ordner "public"
            if (file.exists() && !file.isDirectory()) {
                // Wir haben die Datei gefunden! Wir müssen dem Browser sagen, was für ein Typ sie ist
                String contentType = "text/plain";
                if (path.endsWith(".html")) contentType = "text/html";
                else if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "application/javascript";

                // Lese die Datei ein und schicke sie mitsamt einem "Alles OK (200)"-Stempel an den Browser
                byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
                String response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + content.length + "\r\n" +
                        "\r\n";
                out.write(response.getBytes());
                out.write(content);
            } else {
                // Oh weh, Datei nicht gefunden. Wir schicken einen "404 Not Found" Fehler.
                String response = "HTTP/1.1 404 Not Found\r\n\r\n404 File Not Found";
                out.write(response.getBytes());
            }
            out.flush(); // Sicherstellen, dass das Paket losgeschickt wurde
            socket.close(); // Wir hängen das Telefon erst mal auf (für WebSockets bleibt es offen)
        }

        // Diese Funktion baut eine extrem schnelle, offene Dauerverbindung (WebSocket) auf.
        // Das ist wichtig, weil das Spiel in Echtzeit extrem viele Daten hin- und herschicken muss.
        private void handleWebSocket(Map<String, String> headers) throws Exception {
            // Geheimer Sicherheitshandschlag: Wir beweisen dem Browser, dass wir ein echter Spieleserver sind
            String key = headers.get("sec-websocket-key");
            String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest((key + magic).getBytes("UTF-8"));
            String accept = Base64.getEncoder().encodeToString(hash);

            String handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(handshake.getBytes()); // Handschlag erwidern!
            out.flush();

            // Der Spieler bekommt eine geheime Zufalls-Nummer (Session ID)
            String sessionId = UUID.randomUUID().toString();
            Player player = new Player(sessionId, "Guest"); // Erstelle einen neuen Spieler auf der Karte
            engine.addPlayer(sessionId, player, out); // Sag der Spielwelt, dass ein neuer Spieler da ist

            try {
                // Dies ist eine Endlosschleife, die nur dann aufhört, wenn der Spieler das Fenster schließt
                while (true) {
                    // Warte auf eine Nachricht (z.B. "Ich bewege die Maus nach links") vom Spieler
                    byte[] payload = readWebSocketFrame();
                    if (payload == null) break; // Wenn nichts kommt, brich ab
                    String message = new String(payload, "UTF-8"); // Übersetze die Daten in normalen Text
                    engine.handleMessage(sessionId, message); // Gib den Befehl ans Spiel weiter
                }
            } finally {
                // Wenn die Schleife abbricht (Spieler geht weg), lösche den Spieler von der Karte
                engine.removePlayer(sessionId);
                socket.close();
            }
        }

        // Ein komplizierter Block, der notwendig ist, um die speziellen WebSocket-Nachrichten 
        // vom Browser (der sie oft "maskiert" bzw. verschlüsselt) zu lesen und zu entschlüsseln.
        private byte[] readWebSocketFrame() throws IOException {
            int b1 = in.read();
            if (b1 == -1) return null;
            int opcode = b1 & 0x0F;
            if (opcode == 8) return null; // Der Code "8" bedeutet: "Ich lege jetzt auf" (Close frame)

            int b2 = in.read();
            boolean masked = (b2 & 0x80) != 0; // Ist die Nachricht maskiert?
            int payloadLen = b2 & 0x7F; // Wie lang ist die Nachricht?

            if (payloadLen == 126) {
                payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (payloadLen == 127) {
                for (int i = 0; i < 4; i++) in.read(); // Ignoriere sehr lange Zahlen
                payloadLen = ((in.read() & 0xFF) << 24) | ((in.read() & 0xFF) << 16) | ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            }

            byte[] mask = new byte[4];
            if (masked) {
                in.read(mask); // Lese den "Schlüssel" (Maske) zum Entschlüsseln
            }

            byte[] payload = new byte[payloadLen];
            int read = 0;
            while (read < payloadLen) {
                read += in.read(payload, read, payloadLen - read); // Lese die eigentliche Nachricht
            }

            // Entschlüssele die Nachricht mit dem Schlüssel
            if (masked) {
                for (int i = 0; i < payloadLen; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            return payload; // Gib die entschlüsselte Nachricht zurück
        }
    }

    // --- SPIELLOGIK (Hier passieren die echten Regeln des Spiels) ---

    // Die "Bauanleitung" für einen Spieler
    static class Player {
        String id; // Seine geheime Nummer
        String name; // Sein Name im Spiel
        double x, y, targetX, targetY; // Seine aktuelle Position (x,y) und wo er hin will (targetX, targetY)
        double radius = 20; // Jeder fängt klein an (Größe 20)
        String color; // Seine Farbe

        // Wenn ein neuer Spieler erstellt wird
        Player(String id, String name) {
            this.id = id;
            this.name = name;
            // Wähle eine zufällige Farbe für den Spieler
            this.color = "#" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
            // Setze ihn an eine zufällige Stelle auf der riesigen Karte (bis zu 5000x5000 groß)
            this.x = Math.random() * 5000;
            this.y = Math.random() * 5000;
            this.targetX = x;
            this.targetY = y;
        }

        // Diese Funktion bewegt den Spieler einen kleinen Schritt auf sein Ziel zu
        public void update() {
            double dx = targetX - x;
            double dy = targetY - y;
            // Berechne die genaue Entfernung zum Ziel
            double dist = Math.sqrt(dx * dx + dy * dy);
            // Je größer (radius) der Spieler ist, desto langsamer (speed) wird er
            double speed = Math.max(1.0, 100.0 / radius);
            
            // Wenn wir noch nicht am Ziel sind, gehe einen Schritt in die Richtung
            if (dist > speed) {
                x += (dx / dist) * speed;
                y += (dy / dist) * speed;
            } else {
                // Wenn wir schon da sind, bleib genau dort stehen
                x = targetX;
                y = targetY;
            }
        }
    }

    // Die "Bauanleitung" für ein Futterstück (die kleinen Punkte)
    static class Food {
        String id = UUID.randomUUID().toString(); // Zufällige Nummer für das Futter
        double x = Math.random() * 5000; // Zufällige Position auf der großen Karte
        double y = Math.random() * 5000;
        String color = "#" + Integer.toHexString((int) (Math.random() * 0xFFFFFF)); // Zufällige Farbe
    }

    // Dies ist das "Herz" des Spiels. Es schlägt ständig und kontrolliert alles.
    static class GameEngine extends Thread {
        // Eine Liste aller Spieler, die gerade online sind
        Map<String, Player> players = new ConcurrentHashMap<>();
        // Eine Liste der Leitungen (Mundstücke), um Nachrichten an die Spieler zu senden
        Map<String, OutputStream> clientOutputs = new ConcurrentHashMap<>();
        // Eine Liste für all das Futter, das gerade auf der Karte liegt
        List<Food> foods = new CopyOnWriteArrayList<>();

        // Wenn die Welt erschaffen wird
        public GameEngine() {
            // ... streuen wir sofort 1000 Futterstücke auf die Karte
            for (int i = 0; i < 1000; i++) foods.add(new Food());
        }

        // Ein neuer Spieler kommt dazu
        public void addPlayer(String id, Player p, OutputStream out) {
            players.put(id, p);
            clientOutputs.put(id, out);
        }

        // Ein Spieler verlässt das Spiel
        public void removePlayer(String id) {
            players.remove(id);
            clientOutputs.remove(id);
        }

        // Hier verarbeiten wir Nachrichten von den Spielern (z.B. "Ich will nach links")
        public void handleMessage(String id, String msg) {
            Player p = players.get(id); // Welcher Spieler hat geschrieben?
            if (p == null) return;

            // Wir suchen im Text nach bestimmten Schlüsselwörtern, um zu wissen, was gemeint ist
            if (msg.contains("\"type\":\"target\"")) {
                // Der Spieler hat seine Maus bewegt. Lese die neuen x- und y-Ziele aus.
                Matcher mx = Pattern.compile("\"x\":([0-9.]+)").matcher(msg);
                Matcher my = Pattern.compile("\"y\":([0-9.]+)").matcher(msg);
                if (mx.find() && my.find()) {
                    p.targetX = Double.parseDouble(mx.group(1));
                    p.targetY = Double.parseDouble(my.group(1));
                }
            } else if (msg.contains("\"type\":\"join\"")) {
                // Der Spieler hat sich gerade im Hauptmenü einen Namen gegeben
                Matcher m = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(msg);
                if (m.find()) {
                    p.name = m.group(1);
                }
            }
        }

        // Das ist die Zeitschleife (der "Herzschlag") der Welt
        @Override
        public void run() {
            while (true) {
                try {
                    tick(); // Ein einzelner "Tick" (Schlag) im Spiel
                    Thread.sleep(16); // Warte 16 Millisekunden (das ergibt ca. 60 Bilder pro Sekunde)
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Was bei jedem "Tick" passiert:
        private void tick() {
            // 1. Bewege alle Spieler ein Stückchen auf ihr Ziel zu
            for (Player p : players.values()) p.update();

            // 2. Überprüfe, ob jemand Futter gegessen hat (Kollisionen)
            List<Food> eaten = new ArrayList<>();
            for (Player p : players.values()) {
                for (Food f : foods) {
                    // Berührt der Spieler das Futter?
                    double dx = p.x - f.x;
                    double dy = p.y - f.y;
                    if (Math.sqrt(dx * dx + dy * dy) < p.radius) {
                        eaten.add(f); // Futter in den Magen
                        p.radius = Math.min(200, p.radius + 1); // Spieler wächst ein bisschen (bis maximal Größe 200)
                    }
                }
            }
            // Entferne gegessenes Futter von der Karte
            foods.removeAll(eaten);
            // Fülle die Karte wieder auf, damit immer 1000 Futterstücke da sind
            while (foods.size() < 1000) foods.add(new Food());

            // 3. Überprüfe, ob große Spieler kleine Spieler fressen
            for (Player p1 : players.values()) {
                for (Player p2 : players.values()) {
                    if (p1 == p2) continue; // Man kann sich nicht selbst essen
                    
                    // Sind sie sich nahe genug?
                    double dx = p1.x - p2.x;
                    double dy = p1.y - p2.y;
                    
                    // Wenn p1 den p2 berührt UND p1 deutlich größer ist (mindestens 10% größer)
                    if (Math.sqrt(dx * dx + dy * dy) < p1.radius && p1.radius > p2.radius * 1.1) {
                        p1.radius += p2.radius * 0.5; // Der große Spieler wächst um die halbe Größe des Opfers
                        
                        // Das Opfer (p2) stirbt und wird an einer zufälligen Stelle neu geboren
                        p2.radius = 20; 
                        p2.x = Math.random() * 5000;
                        p2.y = Math.random() * 5000;
                    }
                }
            }

            // 4. Nachdem alles berechnet wurde, schicke ein neues "Foto" der Spielwelt an alle Spieler
            broadcastState();
        }

        // Diese Funktion schreibt auf, wo jeder steht, und sendet es an alle Browser
        private void broadcastState() {
            if (clientOutputs.isEmpty()) return; // Wenn niemand zuschaut, müssen wir nichts schicken

            // Wir bauen manuell einen Textblock, den die Computer verstehen können (JSON-Format)
            StringBuilder sb = new StringBuilder();
            sb.append("{\"players\":[");
            int i = 0;
            // Schreibe jeden Spieler auf
            for (Player p : players.values()) {
                if (i++ > 0) sb.append(",");
                sb.append(String.format(Locale.US, "{\"id\":\"%s\",\"name\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"radius\":%.1f,\"color\":\"%s\"}",
                        p.id, p.name, p.x, p.y, p.radius, p.color));
            }
            sb.append("],\"food\":[");
            i = 0;
            // Schreibe jedes Futterstück auf
            for (Food f : foods) {
                if (i++ > 0) sb.append(",");
                sb.append(String.format(Locale.US, "{\"id\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"color\":\"%s\"}",
                        f.id, f.x, f.y, f.color));
            }
            sb.append("],\"worldWidth\":5000,\"worldHeight\":5000}"); // Gib noch die Weltgröße an

            // Wandle den geschriebenen Text in Computerzeichen (Bytes) um und verpacke es ordnungsgemäß
            byte[] jsonBytes = sb.toString().getBytes();
            byte[] frame = encodeWebSocketFrame(jsonBytes);

            // Sende das Paket (Frame) an jeden einzelnen Spieler
            for (Map.Entry<String, OutputStream> entry : clientOutputs.entrySet()) {
                try {
                    entry.getValue().write(frame);
                    entry.getValue().flush();
                } catch (IOException e) {
                    // Wenn jemand nicht erreichbar ist (Kabel gezogen), entferne ihn aus dem Spiel
                    removePlayer(entry.getKey());
                }
            }
        }

        // Eine kleine "Verpackungsmaschine", die unsere Daten in ein Format (WebSocket Frame) 
        // umwandelt, das der Browser verarbeiten darf. Das ist Standard für WebSockets.
        private byte[] encodeWebSocketFrame(byte[] payload) {
            int len = payload.length;
            int headerLen = len < 126 ? 2 : (len < 65536 ? 4 : 10);
            byte[] frame = new byte[headerLen + len];

            frame[0] = (byte) 129; // Code 129 sagt dem Browser: "Hier kommt eine Textnachricht, und sie ist hiermit fertig"
            if (len < 126) {
                frame[1] = (byte) len;
            } else if (len < 65536) {
                frame[1] = 126;
                frame[2] = (byte) (len >> 8);
                frame[3] = (byte) (len & 0xFF);
            } else {
                frame[1] = 127;
                // Sehr große Nachrichten (überspringen wir hier, da wir meist kürzere Texte senden)
                frame[6] = (byte) (len >> 24);
                frame[7] = (byte) (len >> 16);
                frame[8] = (byte) (len >> 8);
                frame[9] = (byte) (len & 0xFF);
            }

            // Packe den eigentlichen Text in den vorbereiteten Briefumschlag
            System.arraycopy(payload, 0, frame, headerLen, len);
            return frame; // Zurückschicken!
        }
    }
}
