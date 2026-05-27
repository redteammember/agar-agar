import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class AgarServer {

    private static final int PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
    private static final String WEB_ROOT = "public";

    public static void main(String[] args) {
        GameEngine engine = new GameEngine();
        engine.start();

        System.out.println("Starting AgarServer on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket client = serverSocket.accept();
                new Thread(new ClientHandler(client, engine)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ClientHandler implements Runnable {
        private final Socket socket;
        private final GameEngine engine;
        private InputStream in;
        private OutputStream out;

        public ClientHandler(Socket socket, GameEngine engine) {
            this.socket = socket;
            this.engine = engine;
        }

        @Override
        public void run() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();

                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String requestLine = reader.readLine();
                if (requestLine == null) return;

                System.out.println("Request: " + requestLine);

                // Read headers
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(":");
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                    }
                }

                // Check for WebSocket upgrade
                if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                    handleWebSocket(headers);
                } else {
                    handleHttp(requestLine);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void handleHttp(String requestLine) throws IOException {
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            if (path.equals("/")) path = "/index.html";

            File file = new File(WEB_ROOT + path);
            if (file.exists() && !file.isDirectory()) {
                String contentType = "text/plain";
                if (path.endsWith(".html")) contentType = "text/html";
                else if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "application/javascript";

                byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
                String response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + content.length + "\r\n" +
                        "\r\n";
                out.write(response.getBytes());
                out.write(content);
            } else {
                String response = "HTTP/1.1 404 Not Found\r\n\r\n404 File Not Found";
                out.write(response.getBytes());
            }
            out.flush();
            socket.close();
        }

        private void handleWebSocket(Map<String, String> headers) throws Exception {
            String key = headers.get("sec-websocket-key");
            String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest((key + magic).getBytes("UTF-8"));
            String accept = Base64.getEncoder().encodeToString(hash);

            String handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(handshake.getBytes());
            out.flush();

            String sessionId = UUID.randomUUID().toString();
            Player player = new Player(sessionId, "Guest");
            engine.addPlayer(sessionId, player, out);

            try {
                while (true) {
                    byte[] payload = readWebSocketFrame();
                    if (payload == null) break;
                    String message = new String(payload, "UTF-8");
                    engine.handleMessage(sessionId, message);
                }
            } finally {
                engine.removePlayer(sessionId);
                socket.close();
            }
        }

        private byte[] readWebSocketFrame() throws IOException {
            int b1 = in.read();
            if (b1 == -1) return null;
            int opcode = b1 & 0x0F;
            if (opcode == 8) return null; // Close frame

            int b2 = in.read();
            boolean masked = (b2 & 0x80) != 0;
            int payloadLen = b2 & 0x7F;

            if (payloadLen == 126) {
                payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (payloadLen == 127) {
                for (int i = 0; i < 4; i++) in.read(); // ignore top 4 bytes
                payloadLen = ((in.read() & 0xFF) << 24) | ((in.read() & 0xFF) << 16) | ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            }

            byte[] mask = new byte[4];
            if (masked) {
                in.read(mask);
            }

            byte[] payload = new byte[payloadLen];
            int read = 0;
            while (read < payloadLen) {
                read += in.read(payload, read, payloadLen - read);
            }

            if (masked) {
                for (int i = 0; i < payloadLen; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            return payload;
        }
    }

    // --- GAME LOGIC ---

    static class Player {
        String id;
        String name;
        double x, y, targetX, targetY;
        double radius = 20;
        String color;

        Player(String id, String name) {
            this.id = id;
            this.name = name;
            this.color = "#" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
            this.x = Math.random() * 5000;
            this.y = Math.random() * 5000;
            this.targetX = x;
            this.targetY = y;
        }

        public void update() {
            double dx = targetX - x;
            double dy = targetY - y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            double speed = Math.max(1.0, 100.0 / radius);
            if (dist > speed) {
                x += (dx / dist) * speed;
                y += (dy / dist) * speed;
            } else {
                x = targetX;
                y = targetY;
            }
        }
    }

    static class Food {
        String id = UUID.randomUUID().toString();
        double x = Math.random() * 5000;
        double y = Math.random() * 5000;
        String color = "#" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
    }

    static class GameEngine extends Thread {
        Map<String, Player> players = new ConcurrentHashMap<>();
        Map<String, OutputStream> clientOutputs = new ConcurrentHashMap<>();
        List<Food> foods = new CopyOnWriteArrayList<>();

        public GameEngine() {
            for (int i = 0; i < 1000; i++) foods.add(new Food());
        }

        public void addPlayer(String id, Player p, OutputStream out) {
            players.put(id, p);
            clientOutputs.put(id, out);
        }

        public void removePlayer(String id) {
            players.remove(id);
            clientOutputs.remove(id);
        }

        public void handleMessage(String id, String msg) {
            Player p = players.get(id);
            if (p == null) return;

            // Extremely simple manual JSON parsing to avoid dependencies
            if (msg.contains("\"type\":\"target\"")) {
                Matcher mx = Pattern.compile("\"x\":([0-9.]+)").matcher(msg);
                Matcher my = Pattern.compile("\"y\":([0-9.]+)").matcher(msg);
                if (mx.find() && my.find()) {
                    p.targetX = Double.parseDouble(mx.group(1));
                    p.targetY = Double.parseDouble(my.group(1));
                }
            } else if (msg.contains("\"type\":\"join\"")) {
                Matcher m = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(msg);
                if (m.find()) {
                    p.name = m.group(1);
                }
            }
        }

        @Override
        public void run() {
            while (true) {
                try {
                    tick();
                    Thread.sleep(16); // ~60 FPS
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void tick() {
            for (Player p : players.values()) p.update();

            // Collisions
            List<Food> eaten = new ArrayList<>();
            for (Player p : players.values()) {
                for (Food f : foods) {
                    double dx = p.x - f.x;
                    double dy = p.y - f.y;
                    if (Math.sqrt(dx * dx + dy * dy) < p.radius) {
                        eaten.add(f);
                        p.radius = Math.min(200, p.radius + 1);
                    }
                }
            }
            foods.removeAll(eaten);
            while (foods.size() < 1000) foods.add(new Food());

            // Player eating player
            for (Player p1 : players.values()) {
                for (Player p2 : players.values()) {
                    if (p1 == p2) continue;
                    double dx = p1.x - p2.x;
                    double dy = p1.y - p2.y;
                    if (Math.sqrt(dx * dx + dy * dy) < p1.radius && p1.radius > p2.radius * 1.1) {
                        p1.radius += p2.radius * 0.5;
                        p2.radius = 20; // reset dead player
                        p2.x = Math.random() * 5000;
                        p2.y = Math.random() * 5000;
                    }
                }
            }

            broadcastState();
        }

        private void broadcastState() {
            if (clientOutputs.isEmpty()) return;

            // Build JSON manually
            StringBuilder sb = new StringBuilder();
            sb.append("{\"players\":[");
            int i = 0;
            for (Player p : players.values()) {
                if (i++ > 0) sb.append(",");
                sb.append(String.format(Locale.US, "{\"id\":\"%s\",\"name\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"radius\":%.1f,\"color\":\"%s\"}",
                        p.id, p.name, p.x, p.y, p.radius, p.color));
            }
            sb.append("],\"food\":[");
            i = 0;
            for (Food f : foods) {
                if (i++ > 0) sb.append(",");
                sb.append(String.format(Locale.US, "{\"id\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"color\":\"%s\"}",
                        f.id, f.x, f.y, f.color));
            }
            sb.append("],\"worldWidth\":5000,\"worldHeight\":5000}");

            byte[] jsonBytes = sb.toString().getBytes();
            byte[] frame = encodeWebSocketFrame(jsonBytes);

            for (Map.Entry<String, OutputStream> entry : clientOutputs.entrySet()) {
                try {
                    entry.getValue().write(frame);
                    entry.getValue().flush();
                } catch (IOException e) {
                    removePlayer(entry.getKey());
                }
            }
        }

        private byte[] encodeWebSocketFrame(byte[] payload) {
            int len = payload.length;
            int headerLen = len < 126 ? 2 : (len < 65536 ? 4 : 10);
            byte[] frame = new byte[headerLen + len];

            frame[0] = (byte) 129; // Text frame, FIN bit set
            if (len < 126) {
                frame[1] = (byte) len;
            } else if (len < 65536) {
                frame[1] = 126;
                frame[2] = (byte) (len >> 8);
                frame[3] = (byte) (len & 0xFF);
            } else {
                frame[1] = 127;
                // Skipping 64-bit length for brevity, sticking to 32-bit max
                frame[6] = (byte) (len >> 24);
                frame[7] = (byte) (len >> 16);
                frame[8] = (byte) (len >> 8);
                frame[9] = (byte) (len & 0xFF);
            }

            System.arraycopy(payload, 0, frame, headerLen, len);
            return frame;
        }
    }
}
