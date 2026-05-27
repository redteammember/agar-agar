const mainMenu = document.getElementById('main-menu');
const gameUi = document.getElementById('game-ui');
const nicknameInput = document.getElementById('nickname');
const playBtn = document.getElementById('play-btn');
const canvas = document.getElementById('game-canvas');
const ctx = canvas.getContext('2d');

const statFps = document.getElementById('stat-fps');
const statPing = document.getElementById('stat-ping');
const statRx = document.getElementById('stat-rx');
const statTx = document.getElementById('stat-tx');

let ws = null;
let myId = null;
let playerName = '';
let gameState = { players: [], food: [], worldWidth: 5000, worldHeight: 5000 };

let mouseX = 0;
let mouseY = 0;
let camera = { x: 0, y: 0, zoom: 1 };

let stats = { ping: 0, rx: 0, tx: 0 };
let lastMessageTime = Date.now();

let isPlaying = false;
let inputInterval = null;

// FPS Counter
let frames = 0;
setInterval(() => {
    statFps.innerText = frames;
    frames = 0;
}, 1000);

playBtn.addEventListener('click', () => {
    playerName = nicknameInput.value.trim() || 'Guest';
    startGame();
});

window.addEventListener('mousemove', (e) => {
    mouseX = e.clientX;
    mouseY = e.clientY;
});

window.addEventListener('resize', () => {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
});

function startGame() {
    mainMenu.style.display = 'none';
    gameUi.style.display = 'block';
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
    isPlaying = true;

    connectWebSocket();
    requestAnimationFrame(render);
}

function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${protocol}//${window.location.host}/`);

    ws.onopen = () => {
        ws.send(JSON.stringify({ type: 'join', name: playerName }));
        stats.tx++;
        updateStats();

        // Input loop
        inputInterval = setInterval(() => {
            if (ws.readyState === WebSocket.OPEN && myId) {
                const me = gameState.players.find(p => p.id === myId);
                if (me) {
                    const worldX = me.x + (mouseX - window.innerWidth / 2) / camera.zoom;
                    const worldY = me.y + (mouseY - window.innerHeight / 2) / camera.zoom;
                    ws.send(JSON.stringify({ type: 'target', x: worldX, y: worldY }));
                    stats.tx++;
                    updateStats();
                }
            }
        }, 16);
    };

    ws.onmessage = (e) => {
        const now = Date.now();
        stats.ping = now - lastMessageTime;
        lastMessageTime = now;
        stats.rx++;
        updateStats();

        try {
            const state = JSON.parse(e.data);
            gameState = state;

            // Simple heuristic to find ourselves
            if (!myId && state.players.length > 0) {
                const me = state.players.find(p => p.name === playerName);
                if (me) myId = me.id;
            } else if (myId) {
                const me = state.players.find(p => p.id === myId);
                if (!me) die(); // We were eaten
            }
        } catch (err) {
            console.error(err);
        }
    };

    ws.onclose = () => {
        if (isPlaying) {
            alert('Disconnected from server.');
            die();
        }
    };
}

function die() {
    isPlaying = false;
    clearInterval(inputInterval);
    if (ws) ws.close();
    myId = null;
    gameUi.style.display = 'none';
    mainMenu.style.display = 'flex';
}

function updateStats() {
    statPing.innerText = `${stats.ping} ms`;
    statPing.style.color = stats.ping > 100 ? '#f87171' : '#4ade80';
    statRx.innerText = stats.rx;
    statTx.innerText = stats.tx;
}

function render() {
    if (!isPlaying) return;
    frames++;

    ctx.fillStyle = '#0f172a';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    const me = gameState.players.find(p => p.id === myId);
    if (me) {
        camera.x += (me.x - camera.x) * 0.1;
        camera.y += (me.y - camera.y) * 0.1;
        const targetZoom = Math.max(0.2, 20 / me.radius);
        camera.zoom += (targetZoom - camera.zoom) * 0.05;
    }

    ctx.save();
    ctx.translate(canvas.width / 2, canvas.height / 2);
    ctx.scale(camera.zoom, camera.zoom);
    ctx.translate(-camera.x, -camera.y);

    // Grid
    ctx.strokeStyle = 'rgba(255,255,255,0.05)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (let x = 0; x <= gameState.worldWidth; x += 50) { ctx.moveTo(x, 0); ctx.lineTo(x, gameState.worldHeight); }
    for (let y = 0; y <= gameState.worldHeight; y += 50) { ctx.moveTo(0, y); ctx.lineTo(gameState.worldWidth, y); }
    ctx.stroke();

    // Food
    gameState.food.forEach(f => {
        ctx.beginPath();
        ctx.arc(f.x, f.y, 5, 0, Math.PI * 2);
        ctx.fillStyle = f.color;
        ctx.fill();
    });

    // Players
    gameState.players.sort((a,b) => a.radius - b.radius).forEach(p => {
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx.fillStyle = p.color;
        ctx.fill();

        ctx.strokeStyle = 'rgba(0,0,0,0.2)';
        ctx.lineWidth = 4;
        ctx.stroke();

        ctx.fillStyle = 'white';
        ctx.font = `bold ${Math.max(12, p.radius * 0.4)}px Outfit, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(p.name, p.x, p.y);
    });

    ctx.restore();
    requestAnimationFrame(render);
}
