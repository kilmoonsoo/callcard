package app.callcard

import java.io.ByteArrayOutputStream

/**
 * 최소한의 MMS m-send-req PDU 인코더 (OMA-MMS-ENC 1.2, WAP-230-WSP).
 * multipart/related + SMIL 구조로 만듭니다. 국내 통신사 기본 메시지 앱도 같은 구조를 씁니다.
 */
object MmsPdu {

    fun build(to: String, subject: String, text: String, jpeg: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()

        // --- 헤더 ---
        out.write(0x8C); out.write(0x80)                                   // X-Mms-Message-Type: m-send-req
        out.write(0x98); writeText(out, "T" + System.currentTimeMillis().toString(16)) // Transaction-ID
        out.write(0x8D); out.write(0x92)                                   // MMS-Version 1.2
        out.write(0x89); out.write(0x01); out.write(0x81)                  // From: insert-address-token
        out.write(0x97); writeText(out, "$to/TYPE=PLMN")                   // To
        if (subject.isNotBlank()) {                                        // Subject (UTF-8)
            out.write(0x96)
            val body = ByteArrayOutputStream().apply { write(0xEA); writeText(this, subject) }.toByteArray()
            writeValueLength(out, body.size); out.write(body)
        }
        out.write(0x8A); out.write(0x80)                                   // Message-Class: personal
        out.write(0x8F); out.write(0x81)                                   // Priority: normal
        out.write(0x86); out.write(0x81)                                   // Delivery-Report: no
        out.write(0x90); out.write(0x81)                                   // Read-Report: no

        // Content-Type: multipart/related; type="application/smil"; start="<smil>"
        out.write(0x84)
        val ct = ByteArrayOutputStream().apply {
            write(0xB3)                                                    // multipart/related
            write(0x89); writeText(this, "application/smil")              // type
            write(0x8A); writeText(this, "<smil>")                        // start
        }.toByteArray()
        writeValueLength(out, ct.size); out.write(ct)

        // --- 본문 파트 ---
        val parts = mutableListOf<Part>()
        val hasText = text.isNotBlank()
        parts += Part(utf8Type("application/smil"), "<smil>", "smil.xml",
            smil(jpeg != null, hasText).toByteArray(Charsets.UTF_8))
        if (jpeg != null) parts += Part(byteArrayOf(0x9E.toByte()), "<image_0>", "image_0.jpg", jpeg) // image/jpeg
        if (hasText) parts += Part(utf8Type(null), "<text_0>", "text_0.txt", text.toByteArray(Charsets.UTF_8))

        writeUintvar(out, parts.size)
        for (p in parts) {
            val headers = ByteArrayOutputStream().apply {
                write(p.contentType)
                write(0xC0); write(0x22); writeRaw(this, p.contentId)     // Content-ID (quoted-string)
                write(0x8E); writeText(this, p.location)                  // Content-Location
            }.toByteArray()
            writeUintvar(out, headers.size)
            writeUintvar(out, p.data.size)
            out.write(headers)
            out.write(p.data)
        }
        return out.toByteArray()
    }

    private class Part(val contentType: ByteArray, val contentId: String, val location: String, val data: ByteArray)

    /** text/plain(=null) 또는 지정 타입 + charset=utf-8 */
    private fun utf8Type(type: String?): ByteArray {
        val body = ByteArrayOutputStream().apply {
            if (type == null) write(0x83) else writeText(this, type)
            write(0x81); write(0xEA)                                       // charset = utf-8
        }.toByteArray()
        return ByteArrayOutputStream().apply { writeValueLength(this, body.size); write(body) }.toByteArray()
    }

    private fun smil(image: Boolean, text: Boolean): String = buildString {
        append("<smil><head><layout><root-layout/>")
        if (image) append("<region id=\"Image\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\" fit=\"meet\"/>")
        if (text) append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\" fit=\"scroll\"/>")
        append("</layout></head><body>")
        if (image) append("<par dur=\"5000ms\"><img src=\"image_0.jpg\" region=\"Image\"/></par>")
        if (text) append("<par dur=\"5000ms\"><text src=\"text_0.txt\" region=\"Text\"/></par>")
        append("</body></smil>")
    }

    // --- WSP 기본 인코딩 ---
    private fun writeRaw(out: ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.UTF_8)); out.write(0)
    }

    /** Text-string: 첫 바이트가 0x80 이상(한글 등)이면 0x7F 인용 바이트를 붙입니다. */
    private fun writeText(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.isNotEmpty() && (b[0].toInt() and 0x80) != 0) out.write(0x7F)
        out.write(b); out.write(0)
    }

    private fun writeValueLength(out: ByteArrayOutputStream, len: Int) {
        if (len <= 30) out.write(len) else { out.write(0x1F); writeUintvar(out, len) }
    }

    internal fun writeUintvar(out: ByteArrayOutputStream, value: Int) {
        var v = value
        val tmp = ArrayList<Int>()
        tmp.add(v and 0x7F); v = v ushr 7
        while (v > 0) { tmp.add((v and 0x7F) or 0x80); v = v ushr 7 }
        for (i in tmp.indices.reversed()) out.write(tmp[i])
    }
}
