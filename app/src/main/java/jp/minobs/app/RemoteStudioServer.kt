package jp.minobs.app

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RemoteStudioServer(
    private val controller: Controller,
    private val port: Int = 8787
) {
    interface Controller {
        fun remoteStatus(): JSONObject
        fun remotePreviewJpeg(): ByteArray?
        fun remoteMonitorWav(): ByteArray
        fun remoteCommand(command: String, args: Map<String, String>): String
        fun remoteCameraFrame(cameraId: Int, jpeg: ByteArray)
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null
    val pairCode: String = (100000 + SecureRandom().nextInt(900000)).toString()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
            }
            running.set(true)
            pool.execute {
                while (running.get()) {
                    try {
                        socket?.accept()?.let { client -> pool.execute { handle(client) } }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }
            true
        } catch (_: Exception) {
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    fun localUrl(): String? = bestLanIpv4()?.let { "http://$it:$port/?code=$pairCode" }

    private fun handle(client: Socket) {
        client.soTimeout = 7000
        client.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val p = line.indexOf(':')
                if (p > 0) headers[line.substring(0, p).trim().lowercase()] = line.substring(p + 1).trim()
            }

            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 3_000_000) ?: 0
            val body = if (contentLength > 0) input.readExact(contentLength) else ByteArray(0)

            if (path != "/" && path != "/ping" && query["code"] != pairCode) {
                respond(output, 403, "text/plain; charset=utf-8", "Pair code required".toByteArray())
                return
            }

            when {
                method == "GET" && path == "/ping" -> {
                    respond(output, 200, "application/json", JSONObject().put("ok", true).put("service", "Mini OBS Remote").toString().toByteArray(), cache = false)
                }
                method == "GET" && path == "/" -> {
                    if (query["code"] != pairCode) {
                        respond(output, 200, "text/html; charset=utf-8", loginPage().toByteArray(), cache = false)
                    } else {
                        respond(output, 200, "text/html; charset=utf-8", studioPage().toByteArray(), cache = false)
                    }
                }
                method == "GET" && path == "/api/status" -> {
                    respond(output, 200, "application/json", controller.remoteStatus().toString().toByteArray(), cache = false)
                }
                method == "GET" && path == "/preview.jpg" -> {
                    val jpeg = controller.remotePreviewJpeg()
                    if (jpeg == null) respond(output, 503, "text/plain", "preview unavailable".toByteArray(), cache = false)
                    else respond(output, 200, "image/jpeg", jpeg, cache = false)
                }
                method == "GET" && path == "/monitor.wav" -> {
                    respond(output, 200, "audio/wav", controller.remoteMonitorWav(), cache = false)
                }
                (method == "GET" || method == "POST") && path == "/api/cmd" -> {
                    val args = query.toMutableMap()
                    val cmd = args.remove("cmd").orEmpty()
                    args.remove("code")
                    val result = controller.remoteCommand(cmd, args)
                    respond(
                        output,
                        200,
                        "application/json",
                        JSONObject().put("ok", true).put("result", result).toString().toByteArray(),
                        cache = false
                    )
                }
                method == "POST" && path == "/api/camera/frame" -> {
                    val id = query["id"]?.toIntOrNull()?.coerceIn(1, 3) ?: 1
                    if (body.isNotEmpty()) controller.remoteCameraFrame(id, body)
                    respond(output, 200, "text/plain", "ok".toByteArray(), cache = false)
                }
                else -> respond(output, 404, "text/plain", "not found".toByteArray(), cache = false)
            }
        }
    }

    private fun loginPage() = """<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mini OBS Remote</title></head>
<body style="background:#101218;color:white;font-family:system-ui,sans-serif;padding:24px">
<h2>Mini OBS Remote</h2>
<p>ゲーム端末に表示されている6桁コードを入力してください。</p>
<input id="c" inputmode="numeric" maxlength="6" style="font-size:24px;width:150px">
<button id="connect" style="font-size:20px">接続</button>
<p id="msg" style="color:#aab2c0"></p>
<script>
const c=document.getElementById('c');
const msg=document.getElementById('msg');
document.getElementById('connect').addEventListener('click',()=>{const v=c.value.trim();if(v.length!==6){msg.textContent='6桁のコードを入力してください';return;}location='/?code='+encodeURIComponent(v);});
c.addEventListener('keydown',e=>{if(e.key==='Enter')document.getElementById('connect').click();});
</script></body></html>"""

    private fun studioPage(): String {
        val code = pairCode
        return """<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no"><title>Mini OBS Remote Studio</title><style>
body{margin:0;background:#0f1117;color:#eee;font-family:system-ui,sans-serif}header{padding:10px 12px;background:#181b24;position:sticky;top:0;z-index:4}#status{font-size:12px;color:#aab2c0}.wrap{padding:10px;max-width:900px;margin:auto}.preview{position:relative;background:#000;border-radius:10px;overflow:hidden;touch-action:none;min-height:180px}.preview img{width:100%;display:block;min-height:180px;object-fit:contain}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin-top:9px}button{background:#292e3d;color:white;border:0;border-radius:9px;padding:13px 6px;font-weight:700}.red{background:#8f2534}.panel{background:#181b24;border-radius:11px;padding:10px;margin-top:9px}input[type=range]{width:100%}small{color:#9ba4b5}.row{display:flex;gap:6px}.row>*{flex:1}#chat{height:100px;overflow:auto;background:#0b0d12;padding:6px;font-size:12px}.warn{color:#ffb866;font-size:12px;margin-top:6px}
</style></head><body>
<header><b>Mini OBS Dual Phone Studio</b><div id="status">接続確認中…</div></header><div class="wrap">
<div class="preview" id="pv"><img id="img" alt="Remote preview"></div><div id="previewMsg" class="warn"></div>
<div class="grid" id="deck"></div><button style="width:100%;margin-top:6px" id="editDeck">Stream Deckを編集</button>
<div class="panel"><b>シーン</b><div class="grid"><button data-scene="game">GAME</button><button data-scene="talk">TALK</button><button data-scene="wait">WAIT</button><button class="red" id="privacy">緊急非表示</button></div></div>
<div class="panel"><b>ソース編集</b><div class="row"><button data-source="text">文字</button><button data-source="image">画像</button><button data-source="camera">カメラ</button><button data-source="external1">外部Cam1</button></div><small>プレビューを指でドラッグすると選択ソースを移動</small><input id="scale" type="range" min="8" max="80" value="28"></div>
<div class="panel"><b>Audio Mixer</b><label>MIC <span id="mv">100</span>%</label><input id="mic" type="range" min="0" max="200" value="100"><label>GAME <span id="iv">100</span>%</label><input id="internal" type="range" min="0" max="200" value="100"><div class="row"><button id="mute">MIC MUTE</button><button id="monitor">3秒音声チェック</button></div></div>
<div class="panel"><b>Replay / Marker</b><div class="row"><button id="replay">直前を保存</button><button id="marker">★ MARK</button></div></div>
<div class="panel"><b>Chat / Alert relay</b><div id="chat">外部チャット連携の表示欄</div><div class="row"><input id="chatText" placeholder="テロップ/コメント"><button id="sendChat">送信</button></div></div>
<div class="panel"><b>Health</b><pre id="health" style="white-space:pre-wrap;font-size:11px"></pre></div>
</div><script>
const CODE='$code';
const el=id=>document.getElementById(id);
const statusEl=el('status'),img=el('img'),pv=el('pv'),deck=el('deck'),health=el('health'),chat=el('chat'),previewMsg=el('previewMsg');
function qs(o){return Object.entries(o||{}).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(v)).join('&')}
async function cmd(c,a={}){try{const r=await fetch('/api/cmd?code='+encodeURIComponent(CODE)+'&cmd='+encodeURIComponent(c)+'&'+qs(a),{method:'POST',cache:'no-store'});if(!r.ok)throw new Error('HTTP '+r.status);return true}catch(e){statusEl.textContent='操作送信エラー: '+e.message;return false}}
let defs=['record','replay','marker','privacy','scene:game','scene:talk','scene:wait','mute'];
let actions;try{actions=JSON.parse(localStorage.getItem('miniobsDeck')||JSON.stringify(defs));if(!Array.isArray(actions))actions=[...defs]}catch(e){actions=[...defs]}
function label(a){return ({record:'REC',replay:'REPLAY',marker:'MARK',privacy:'PANIC',mute:'MIC',stream:'LIVE'})[a]||a.replace('scene:','').toUpperCase()}
function deckRun(a){if(a.startsWith('scene:'))cmd('scene',{value:a.split(':')[1]});else cmd(a)}
function renderDeck(){deck.replaceChildren();actions.forEach(a=>{const b=document.createElement('button');b.textContent=label(a);if(a==='privacy')b.className='red';b.addEventListener('click',()=>deckRun(a));deck.appendChild(b)})}
function editDeck(){const x=prompt('8個の操作をカンマ区切りで指定\nrecord,stream,replay,marker,privacy,mute,scene:game,scene:talk,scene:wait',actions.join(','));if(x){actions=x.split(',').map(v=>v.trim()).filter(Boolean).slice(0,8);while(actions.length<8)actions.push('marker');localStorage.setItem('miniobsDeck',JSON.stringify(actions));renderDeck()}}
el('editDeck').addEventListener('click',editDeck);renderDeck();
document.querySelectorAll('[data-scene]').forEach(b=>b.addEventListener('click',()=>cmd('scene',{value:b.dataset.scene})));
document.querySelectorAll('[data-source]').forEach(b=>b.addEventListener('click',()=>cmd('select',{value:b.dataset.source})));
el('privacy').addEventListener('click',()=>cmd('privacy'));el('replay').addEventListener('click',()=>cmd('replay'));el('marker').addEventListener('click',()=>cmd('marker'));el('mute').addEventListener('click',()=>cmd('mute'));
el('scale').addEventListener('input',e=>cmd('scale',{value:e.target.value}));
el('mic').addEventListener('input',e=>{el('mv').textContent=e.target.value;cmd('micVolume',{value:e.target.value})});
el('internal').addEventListener('input',e=>{el('iv').textContent=e.target.value;cmd('internalVolume',{value:e.target.value})});
el('monitor').addEventListener('click',()=>{const a=new Audio('/monitor.wav?code='+encodeURIComponent(CODE)+'&t='+Date.now());a.play().catch(()=>{statusEl.textContent='ブラウザが音声再生をブロックしました'})});
el('sendChat').addEventListener('click',()=>{const t=el('chatText');cmd('chat',{text:t.value});t.value=''});
let lastX=0,lastY=0,drag=false;pv.addEventListener('pointerdown',e=>{drag=true;lastX=e.clientX;lastY=e.clientY;pv.setPointerCapture(e.pointerId)});pv.addEventListener('pointermove',e=>{if(!drag)return;const dx=(e.clientX-lastX)*100/Math.max(1,pv.clientWidth),dy=(e.clientY-lastY)*100/Math.max(1,pv.clientHeight);lastX=e.clientX;lastY=e.clientY;cmd('move',{dx:dx.toFixed(2),dy:dy.toFixed(2)})});pv.addEventListener('pointerup',()=>drag=false);pv.addEventListener('pointercancel',()=>drag=false);
let previewTimer=null;function schedulePreview(ms){clearTimeout(previewTimer);previewTimer=setTimeout(refreshPreview,ms)}
function refreshPreview(){img.onload=()=>{previewMsg.textContent='';schedulePreview(450)};img.onerror=()=>{previewMsg.textContent='プレビュー取得中… 操作はそのまま使えます';schedulePreview(1200)};img.src='/preview.jpg?code='+encodeURIComponent(CODE)+'&t='+Date.now()}
function renderChat(lines){chat.replaceChildren();(lines||[]).forEach(x=>{const d=document.createElement('div');d.textContent=String(x);chat.appendChild(d)});chat.scrollTop=chat.scrollHeight}
async function refresh(){try{const r=await fetch('/api/status?code='+encodeURIComponent(CODE)+'&t='+Date.now(),{cache:'no-store'});if(!r.ok)throw new Error('HTTP '+r.status);const s=await r.json();statusEl.textContent=(s.live?'LIVE ':'')+(s.recording?'REC ':'')+'Scene:'+s.scene+'  '+(s.bitrateKbps||0)+'kbps  '+(s.network||'')+'  '+(s.temperature??'--')+'℃';health.textContent=JSON.stringify(s,null,2);renderChat(s.chat)}catch(e){statusEl.textContent='再接続中… '+e.message}setTimeout(refresh,1000)}
refreshPreview();refresh();
</script></body></html>"""
    }

    private fun parseQuery(q: String): Map<String, String> = buildMap {
        if (q.isBlank()) return@buildMap
        q.split('&').forEach { token ->
            val p = token.indexOf('=')
            val k = if (p >= 0) token.substring(0, p) else token
            val v = if (p >= 0) token.substring(p + 1) else ""
            put(urlDecode(k), urlDecode(v))
        }
    }

    private fun urlDecode(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8.name())

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) out.append(c.toChar())
            if (out.length > 8192) break
        }
        return out.toString()
    }

    private fun BufferedInputStream.readExact(size: Int): ByteArray {
        val data = ByteArray(size)
        var pos = 0
        while (pos < size) {
            val n = read(data, pos, size - pos)
            if (n <= 0) break
            pos += n
        }
        return if (pos == size) data else data.copyOf(pos)
    }

    private fun respond(
        out: BufferedOutputStream,
        status: Int,
        type: String,
        body: ByteArray,
        cache: Boolean = true
    ) {
        val text = when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            503 -> "Unavailable"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status $text\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            if (!cache) append("Cache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("X-Content-Type-Options: nosniff\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private data class LanAddress(val interfaceName: String, val address: String, val score: Int)

    private fun bestLanIpv4(): String? = runCatching {
        val preferredPrefixes = listOf("wlan", "swlan", "ap", "eth", "rndis", "usb")
        Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                Collections.list(iface.inetAddresses).asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
                    .map { address ->
                        val name = iface.name.lowercase()
                        val prefixIndex = preferredPrefixes.indexOfFirst { name.startsWith(it) }
                        val interfaceScore = if (prefixIndex >= 0) prefixIndex else 50
                        val ip = address.hostAddress ?: ""
                        val ipScore = when {
                            ip.startsWith("192.168.") -> 0
                            ip.startsWith("10.") -> 1
                            ip.startsWith("172.") -> 2
                            else -> 10
                        }
                        LanAddress(iface.name, ip, interfaceScore * 10 + ipScore)
                    }
            }
            .filter { it.address.isNotBlank() }
            .sortedBy { it.score }
            .firstOrNull()
            ?.address
    }.getOrNull()
}
