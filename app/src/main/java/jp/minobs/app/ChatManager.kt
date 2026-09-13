package jp.minobs.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

class ChatManager(private val onMessage: (String, String) -> Unit) {
    private val twitchRunning = AtomicBoolean(false)
    private val youtubeRunning = AtomicBoolean(false)
    private var twitchThread: Thread? = null
    private var youtubeThread: Thread? = null

    fun stopAll() { twitchRunning.set(false); youtubeRunning.set(false); twitchThread?.interrupt(); youtubeThread?.interrupt() }

    /** Read-only anonymous Twitch IRC. No account credential is stored. */
    fun startTwitch(channel: String) {
        twitchRunning.set(false); twitchThread?.interrupt()
        val clean = channel.trim().removePrefix("#").lowercase()
        if (clean.isBlank()) return
        twitchRunning.set(true)
        twitchThread = thread(name = "MiniOBS-Twitch", isDaemon = true) {
            while (twitchRunning.get()) {
                try {
                    val socket = (SSLSocketFactory.getDefault().createSocket("irc.chat.twitch.tv", 6697) as javax.net.ssl.SSLSocket)
                    val out = OutputStreamWriter(socket.outputStream)
                    val nick = "justinfan${10000 + (Math.random() * 80000).toInt()}"
                    out.write("PASS SCHMOOPIIE\r\nNICK $nick\r\nJOIN #$clean\r\n"); out.flush()
                    val reader = BufferedReader(InputStreamReader(socket.inputStream))
                    while (twitchRunning.get()) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("PING")) { out.write("PONG :tmi.twitch.tv\r\n"); out.flush(); continue }
                        val marker = " PRIVMSG #$clean :"
                        val p = line.indexOf(marker)
                        if (p > 0) {
                            val user = line.substringAfter(':').substringBefore('!')
                            val msg = line.substring(p + marker.length)
                            onMessage(user, msg)
                        }
                    }
                    runCatching { socket.close() }
                } catch (_: Exception) { try { Thread.sleep(2500) } catch (_: Exception) {} }
            }
        }
    }

    /** YouTube Data API reader. API key + liveChatId are kept by the caller locally. */
    fun startYouTube(liveChatId: String, apiKey: String) {
        youtubeRunning.set(false); youtubeThread?.interrupt()
        if (liveChatId.isBlank() || apiKey.isBlank()) return
        youtubeRunning.set(true)
        youtubeThread = thread(name = "MiniOBS-YouTube", isDaemon = true) {
            var pageToken = ""
            var interval = 4000L
            while (youtubeRunning.get()) {
                try {
                    val url = buildString {
                        append("https://www.googleapis.com/youtube/v3/liveChat/messages?part=snippet,authorDetails")
                        append("&liveChatId=").append(URLEncoder.encode(liveChatId, "UTF-8"))
                        append("&key=").append(URLEncoder.encode(apiKey, "UTF-8"))
                        if (pageToken.isNotBlank()) append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
                    }
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 5000
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val root = JSONObject(text)
                    pageToken = root.optString("nextPageToken", pageToken)
                    interval = root.optLong("pollingIntervalMillis", 4000L).coerceIn(1500L, 15000L)
                    val items = root.optJSONArray("items")
                    if (items != null) for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val author = item.optJSONObject("authorDetails")?.optString("displayName").orEmpty()
                        val msg = item.optJSONObject("snippet")?.optString("displayMessage").orEmpty()
                        if (msg.isNotBlank()) onMessage(author.ifBlank { "YouTube" }, msg)
                    }
                } catch (_: Exception) { interval = 5000L }
                try { Thread.sleep(interval) } catch (_: InterruptedException) { break }
            }
        }
    }
}
