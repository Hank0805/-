package jp.minobs.app

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
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
            socket = ServerSocket(port).also { it.reuseAddress = true }
            running.set(true)
            pool.execute {
                while (running.get()) {
                    try { socket?.accept()?.let { client -> pool.execute { handle(client) } } }
                    catch (_: Exception) { if (!running.get()) break }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    fun localUrl(): String? = localIpv4()?.let { "http://$it:$port/?code=$pairCode" }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
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

            if (path != "/" && query["code"] != pairCode) {
                respond(output, 403, "text/plain; charset=utf-8", "Pair code required".toByteArray())
                return
            }

            when {
                method == "GET" && path == "/" -> {
                    if (query["code"] != pairCode) respond(output, 200, "text/html; charset=utf-8", loginPage().toByteArray())
                    else respond(output, 200, "text/html; charset=utf-8", studioPage().toByteArray())
                }
                method == "GET" && path == "/api/status" -> respond(output, 200, "application/json", controller.remoteStatus().toString().toByteArray())
                method == "GET" && path == "/preview.jpg" -> {
                    val jpeg = controller.remotePreviewJpeg()
                    if (jpeg == null) respond(output, 503, "text/plain", "preview unavailable".toByteArray())
                    else respond(output, 200, "image/jpeg", jpeg, cache = false)
                }
                method == "GET" && path == "/monitor.wav" -> respond(output, 200, "audio/wav", controller.remoteMonitorWav(), cache = false)
                (method == "GET" || method == "POST") && path == "/api/cmd" -> {
                    val args = query.toMutableMap(); val cmd = args.remove("cmd").orEmpty(); args.remove("code")
                    val result = controller.remoteCommand(cmd, args)
                    respond(output, 200, "application/json", JSONObject().put("ok", true).put("result", result).toString().toByteArray())
                }
                method == "POST" && path == "/api/camera/frame" -> {
                    val id = query["id"]?.toIntOrNull()?.coerceIn(1, 3) ?: 1
                    if (body.isNotEmpty()) controller.remoteCameraFrame(id, body)
                    respond(output, 200, "text/plain", "ok".toByteArray())
                }
                else -> respond(output, 404, "text/plain", "not found".toByteArray())
            }
        }
    }

    private fun loginPage() = """<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='background:#101218;color:white;font-family:sans-serif;padding:24px'><h2>Mini OBS Remote</h2><p>ゲーム端末に表示されている6桁コードを入力</p><input id='c' inputmode='numeric' maxlength='6' style='font-size:24px;width:150px'><button onclick="location='/?code='+c.value" style='font-size:20px'>接続</button></body></html>"""

    private fun studioPage(): String {
        val code = pairCode
        return """<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'><style>
body{margin:0;background:#0f1117;color:#eee;font-family:system-ui,sans-serif}header{padding:10px 12px;background:#181b24;position:sticky;top:0;z-index:4}#status{font-size:12px;color:#aab2c0}.wrap{padding:10px;max-width:900px;margin:auto}.preview{position:relative;background:#000;border-radius:10px;overflow:hidden;touch-action:none}.preview img{width:100%;display:block;min-height:180px;object-fit:contain}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin-top:9px}button{background:#292e3d;color:white;border:0;border-radius:9px;padding:13px 6px;font-weight:700}.red{background:#8f2534}.green{background:#17663c}.orange{background:#7b4d13}.panel{background:#181b24;border-radius:11px;padding:10px;margin-top:9px}input[type=range]{width:100%}small{color:#9ba4b5}.row{display:flex;gap:6px}.row>*{flex:1}#chat{height:100px;overflow:auto;background:#0b0d12;padding:6px;font-size:12px}</style></head><body>
<header><b>Mini OBS Dual Phone Studio</b><div id='status'>接続中…</div></header><div class='wrap'>
<div class='preview' id='pv'><img id='img' src='/preview.jpg?code=$code&t=0'></div>
<div class='grid' id='deck'></div><button style='width:100%;margin-top:6px' onclick='editDeck()'>Stream Deckを編集</button>
<div class='panel'><b>シーン</b><div class='grid'><button onclick="cmd('scene',{value:'game'})">GAME</button><button onclick="cmd('scene',{value:'talk'})">TALK</button><button onclick="cmd('scene',{value:'wait'})">WAIT</button><button class='red' onclick="cmd('privacy')">緊急非表示</button></div></div>
<div class='panel'><b>ソース編集</b><div class='row'><button onclick="cmd('select',{value:'text'})">文字</button><button onclick="cmd('select',{value:'image'})">画像</button><button onclick="cmd('select',{value:'camera'})">カメラ</button><button onclick="cmd('select',{value:'external1'})">外部Cam1</button></div><small>プレビューを指でドラッグすると選択ソースを移動</small><input id='scale' type='range' min='8' max='80' value='28' oninput="cmd('scale',{value:this.value})"></div>
<div class='panel'><b>Audio Mixer</b><label>MIC <span id='mv'>100</span>%</label><input type='range' min='0' max='200' value='100' oninput="mv.textContent=this.value;cmd('micVolume',{value:this.value})"><label>GAME <span id='iv'>100</span>%</label><input type='range' min='0' max='200' value='100' oninput="iv.textContent=this.value;cmd('internalVolume',{value:this.value})"><div class='row'><button onclick="cmd('mute')">MIC MUTE</button><button onclick="playMonitor()">3秒音声チェック</button></div></div>
<div class='panel'><b>Replay / Marker</b><div class='row'><button onclick="cmd('replay')">直前を保存</button><button onclick="cmd('marker')">★ MARK</button></div></div>
<div class='panel'><b>Chat / Alert relay</b><div id='chat'>外部チャット連携の表示欄</div><div class='row'><input id='chatText' placeholder='テロップ/コメント'><button onclick="cmd('chat',{text:chatText.value});chatText.value=''">送信</button></div></div>
<div class='panel'><b>Health</b><pre id='health' style='white-space:pre-wrap;font-size:11px'></pre></div>
</div><script>
const CODE='$code';
function qs(o){return Object.entries(o||{}).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(v)).join('&')}
async function cmd(c,a={}){try{await fetch('/api/cmd?code='+CODE+'&cmd='+encodeURIComponent(c)+'&'+qs(a),{method:'POST'});}catch(e){}}
let defs=['record','replay','marker','privacy','scene:game','scene:talk','scene:wait','mute'];let actions=JSON.parse(localStorage.getItem('miniobsDeck')||JSON.stringify(defs));
function label(a){return ({record:'REC',replay:'REPLAY',marker:'MARK',privacy:'PANIC',mute:'MIC',stream:'LIVE'})[a]||a.replace('scene:','').toUpperCase()}
function deckRun(a){if(a.startsWith('scene:'))cmd('scene',{value:a.split(':')[1]});else cmd(a)}
function renderDeck(){deck.innerHTML='';actions.forEach(a=>{let b=document.createElement('button');b.textContent=label(a);if(a==='privacy')b.className='red';b.onclick=()=>deckRun(a);deck.appendChild(b)})}
function editDeck(){let x=prompt('8個の操作をカンマ区切りで指定\nrecord,stream,replay,marker,privacy,mute,scene:game,scene:talk,scene:wait',actions.join(','));if(x){actions=x.split(',').map(v=>v.trim()).filter(Boolean).slice(0,8);while(actions.length<8)actions.push('marker');localStorage.setItem('miniobsDeck',JSON.stringify(actions));renderDeck()}}
renderDeck();
let lastX=0,lastY=0,drag=false;pv.onpointerdown=e=>{drag=true;lastX=e.clientX;lastY=e.clientY;pv.setPointerCapture(e.pointerId)};pv.onpointermove=e=>{if(!drag)return;let dx=(e.clientX-lastX)*100/pv.clientWidth,dy=(e.clientY-lastY)*100/pv.clientHeight;lastX=e.clientX;lastY=e.clientY;cmd('move',{dx:dx.toFixed(2),dy:dy.toFixed(2)})};pv.onpointerup=()=>drag=false;
function refreshPreview(){img.src='/preview.jpg?code='+CODE+'&t='+Date.now()} img.onload=()=>setTimeout(refreshPreview,450);img.onerror=()=>setTimeout(refreshPreview,1000);
async function refresh(){try{let s=await (await fetch('/api/status?code='+CODE)).json();status.textContent=(s.live?'LIVE ':'')+(s.recording?'REC ':'')+'Scene:'+s.scene+'  '+(s.bitrateKbps||0)+'kbps  '+s.network+'  '+s.temperature+'℃';health.textContent=JSON.stringify(s,null,2);if(s.chat)chat.innerHTML=s.chat.map(x=>'<div>'+x+'</div>').join('');}catch(e){status.textContent='再接続中…'}setTimeout(refresh,1000)}
function playMonitor(){let a=new Audio('/monitor.wav?code='+CODE+'&t='+Date.now());a.play()} refresh();
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
        val data = ByteArray(size); var pos = 0
        while (pos < size) { val n = read(data, pos, size - pos); if (n <= 0) break; pos += n }
        return if (pos == size) data else data.copyOf(pos)
    }

    private fun respond(out: BufferedOutputStream, status: Int, type: String, body: ByteArray, cache: Boolean = true) {
        val text = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 503 -> "Unavailable"; else -> "Error" }
        val header = buildString {
            append("HTTP/1.1 $status $text\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            if (!cache) append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
        }
        out.write(header.toByteArray()); out.write(body); out.flush()
    }

    private fun localIpv4(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { !it.startsWith("169.254.") }
    }.getOrNull()
}
