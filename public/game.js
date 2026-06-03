// Hier verknüpfen wir unseren JavaScript-Code mit den Elementen auf der Webseite (HTML)
// document.getElementById sucht nach einem Element mit einem bestimmten Namen auf der Seite
const mainMenu = document.getElementById('main-menu'); // Das Hauptmenü
const gameUi = document.getElementById('game-ui'); // Die Spieloberfläche
const nicknameInput = document.getElementById('nickname'); // Das Textfeld für den Namen
const playBtn = document.getElementById('play-btn'); // Der "Spielen"-Knopf
const canvas = document.getElementById('game-canvas'); // Die Leinwand, auf der wir zeichnen
const ctx = canvas.getContext('2d'); // Unser "Pinsel", um auf der Leinwand 2D-Grafiken zu zeichnen

// Hier greifen wir auf die Elemente für unsere Netzwerk-Statistik oben rechts zu
const statFps = document.getElementById('stat-fps');
const statPing = document.getElementById('stat-ping');
const statRx = document.getElementById('stat-rx');
const statTx = document.getElementById('stat-tx');

// Variablen (Speicherplätze), die wir während des Spiels brauchen
let ws = null; // Hier speichern wir später unsere Telefonverbindung (WebSocket) zum Server
let myId = null; // Unsere eigene Erkennungsnummer im Spiel
let playerName = ''; // Unser gewählter Name
// Das ist unser Notizblock für den Zustand der Spielwelt: Wer spielt mit? Wo ist das Futter? Wie groß ist die Welt?
let gameState = { players: [], food: [], worldWidth: 5000, worldHeight: 5000 };

// Speichert die aktuelle Position unserer Maus auf dem Bildschirm
let mouseX = 0;
let mouseY = 0;
// Unsere Kamera, die bestimmt, welchen Ausschnitt der großen Spielwelt wir auf unserem Bildschirm sehen
let camera = { x: 0, y: 0, zoom: 1 };

// Speichert unsere Netzwerk-Statistiken (wie schnell das Spiel reagiert)
let stats = { ping: 0, rx: 0, tx: 0 };
let lastMessageTime = Date.now();

// Gibt an, ob wir uns gerade mitten im Spiel befinden oder noch im Menü sind
let isPlaying = false;
let inputInterval = null; // Hier speichern wir später unseren "Wecker", der ständig die Mausposition sendet

// FPS Counter (Bilder pro Sekunde Zähler)
let frames = 0;
// Dieser Wecker (setInterval) klingelt jede Sekunde (1000 Millisekunden) und schreibt auf, 
// wie viele Bilder in der letzten Sekunde gezeichnet wurden.
setInterval(() => {
    statFps.innerText = frames;
    frames = 0; // Zähler wieder auf 0 setzen für die nächste Sekunde
}, 1000);

// Was soll passieren, wenn man auf den "Spielen"-Knopf klickt?
playBtn.addEventListener('click', () => {
    // Lies den Namen aus dem Textfeld aus. Wenn es leer ist, nenne den Spieler 'Guest' (Gast)
    playerName = nicknameInput.value.trim() || 'Guest';
    // Starte das Spiel!
    startGame();
});

// Jedes Mal, wenn die Maus auf dem Bildschirm bewegt wird, merken wir uns die neue Position
window.addEventListener('mousemove', (e) => {
    mouseX = e.clientX;
    mouseY = e.clientY;
});

// Wenn der Spieler die Größe seines Browserfensters ändert, passen wir unsere Leinwand an
window.addEventListener('resize', () => {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
});

// Diese Funktion startet das eigentliche Spielgeschehen
function startGame() {
    mainMenu.style.display = 'none'; // Verstecke das Hauptmenü
    gameUi.style.display = 'block'; // Zeige die Spieloberfläche
    canvas.width = window.innerWidth; // Mache die Leinwand so groß wie das Fenster
    canvas.height = window.innerHeight;
    isPlaying = true; // Merke dir: Wir spielen jetzt!

    connectWebSocket(); // Rufe den Server an, um mitzuspielen
    requestAnimationFrame(render); // Starte das Zeichnen der Bilder (wie ein Daumenkino)
}

