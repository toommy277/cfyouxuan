package com.sidewang.cfoptimizer

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.HttpsURLConnection

class LocalApiServer(private val context: Context) : NanoHTTPD("127.0.0.1", 8788) {
    private val regionNames = linkedMapOf(
        "HK" to "中国香港 HK", "TW" to "中国台湾 TW", "SG" to "新加坡 SG", "JP" to "日本 JP",
        "US" to "美国 US", "KR" to "韩国 KR", "GB" to "英国 GB", "DE" to "德国 DE",
        "RU" to "俄罗斯 RU", "NL" to "荷兰 NL", "SE" to "瑞典 SE", "AU" to "澳大利亚 AU",
        "VN" to "越南 VN", "IN" to "印度 IN", "LV" to "拉脱维亚 LV", "FI" to "芬兰 FI"
    )
    private val routeCodes = setOf("NRT", "KIX", "HND", "HKG", "SIN", "TPE", "ICN", "LAX", "SJC", "FRA", "AMS", "LHR", "CDG", "ORD", "IAD", "SEA", "DFW", "MEL", "SYD")

    // 测速：单次有效下载窗口约 6s（对齐 CFData）；多次请求在后端按 1.5s 间隔排队，连点不报错
    private val minSpeedIntervalMs = 1500L
    private val speedReadWindowMs = 6000L
    private val speedQueueLock = Any()
    private var nextSpeedAllowedAt = 0L

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/" -> html()
                session.method == Method.GET && session.uri == "/favicon.ico" -> newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                session.method == Method.GET && session.uri == "/api/regions" -> {
                    val regions = JSONObject()
                    regionNames.forEach { (code, name) -> regions.put(code, JSONObject().put("name", name)) }
                    json(JSONObject().put("regions", regions))
                }
                session.method == Method.GET && session.uri == "/api/list" -> listItems(session)
                session.method == Method.POST && session.uri == "/api/optimize" -> optimize(body(session))
                session.method == Method.POST && session.uri == "/api/speed" -> speed(body(session))
                session.method == Method.POST && session.uri == "/api/latency" -> latency(body(session))
                session.method == Method.POST && session.uri == "/api/upload" -> upload(body(session))
                else -> json(JSONObject().put("error", "Not found"), Response.Status.NOT_FOUND)
            }
        } catch (e: IllegalArgumentException) {
            json(JSONObject().put("error", e.message ?: "请求无效"), Response.Status.BAD_REQUEST)
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "服务器内部错误"), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun html(): Response {
        val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun body(session: IHTTPSession): JSONObject {
        // JSON 请求直接按 Content-Length 读取原始字节，并显式按 UTF-8 解码。
        // 避免依赖 parseBody(files) 对非表单 JSON 正文的中间解码行为。
        val contentType = session.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value.orEmpty()
        if (!contentType.startsWith("application/json", ignoreCase = true)) {
            throw IllegalArgumentException("请求 Content-Type 必须是 application/json; charset=utf-8")
        }

        val length = session.headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value?.toLongOrNull()
            ?: throw IllegalArgumentException("请求缺少有效的 Content-Length")
        if (length <= 0L || length > 2_000_000L) {
            throw IllegalArgumentException("请求正文长度无效")
        }

        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = session.inputStream.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IllegalArgumentException("请求正文未完整接收")
            offset += count
        }

        val raw = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("请求正文不是有效的 UTF-8 编码")
        }

        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("上传请求不是有效的 JSON")
        }
    }

    private fun json(obj: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

    private fun listItems(session: IHTTPSession): Response {
        val params = session.parameters
        val region = (params["region"]?.firstOrNull() ?: "JP").uppercase(Locale.ROOT)
        if (!regionNames.containsKey(region)) return json(JSONObject().put("error", "不支持的地区代码"), Response.Status.BAD_REQUEST)
        val custom = params["source"]?.firstOrNull()?.trim().orEmpty()
        val defaultSource = custom.isBlank()
        val isUrlSource = custom.startsWith("https://", ignoreCase = true) || custom.startsWith("http://", ignoreCase = true)
        val sourceLabel: String
        val raw: String
        if (defaultSource) {
            sourceLabel = "https://bestcf.pages.dev/random-region/$region/all.txt"
            raw = fetchText(sourceLabel)
        } else if (isUrlSource) {
            if (!custom.startsWith("https://", ignoreCase = true)) {
                return json(JSONObject().put("error", "数据源网址必须使用 HTTPS"), Response.Status.BAD_REQUEST)
            }
            sourceLabel = custom
            raw = fetchText(sourceLabel)
        } else {
            sourceLabel = "自定义输入"
            raw = custom
        }

        val map = LinkedHashMap<String, JSONObject>()
        raw.split(Regex("\\r?\\n")).forEachIndexed { i, line ->
            parseInputLine(line, i).forEach { item ->
                val key = "${item.optString("ip")}:${item.optInt("port")}"
                if (!map.containsKey(key)) map[key] = item
            }
        }
        val all = map.values.toList()
        // 仅自定义输入/自定义 TXT 自动补全缺失的国家和线路；已有字段不会被覆盖。
        if (!defaultSource && all.isNotEmpty()) detectMissingMetadata(all)

        val routes = JSONArray()
        all.map { it.optString("route") }.filter { it.isNotBlank() }.distinct().forEach { routes.put(it) }
        val requestedRoute = (params["route"]?.firstOrNull() ?: "全部").uppercase(Locale.ROOT)
        val items = if (requestedRoute == "全部" || requestedRoute == "ALL") all else all.filter { it.optString("route").uppercase(Locale.ROOT) == requestedRoute }
        return json(JSONObject().put("source", sourceLabel).put("region", region).put("count", items.size).put("total", all.size).put("routes", routes).put("items", JSONArray(items)))
    }

    private fun fetchText(source: String): String {
        val parsedUrl = URL(source)
        if (parsedUrl.protocol != "https") throw IllegalArgumentException("数据源网址必须使用 HTTPS")
        val conn = parsedUrl.openConnection() as HttpsURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "PhoneCFIPTool/1.0 Android")
        return try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("数据源返回 HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun parseInputLine(line: String, index: Int): List<JSONObject> {
        val raw = line.removePrefix("\uFEFF").trim()
        if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("//")) return emptyList()
        val cidr = parseCidrInput(raw, index)
        if (cidr != null) return cidr
        if ('/' in raw) throw IllegalArgumentException("CIDR 格式无效：$raw")
        return listOfNotNull(parseLine(raw, index))
    }

    private fun parseCidrInput(raw: String, index: Int): List<JSONObject>? {
        var cidrText = ""
        var portText = ""
        val bracketedV6 = Regex("^\\[([0-9a-fA-F:]+/\\d{1,3})](?::(\\d{1,5}))?$").find(raw)
        val ipv4 = Regex("^((?:\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2})(?::(\\d{1,5}))?$").find(raw)
        val ipv6 = Regex("^([0-9a-fA-F:]+/\\d{1,3})$").find(raw)
        when {
            bracketedV6 != null -> { cidrText = bracketedV6.groupValues[1]; portText = bracketedV6.groupValues[2] }
            ipv4 != null -> { cidrText = ipv4.groupValues[1]; portText = ipv4.groupValues[2] }
            ipv6 != null -> { cidrText = ipv6.groupValues[1] }
            else -> return null
        }
        val port = if (portText.isBlank()) 443 else portText.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("CIDR 端口无效：$raw")
        val parts = cidrText.split('/')
        if (parts.size != 2) throw IllegalArgumentException("CIDR 格式无效：$raw")
        val address = parts[0]
        val bytes = try {
            if (address.contains(':')) {
                if (InetAddress.getByName(address) !is java.net.Inet6Address) throw IllegalArgumentException("IPv6 地址无效")
                InetAddress.getByName(address).address
            } else {
                if (!validIp(address)) throw IllegalArgumentException("IPv4 地址无效")
                address.split('.').map { it.toInt().toByte() }.toByteArray()
            }
        } catch (e: Exception) { throw IllegalArgumentException("CIDR 地址无效：$raw") }
        val bits = bytes.size * 8
        val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..bits }
            ?: throw IllegalArgumentException("CIDR 前缀长度无效：$raw")
        val hostBits = bits - prefix
        val base = java.math.BigInteger(1, bytes)
        val hostRange = java.math.BigInteger.ONE.shiftLeft(hostBits)
        val network = if (hostBits == 0) base else base.divide(hostRange).multiply(hostRange)
        val count = if (hostRange <= java.math.BigInteger.valueOf(512)) hostRange.toInt() else 512
        val offsets = if (hostRange <= java.math.BigInteger.valueOf(512)) {
            (0 until count).map { java.math.BigInteger.valueOf(it.toLong()) }
        } else {
            val random = SecureRandom()
            val picked = LinkedHashSet<java.math.BigInteger>()
            while (picked.size < 512) {
                val value = java.math.BigInteger(hostBits.coerceAtLeast(1), random)
                if (value < hostRange) picked.add(value)
            }
            picked.toList()
        }
        return offsets.mapIndexed { offsetIndex, offset ->
            val value = network.add(offset)
            val rawBytes = value.toByteArray()
            val normalized = ByteArray(bytes.size)
            val sourceStart = (rawBytes.size - normalized.size).coerceAtLeast(0)
            val copyLength = minOf(rawBytes.size, normalized.size)
            System.arraycopy(rawBytes, sourceStart, normalized, normalized.size - copyLength, copyLength)
            val ip = InetAddress.getByAddress(normalized).hostAddress.substringBefore('%')
            JSONObject().put("id", "$ip:$port:${index}_$offsetIndex").put("ip", ip).put("port", port)
                .put("label", "").put("route", "").put("country", "").put("original", raw)
                .put("latencyMs", JSONObject.NULL).put("speedMbps", JSONObject.NULL).put("speedError", JSONObject.NULL).put("speedStatus", "")
        }
    }

    private fun parseLine(line: String, index: Int): JSONObject? {
        val raw = line.removePrefix("\uFEFF").trim()
        if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("//")) return null
        val bracketed = Regex("^\\[([0-9a-fA-F:.%]+)](?::(\\d{1,5}))?(.*)$").find(raw)
        val ipv4 = Regex("^((?:\\d{1,3}\\.){3}\\d{1,3})(?::(\\d{1,5}))?(.*)$").find(raw)
        val domain = Regex("^([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::(\\d{1,5}))?(.*)$").find(raw)
        val bareV6 = raw.takeWhile { !it.isWhitespace() && it !in ",;|#" }
        val ip: String
        val portText: String
        val suffix: String
        when {
            bracketed != null -> {
                ip = bracketed.groupValues[1]
                portText = bracketed.groupValues[2]
                suffix = bracketed.groupValues[3]
            }
            ipv4 != null -> {
                ip = ipv4.groupValues[1]
                portText = ipv4.groupValues[2]
                suffix = ipv4.groupValues[3]
            }
            bareV6.contains(':') && validIp(bareV6) -> {
                ip = bareV6
                portText = ""
                suffix = raw.removePrefix(bareV6)
            }
            domain != null -> {
                ip = domain.groupValues[1]
                portText = domain.groupValues[2]
                suffix = domain.groupValues[3]
            }
            else -> return null
        }
        if (!validHost(ip)) return null
        val port = portText.ifBlank { "443" }.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val cleanSuffix = suffix.trim()
        val hash = cleanSuffix.indexOf('#')
        val fieldsText = (if (hash >= 0) cleanSuffix.substring(0, hash) else cleanSuffix).trim()
        val label = (if (hash >= 0) cleanSuffix.substring(hash + 1) else fieldsText).trim()
        val fields = fieldsText.split(Regex("[\\t,;| ]+")).filter { it.isNotBlank() }
        var route = fields.firstOrNull { it.uppercase(Locale.ROOT) in routeCodes }?.uppercase(Locale.ROOT) ?: ""
        if (route.isBlank()) {
            route = Regex("(?:^|[^A-Za-z0-9])([A-Z]{3})(?:$|[^A-Za-z0-9])", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
        }
        val country = detectCountry(raw).ifBlank { "" }
        return JSONObject().put("id", "$ip:$port:$index").put("ip", ip).put("port", port)
            .put("label", label.ifBlank { fieldsText }).put("route", route).put("country", country).put("original", raw)
            .put("latencyMs", JSONObject.NULL).put("speedMbps", JSONObject.NULL).put("speedError", JSONObject.NULL).put("speedStatus", "")
    }

    private fun detectMissingMetadata(items: List<JSONObject>) {
        // 若线路已存在，可以可靠地从已知机房映射补国家；不会覆盖数据源原有国家。
        items.forEach { item ->
            if (item.optString("country").isBlank()) countryForColo(item.optString("route")).takeIf { it.isNotBlank() }?.let { item.put("country", it) }
        }
        val pending = items.filter { item -> item.optString("country").isBlank() || item.optString("route").isBlank() || item.optString("route").equals("none", ignoreCase = true) }
        if (pending.isEmpty()) return
        val next = AtomicInteger(0)
        val workers = (0 until minOf(32, pending.size)).map {
            Thread {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= pending.size) break
                    val item = pending[i]
                    val colo = detectColo(item) ?: continue
                    if (item.optString("route").isBlank() || item.optString("route").equals("none", ignoreCase = true)) item.put("route", colo)
                    if (item.optString("country").isBlank()) countryForColo(colo).takeIf { it.isNotBlank() }?.let { item.put("country", it) }
                }
            }.apply { isDaemon = true; start() }
        }
        workers.forEach { it.join() }
    }

    private fun detectColo(item: JSONObject): String? {
        val host = item.optString("ip").trim()
        val port = item.optInt("port", 443)
        if (!validHost(host)) return null
        // IP 地址通过 Cloudflare 的主机名握手并把 TCP 连接固定到该 IP；域名则保留其自身 SNI。
        val sniHost = if (validIp(host)) "www.cloudflare.com" else host
        return try {
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
            socket.soTimeout = 1200
            socket.connect(InetSocketAddress(host, port), 1000)
            socket.use { ssl ->
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                ssl.sslParameters = params
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(sniHost, ssl.session)) return null
                val out = BufferedOutputStream(ssl.outputStream)
                out.write(("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $sniHost\r\nUser-Agent: PhoneCFIPTool/1.0 Android\r\nConnection: close\r\nAccept: text/plain\r\n\r\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                val input = BufferedInputStream(ssl.inputStream)
                val statusLine = readHttpLine(input) ?: return null
                val status = Regex("HTTP/\\d\\.\\d\\s+(\\d+)").find(statusLine)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                if (status !in 200..299) return null
                var headerBytes = 0
                while (true) {
                    val line = readHttpLine(input) ?: return null
                    headerBytes += line.length
                    if (headerBytes > 16384) return null
                    if (line.isEmpty()) break
                }
                val body = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (body.size() < 8192) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 8192 - body.size()))
                    if (n <= 0) break
                    body.write(buffer, 0, n)
                }
                Regex("(?m)^colo=([A-Z]{3})\\s*$").find(body.toString("UTF-8"))?.groupValues?.get(1)
            }
        } catch (_: Exception) { null }
    }

    private fun countryForColo(colo: String): String = when (colo.uppercase(Locale.ROOT)) {
        "NRT", "KIX", "HND" -> "JP"
        "HKG" -> "HK"
        "SIN" -> "SG"
        "TPE" -> "TW"
        "ICN" -> "KR"
        "LAX", "SJC", "ORD", "IAD", "SEA", "DFW", "ATL", "MIA", "BOS", "DEN", "PHX", "PDX", "SLC", "EWR", "MSP", "MCI", "IAH", "CLT", "DTW", "BWI", "LAS", "SAN", "SNA", "AUS", "RDU", "CLE", "PIT", "CMH", "IND", "BNA", "SAT", "OKC", "ABQ", "BUF", "RIC", "JAX", "ORF", "TPA", "MCO", "FLL", "PBI", "RSW", "CHS", "SAV", "GSP", "PWM", "BTV", "ALB", "SYR", "ROC", "GRR", "FNT", "TUL", "LIT", "SHV", "LBB", "AMA", "MAF", "ELP", "TUS", "YUM", "FAT", "RNO", "BOI", "GEG", "BIL", "FSD", "OMA", "DSM", "CID", "PIA", "EVV", "LEX", "SDF", "DAY", "CAK", "ERI", "AVP", "ITH", "MHT", "BDL", "HVN", "ACK", "MVY" -> "US"
        "FRA", "MUC", "DUS", "HAM", "BER", "STR", "NUE", "LEJ", "HAJ", "CGN" -> "DE"
        "AMS" -> "NL"
        "LHR", "MAN", "EDI", "GLA", "BHX", "BRS" -> "GB"
        "CDG", "ORY", "MRS", "LYS", "TLS", "NCE", "BOD", "NTE", "LIL", "MPL" -> "FR"
        "MEL", "SYD", "BNE", "PER", "ADL", "CBR" -> "AU"
        "YYZ", "YUL", "YVR", "YOW", "YYC", "YEG", "YHZ", "YWG" -> "CA"
        "MAD", "BCN", "VLC", "AGP", "BIO", "SVQ" -> "ES"
        "FCO", "MXP", "LIN", "VCE", "NAP", "BLQ" -> "IT"
        "ZRH", "GVA", "BSL" -> "CH"
        "VIE" -> "AT"
        "BRU" -> "BE"
        "CPH", "BLL" -> "DK"
        "ARN", "GOT", "MMX" -> "SE"
        "OSL", "BGO", "TRD" -> "NO"
        "HEL" -> "FI"
        "WAW", "KRK", "KTW" -> "PL"
        "PRG", "BRQ" -> "CZ"
        "BUD" -> "HU"
        "OTP" -> "RO"
        "ATH", "SKG" -> "GR"
        "IST", "SAW" -> "TR"
        "DXB", "AUH" -> "AE"
        "JNB", "CPT", "DUR" -> "ZA"
        "GRU", "GIG", "POA", "BSB" -> "BR"
        "EZE", "COR" -> "AR"
        "MEX", "GDL", "MTY", "QRO" -> "MX"
        "DEL", "BOM", "BLR", "MAA", "HYD", "CCU" -> "IN"
        "BKK" -> "TH"
        "KUL" -> "MY"
        "CGK" -> "ID"
        "MNL" -> "PH"
        "HAN", "SGN", "DAD" -> "VN"
        "AKL", "WLG", "CHC" -> "NZ"
        else -> ""
    }

    private fun detectCountry(raw: String): String {
        val afterHash = raw.substringAfter('#', "")
        val pipeParts = afterHash.split('|').map { it.trim() }
        if (pipeParts.size >= 2) {
            val candidate = pipeParts[1].uppercase(Locale.ROOT)
            if (candidate.matches(Regex("[A-Z]{2}")) && candidate !in routeCodes) return candidate
        }
        val tokens = raw.substringAfter('#', raw).split(Regex("[,;|\\s]+"))
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.matches(Regex("[A-Z]{2}")) }
        val excluded = routeCodes + setOf("CF", "IP", "MS", "MB", "GBPS", "HTTP", "HTTPS")
        return tokens.firstOrNull { it !in excluded } ?: ""
    }

    private fun validIp(ip: String): Boolean {
        if (ip.contains(':')) return try { java.net.InetAddress.getByName(ip) is java.net.Inet6Address } catch (_: Exception) { false }
        val parts = ip.split('.')
        return parts.size == 4 && parts.all { part -> part.isNotEmpty() && (part.toIntOrNull()?.let { n -> n in 0..255 } == true) && (part.length == 1 || !part.startsWith('0')) }
    }

    private fun validHost(host: String): Boolean {
        if (validIp(host)) return true
        // 不把格式错误的纯数字 IPv4 当成域名放行。
        if (host.matches(Regex("\\d+(?:\\.\\d+){3}"))) return false
        if (host.length !in 1..253) return false
        return host.matches(Regex("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*"))
    }

    private fun validItem(item: JSONObject): Boolean = validHost(item.optString("ip")) && item.optInt("port", 0) in 1..65535

    private fun optimize(input: JSONObject): Response {
        val source = input.optJSONArray("items") ?: JSONArray()
        val items = (0 until source.length()).mapNotNull { source.optJSONObject(it)?.takeIf(::validItem) }
        if (items.isEmpty()) return json(JSONObject().put("error", "没有有效 IP"), Response.Status.BAD_REQUEST)
        val results = arrayOfNulls<JSONObject>(items.size)
        val next = AtomicInteger(0)
        val threads = (0 until minOf(16, items.size)).map {
            Thread {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= items.size) break
                    results[i] = tcpProbe(items[i])
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val alive = results.filterNotNull().filter { it.optBoolean("ok") && !it.isNull("latencyMs") }.sortedBy { it.optDouble("latencyMs") }
        return json(JSONObject().put("count", items.size).put("alive", alive.size).put("items", JSONArray(alive)))
    }

    private fun tcpProbe(item: JSONObject): JSONObject {
        val out = JSONObject(item.toString())
        val start = System.currentTimeMillis()
        return try {
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress(item.getString("ip"), item.getInt("port")), 2500)
            }
            out.put("ok", true).put("latencyMs", System.currentTimeMillis() - start)
        } catch (e: Exception) {
            out.put("ok", false).put("latencyMs", JSONObject.NULL).put("error", when (e) {
                is java.net.SocketTimeoutException -> "连接超时"
                else -> e.message ?: "连接失败"
            })
        }
    }

    private fun latency(input: JSONObject): Response {
        val item = input.optJSONObject("item") ?: return json(JSONObject().put("error", "缺少 IP 信息"), Response.Status.BAD_REQUEST)
        if (!validItem(item)) return json(JSONObject().put("error", "IP 或端口无效"), Response.Status.BAD_REQUEST)
        val result = tcpProbe(item)
        return if (result.optBoolean("ok")) json(result)
        else json(result, Response.Status.BAD_REQUEST)
    }

    private fun speed(input: JSONObject): Response {
        val item = input.optJSONObject("item")
        if (item == null || !validItem(item)) return json(JSONObject().put("error", "IP 或端口无效"), Response.Status.BAD_REQUEST)
        val pipeIn = java.io.PipedInputStream(64 * 1024)
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        Thread {
            val writer = BufferedOutputStream(pipeOut)
            fun emit(data: JSONObject) {
                try { writer.write(("data: " + data.toString() + "\n\n").toByteArray(Charsets.UTF_8)); writer.flush() } catch (_: Exception) {}
            }
            var received = 0L
            var start = System.currentTimeMillis()
            try {
                // 排队：两次实际开测至少间隔 1.5s；前端可连点，只延后执行，不返回错误
                val delayMs = synchronized(speedQueueLock) {
                    val now = System.currentTimeMillis()
                    val startAt = maxOf(now, nextSpeedAllowedAt)
                    nextSpeedAllowedAt = startAt + minSpeedIntervalMs
                    startAt - now
                }
                if (delayMs > 0L) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("测速已取消")
                    }
                }
                start = System.currentTimeMillis()
                val ip = item.getString("ip")
                val port = item.getInt("port")
                val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
                socket.soTimeout = 10000
                socket.connect(InetSocketAddress(ip, port), 5000)
                val params: SSLParameters = socket.sslParameters
                params.serverNames = listOf(SNIHostName("speed.cloudflare.com"))
                socket.sslParameters = params
                socket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify("speed.cloudflare.com", socket.session)) {
                    socket.close(); throw IllegalStateException("TLS 证书主机名验证失败")
                }
                val out = BufferedOutputStream(socket.getOutputStream())
                out.write(("GET /__down?bytes=99999999 HTTP/1.1\r\nHost: speed.cloudflare.com\r\nUser-Agent: PhoneCFIPTool/1.0 Android\r\nConnection: close\r\nAccept: */*\r\n\r\n").toByteArray(Charsets.US_ASCII)); out.flush()
                val inputStream = BufferedInputStream(socket.getInputStream())
                val statusLine = readHttpLine(inputStream) ?: throw IllegalStateException("测速端点无响应")
                val status = Regex("HTTP/\\d\\.\\d\\s+(\\d+)").find(statusLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readHttpLine(inputStream) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
                }
                if (status !in 200..299) { socket.close(); throw IllegalStateException("测速端点返回 HTTP $status") }
                val startRead = System.currentTimeMillis()
                var lastTime = startRead
                var lastBytes = 0L
                val chunked = headers["transfer-encoding"]?.contains("chunked", true) == true
                val length = headers["content-length"]?.toLongOrNull()
                if (chunked) {
                    while (System.currentTimeMillis() - startRead < speedReadWindowMs) {
                        val line = readHttpLine(inputStream) ?: break
                        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: break
                        if (size <= 0) { readHttpLine(inputStream); break }
                        var remain = size
                        val buffer = ByteArray(16 * 1024)
                        while (remain > 0) {
                            val n = inputStream.read(buffer, 0, minOf(buffer.size.toLong(), remain).toInt())
                            if (n <= 0) throw IllegalStateException("测速连接中断")
                            received += n; remain -= n
                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 250) {
                                emit(progress(received, now - start, received - lastBytes, now - lastTime))
                                lastBytes = received; lastTime = now
                            }
                        }
                        readHttpLine(inputStream)
                    }
                } else {
                    val buffer = ByteArray(16 * 1024)
                    while (System.currentTimeMillis() - startRead < speedReadWindowMs && (length == null || received < length)) {
                        val want = if (length == null) buffer.size else minOf(buffer.size.toLong(), length - received).toInt()
                        val n = inputStream.read(buffer, 0, want)
                        if (n < 0) break
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 250) {
                            emit(progress(received, now - start, received - lastBytes, now - lastTime))
                            lastBytes = received; lastTime = now
                        }
                    }
                }
                socket.close()
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1)
                emit(JSONObject().put("type", "done").put("ok", received > 0).put("error", if (received > 0) JSONObject.NULL else "未接收到测速数据").put("receivedBytes", received).put("elapsedMs", elapsed).put("avgMbps", received * 8.0 / elapsed / 1000.0))
            } catch (e: Exception) {
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1)
                emit(JSONObject().put("type", "done").put("ok", false).put("error", e.message ?: "测速失败").put("receivedBytes", received).put("elapsedMs", elapsed).put("avgMbps", if (received > 0) received * 8.0 / elapsed / 1000.0 else 0))
            } finally { try { writer.close() } catch (_: Exception) {} }
        }.apply { isDaemon = true; start() }
        return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn).apply {
            addHeader("Cache-Control", "no-cache, no-transform")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun progress(bytes: Long, elapsed: Long, deltaBytes: Long, deltaMs: Long): JSONObject = JSONObject()
        .put("type", "progress").put("receivedBytes", bytes)
        .put("currentMbps", deltaBytes * 8.0 / deltaMs.coerceAtLeast(1) / 1000.0)
        .put("avgMbps", bytes * 8.0 / elapsed.coerceAtLeast(1) / 1000.0).put("elapsedMs", elapsed)

    private fun readHttpLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var previous = -1
        while (out.size() < 16384) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("US-ASCII")
            if (previous == 13 && b == 10) {
                val bytes = out.toByteArray()
                return String(if (bytes.isNotEmpty() && bytes.last() == 13.toByte()) bytes.copyOf(bytes.size - 1) else bytes, Charsets.US_ASCII)
            }
            out.write(b); previous = b
        }
        throw IllegalStateException("HTTP 响应头过长")
    }

    private fun upload(input: JSONObject): Response {
        val prefs = context.getSharedPreferences("worker_config", Context.MODE_PRIVATE)
        val workerUrl = prefs.getString("workerUrl", "")?.trim().orEmpty().trimEnd('/')
        val secret = prefs.getString("workerSecret", "").orEmpty()
        if (workerUrl.isBlank() || secret.isBlank()) return json(JSONObject().put("error", "请先在 App 右上角菜单配置 Worker 地址和密钥"), Response.Status.BAD_REQUEST)
        val inputText = input.optString("text", "")
        val mode = input.optString("mode", "overwrite").lowercase(Locale.ROOT)
        if (mode !in setOf("overwrite", "append")) return json(JSONObject().put("error", "不支持的上传模式"), Response.Status.BAD_REQUEST)
        if ('\uFFFD' in inputText) {
            return json(JSONObject().put("error", "上传文本已包含乱码替换字符，已取消更新，请检查上游 UTF-8 编码"), Response.Status.BAD_REQUEST)
        }
        val incomingLines = inputText.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotBlank() }
        if (incomingLines.isEmpty()) return json(JSONObject().put("error", "请至少选择 1 个 IP"), Response.Status.BAD_REQUEST)
        val url = URL(workerUrl)
        if (url.protocol != "https") return json(JSONObject().put("error", "Worker 地址必须使用 HTTPS"), Response.Status.BAD_REQUEST)

        var outgoingLines = incomingLines
        var added = incomingLines.size
        var updated = 0
        if (mode == "append") {
            // 追加前必须成功读取当前订阅；读取失败时绝不退化为空文件覆盖。
            val readConn = url.openConnection() as HttpsURLConnection
            readConn.requestMethod = "GET"
            readConn.connectTimeout = 15000; readConn.readTimeout = 15000
            readConn.setRequestProperty("Authorization", "Bearer $secret")
            readConn.setRequestProperty("Accept", "text/plain, */*")
            val readCode = readConn.responseCode
            val contentType = readConn.contentType.orEmpty()
            val existingText = (if (readCode in 200..299) readConn.inputStream else readConn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            readConn.disconnect()
            if (readCode !in 200..299) return json(JSONObject().put("error", "读取服务器现有订阅失败：HTTP $readCode，未修改服务器内容"), Response.Status.INTERNAL_ERROR)
            if (contentType.contains("text/html", ignoreCase = true) || existingText.trimStart().startsWith("<!DOCTYPE html", true) || existingText.trimStart().startsWith("<html", true)) {
                return json(JSONObject().put("error", "服务器读取接口返回的不是订阅文本，已取消追加以保护现有内容"), Response.Status.BAD_REQUEST)
            }
            val existingLines = existingText.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotBlank() }
            val normalizedExisting = LinkedHashMap<String, String>()
            existingLines.forEach { line ->
                val normalized = normalizeSubscriptionLine(line)
                    ?: return json(JSONObject().put("error", "无法识别服务器现有订阅中的一条记录，已取消追加以避免覆盖数据"), Response.Status.BAD_REQUEST)
                val key = normalized.substringBefore('#').trim()
                if (!normalizedExisting.containsKey(key)) normalizedExisting[key] = normalized
            }
            val incomingByIp = LinkedHashMap<String, String>()
            incomingLines.forEach { line ->
                val normalized = normalizeSubscriptionLine(line)
                    ?: return json(JSONObject().put("error", "无法识别本次上传记录，已取消追加"), Response.Status.BAD_REQUEST)
                // 以 IP+端口（# 前的地址）为唯一键：相同键更新原记录，不同端口视为新记录。
                val key = normalized.substringBefore('#').trim()
                incomingByIp[key] = normalized
            }
            added = incomingByIp.keys.count { !normalizedExisting.containsKey(it) }
            updated = incomingByIp.count { (key, line) ->
                normalizedExisting.containsKey(key) && normalizedExisting[key] != line
            }
            // LinkedHashMap 对已存在键赋值会保留原位置；更新信息但不影响其他 IP。
            incomingByIp.forEach { (key, line) -> normalizedExisting[key] = line }
            outgoingLines = normalizedExisting.values.toList()
        } else {
            val normalized = incomingLines.map { normalizeSubscriptionLine(it)
                ?: return json(JSONObject().put("error", "无法识别本次上传记录，已取消覆盖"), Response.Status.BAD_REQUEST) }
            outgoingLines = normalized.distinctBy { it.substringBefore('#').trim() }
        }

        val uploadUrl = URL("$workerUrl/update")
        val conn = uploadUrl.openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000; conn.readTimeout = 15000; conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $secret")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val outgoingText = outgoingLines.joinToString("\n") + "\n"
        if ('\uFFFD' in outgoingText) {
            conn.disconnect()
            return json(JSONObject().put("error", "发送前发现乱码替换字符，已取消更新"), Response.Status.BAD_REQUEST)
        }
        val payload = JSONObject().put("text", outgoingText).toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        val detail = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) return json(JSONObject().put("error", "Worker 更新失败：HTTP $code").put("detail", detail), Response.Status.INTERNAL_ERROR)
        return json(JSONObject().put("ok", true)
            .put("added", if (mode == "append") added else outgoingLines.size)
            .put("updated", if (mode == "append") updated else 0)
            .put("count", outgoingLines.size)
            .put("message", if (mode == "append") "订阅已追加" else "订阅已覆盖")
            .put("detail", detail))
    }

    private fun normalizeSubscriptionLine(line: String): String? {
        val trimmed = line.trim()
        // IPv6 输入必须使用 [IPv6]:端口；IPv4 和域名使用 host:端口。
        val match = Regex("^(?:\\[([0-9a-fA-F:.]+)]|([^\\s:#|]+)):(\\d{1,5})(.*)$").find(trimmed) ?: return null
        val host = match.groupValues[1].ifBlank { match.groupValues[2] }
        val port = match.groupValues[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (!validHost(host)) return null
        val ipPort = "${if (host.contains(':')) "[$host]" else host}:$port"
        val tail = match.groupValues[4]
        val parts = tail.substringAfter('#', "").split('|').map { it.trim() }
        var country = parts.getOrNull(1).orEmpty().uppercase(Locale.ROOT)
        var route = parts.getOrNull(2).orEmpty()
        var metrics = parts.getOrNull(3).orEmpty()
        if (metrics == ipPort || metrics == "$ipPort#自选") metrics = ""
        if (route.endsWith(ipPort)) route = route.removeSuffix(ipPort).trim()
        if (route.isBlank() || route == ipPort) {
            val allTokens = Regex("[A-Za-z]{2,4}").findAll(tail).map { it.value.uppercase(Locale.ROOT) }.toList()
            route = allTokens.firstOrNull { it in routeCodes } ?: ""
        }
        if (country == "自选" || country == ipPort || !country.matches(Regex("[A-Z]{2}"))) country = ""
        if (metrics.contains(ipPort)) metrics = metrics.substringBefore(ipPort).trim()
        return if (metrics.isNotBlank()) "$ipPort#自选 | $country | $route | $metrics | $ipPort"
        else "$ipPort#自选 | $country | $route | $ipPort"
    }

}
