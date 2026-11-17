package com.celander.app.core.ical

/** A single unfolded iCalendar content line: `NAME;PARAM=value:VALUE`. */
data class ContentLine(
    val name: String,
    val params: Map<String, String>,
    val value: String,
) {
    fun param(key: String): String? = params[key.uppercase()]

    companion object {
        /** Joins folded lines (RFC 5545 §3.1) and drops empty ones. */
        fun unfold(text: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                if (raw.startsWith(" ") || raw.startsWith("\t")) {
                    current.append(raw, 1, raw.length)
                } else {
                    if (current.isNotBlank()) result += current.toString()
                    current.setLength(0)
                    current.append(raw)
                }
            }
            if (current.isNotBlank()) result += current.toString()
            return result
        }

        fun parse(line: String): ContentLine? {
            var i = 0
            while (i < line.length && line[i] != ';' && line[i] != ':') i++
            if (i == 0 || i >= line.length) return null
            val name = line.substring(0, i).trim().uppercase()
            val params = linkedMapOf<String, String>()
            while (i < line.length && line[i] == ';') {
                i++
                val eq = line.indexOf('=', i)
                if (eq < 0) return null
                val key = line.substring(i, eq).trim().uppercase()
                i = eq + 1
                val value = StringBuilder()
                var quoted = false
                while (i < line.length) {
                    val c = line[i]
                    if (c == '"') {
                        quoted = !quoted
                    } else if (!quoted && (c == ';' || c == ':')) {
                        break
                    } else {
                        value.append(c)
                    }
                    i++
                }
                params[key] = value.toString()
            }
            if (i >= line.length || line[i] != ':') return null
            return ContentLine(name, params, line.substring(i + 1))
        }

        fun unescapeText(value: String): String {
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (val next = value[i + 1]) {
                        'n', 'N' -> sb.append('\n')
                        else -> sb.append(next)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun escapeText(value: String): String = value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\r", "\\n")
            .replace("\n", "\\n")

        /** Folds a line to 75 octets per physical line without splitting UTF-8 sequences. */
        fun fold(line: String): String {
            if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
            val sb = StringBuilder()
            var octets = 0
            var limit = 75
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                val chars = Character.charCount(cp)
                val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (octets + size > limit) {
                    sb.append("\r\n ")
                    octets = 0
                    limit = 74
                }
                sb.appendCodePoint(cp)
                octets += size
                i += chars
            }
            return sb.toString()
        }
    }
}