// Diese Funktion baut die Verbindung (den "Anruf") zum Spielserver auf
function connectWebSocket() {
    // Bestimme, ob wir eine normale oder sichere (verschlüsselte) Verbindung brauchen
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    // Wähle die Nummer des Servers (er läuft auf demselben Computer)
    ws = new WebSocket(`${protocol}//${window.location.host}/`);

    // Wenn der Server "abnimmt" (Verbindung erfolgreich)
    ws.onopen = () => {
        // Sende unseren Namen an den Server
        ws.send(JSON.stringify({ type: 'join', name: playerName }));
        stats.tx++; // Zähle hoch, dass wir eine Nachricht gesendet haben
        updateStats(); // Aktualisiere die Anzeige oben rechts

        // Stelle einen Wecker, der sehr schnell (alle 16 Millisekunden) klingelt
        inputInterval = setInterval(() => {
            // Wenn wir verbunden sind und der Server uns schon kennt (myId)
            if (ws.readyState === WebSocket.OPEN && myId) {
                // Suche uns selbst auf der Karte
                const me = gameState.players.find(p => p.id === myId);
                if (me) {
                    // Rechne aus, wohin wir auf der großen Weltkarte zeigen (basierend auf unserer Bildschirm-Maus)
                    const worldX = me.x + (mouseX - window.innerWidth / 2) / camera.zoom;
                    const worldY = me.y + (mouseY - window.innerHeight / 2) / camera.zoom;
                    // Sende unser Bewegungsziel an den Server
                    ws.send(JSON.stringify({ type: 'target', x: worldX, y: worldY }));
                    stats.tx++; // Zähle gesendete Nachrichten hoch
                    updateStats();
                }
            }
        }, 16);
    };

    // Diese Funktion wird immer aufgerufen, wenn uns der Server Neuigkeiten (eine neue Karte) schickt
    ws.onmessage = (e) => {
        const now = Date.now();
        // Berechne, wie lange die Nachricht gebraucht hat (Ping)
        stats.ping = now - lastMessageTime;
        lastMessageTime = now;
        stats.rx++; // Zähle hoch, dass wir eine Nachricht empfangen haben
        updateStats();

        try {
            // Entpacke die Server-Nachricht, sodass unser Computer sie versteht
            const state = JSON.parse(e.data);
            gameState = state; // Aktualisiere unseren "Notizblock" mit der neuen Spielwelt

            // Ein einfacher Trick, um herauszufinden, wer wir selbst in dem Spiel sind:
            // Wenn wir unsere ID noch nicht kennen, suchen wir unseren Namen in der Spielerliste
            if (!myId && state.players.length > 0) {
                const me = state.players.find(p => p.name === playerName);
                if (me) myId = me.id; // Ah, gefunden! Merke dir die ID.
            } else if (myId) {
                // Wenn wir unsere ID schon kennen, prüfen wir bei jedem Update, ob wir noch am Leben sind
                const me = state.players.find(p => p.id === myId);
                if (!me) die(); // Wir sind nicht mehr in der Liste. Ein anderer Spieler hat uns gegessen!
            }
        } catch (err) {
            console.error(err); // Falls der Server unverständliches Zeug redet, ignoriere es und gib eine Fehlermeldung aus
        }
    };

    // Wenn der Server auflegt (Verbindung abbricht)
    ws.onclose = () => {
        if (isPlaying) {
            alert('Verbindung zum Server verloren.');
            die(); // Beende unser Spiel
        }
    };
}

// Diese Funktion wird aufgerufen, wenn unser Spieler stirbt (gegessen wird)
function die() {
    isPlaying = false; // Wir spielen nicht mehr
    clearInterval(inputInterval); // Schalte den Maus-Sende-Wecker aus
    if (ws) ws.close(); // Lege den Telefonhörer (Verbindung zum Server) auf
    myId = null; // Vergiss unsere alte ID
    gameUi.style.display = 'none'; // Verstecke das Spielfeld
    mainMenu.style.display = 'flex'; // Zeige wieder das Hauptmenü, damit wir neu starten können
}

// Aktualisiert die Zahlen in der Netzwerk-Diagnose oben rechts auf dem Bildschirm
function updateStats() {
    statPing.innerText = `${stats.ping} ms`;
    // Wenn das Internet zu langsam ist (Ping über 100), mache die Schrift rot, sonst grün
    statPing.style.color = stats.ping > 100 ? '#f87171' : '#4ade80';
    statRx.innerText = stats.rx;
    statTx.innerText = stats.tx;
}

// Dies ist unsere "Zeichen-Fabrik" (Render-Loop). Sie wird super schnell immer wieder aufgerufen (Daumenkino-Prinzip)
function render() {
    if (!isPlaying) return; // Wenn wir nicht spielen, zeichne nichts
    frames++; // Zähle mit, dass wir gerade ein Bild zeichnen (für unsere Bilder-pro-Sekunde-Anzeige)

    // Übermale das ganze alte Bild mit unserer dunkelblauen Hintergrundfarbe, wie ein Radiergummi
    ctx.fillStyle = '#0f172a';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // Kameraführung: Finde uns selbst auf der Karte, damit die Kamera uns folgen kann
    const me = gameState.players.find(p => p.id === myId);
    if (me) {
        // Gleite sanft zur Position des Spielers (nicht sofort hinspringen, das sieht unschön aus)
        camera.x += (me.x - camera.x) * 0.1;
        camera.y += (me.y - camera.y) * 0.1;
        // Wenn der Spieler größer wird, muss die Kamera herauszoomen, damit wir noch etwas sehen
        const targetZoom = Math.max(0.2, 20 / me.radius);
        camera.zoom += (targetZoom - camera.zoom) * 0.05; // Auch hier: weiches Herauszoomen
    }

    ctx.save(); // Speichere den neutralen Zustand der Leinwand
    
    // Verschiebe die Welt so, dass die Kamera (und unser Spieler) genau in der Mitte des Bildschirms ist
    ctx.translate(canvas.width / 2, canvas.height / 2);
    ctx.scale(camera.zoom, camera.zoom); // Zoome in die Welt hinein oder heraus
    ctx.translate(-camera.x, -camera.y);

    // Male ein Gitternetz auf den Boden (hilft dabei, Bewegung besser wahrzunehmen)
    ctx.strokeStyle = 'rgba(255,255,255,0.05)'; // Fast unsichtbares Weiß
    ctx.lineWidth = 2; // Liniendicke
    ctx.beginPath(); // Setze den Pinsel an
    // Male vertikale und horizontale Linien alle 50 Schritte (Pixel)
    for (let x = 0; x <= gameState.worldWidth; x += 50) { ctx.moveTo(x, 0); ctx.lineTo(x, gameState.worldHeight); }
    for (let y = 0; y <= gameState.worldHeight; y += 50) { ctx.moveTo(0, y); ctx.lineTo(gameState.worldWidth, y); }
    ctx.stroke(); // Führe den Pinselstrich aus

    // Male jedes einzelne kleine Futterstück auf die Karte
    gameState.food.forEach(f => {
        ctx.beginPath();
        ctx.arc(f.x, f.y, 5, 0, Math.PI * 2); // Ein runder Kreis mit Größe 5
        ctx.fillStyle = f.color; // Färbe ihn ein
        ctx.fill(); // Ausmalen
    });

    // Male alle Mitspieler auf die Karte
    // Wir sortieren sie nach Größe, damit die großen Spieler über die kleinen drüber gezeichnet werden und diese verdecken
    gameState.players.sort((a,b) => a.radius - b.radius).forEach(p => {
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2); // Male einen runden Kreis für den Spieler (Radius bestimmt die Größe)
        ctx.fillStyle = p.color; // Seine Farbe
        ctx.fill();

        // Ein leichter schwarzer Rand für den 3D-Look
        ctx.strokeStyle = 'rgba(0,0,0,0.2)';
        ctx.lineWidth = 4;
        ctx.stroke();

        // Schreibe den Namen des Spielers in die Mitte seiner Kugel
        ctx.fillStyle = 'white';
        ctx.font = `bold ${Math.max(12, p.radius * 0.4)}px Outfit, sans-serif`; // Schrift wird größer, je größer der Spieler ist
        ctx.textAlign = 'center'; // Genau in die Mitte setzen
        ctx.textBaseline = 'middle';
        ctx.fillText(p.name, p.x, p.y); // Text zeichnen
    });

    ctx.restore(); // Mache die Kamera-Verschiebungen rückgängig, damit die Netzwerk-Diagnose oben rechts an ihrem festen Platz bleibt
    requestAnimationFrame(render); // Bitte den Browser, diese Funktion sofort wieder aufzurufen, um das nächste Bild zu zeichnen
}
