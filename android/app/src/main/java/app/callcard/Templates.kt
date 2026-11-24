package app.callcard

/** 입력한 정보로 소개글 예시를 만듭니다. (인터넷·AI 없이 기기 안에서 만드는 정해진 틀입니다) */
object Templates {

    enum class Tone(val label: String) { POLITE("정중하게"), FRIENDLY("친근하게"), SHORT("간결하게") }

    data class Info(
        val org: String, val name: String, val title: String,
        val phone: String, val address: String, val hours: String,
    )

    data class Result(val subject: String, val intro: String, val missed: String)

    fun generate(i: Info, tone: Tone): Result {
        val who = listOf(i.org, i.name, i.title).filter { it.isNotBlank() }.joinToString(" ")
        val nameOnly = listOf(i.org, i.name).filter { it.isNotBlank() }.joinToString(" ")
        val contact = buildList {
            if (i.phone.isNotBlank()) add("📞 ${i.phone}")
            if (i.address.isNotBlank()) add("📍 ${i.address}")
            if (i.hours.isNotBlank()) add("🕘 ${i.hours}")
        }
        val contactBlock = contact.joinToString("\n")
        val subject = if (nameOnly.isNotBlank()) "$nameOnly 명함" else "명함 드립니다"

        val intro: String
        val missed: String
        when (tone) {
            Tone.POLITE -> {
                intro = lines(
                    "안녕하세요, ${who}입니다.",
                    "방금 통화 감사드립니다. 제 명함을 보내드립니다.",
                    "",
                    contactBlock,
                    "",
                    "궁금하신 점은 언제든 편하게 연락 주십시오.",
                )
                missed = lines(
                    "안녕하세요, ${who}입니다.",
                    "방금 전화를 받지 못해 죄송합니다.",
                    "확인하는 대로 바로 연락드리겠습니다.",
                    "",
                    "급하신 경우 문자로 용건을 남겨 주시면 빠르게 답변드리겠습니다.",
                    contactBlock,
                )
            }
            Tone.FRIENDLY -> {
                intro = lines(
                    "안녕하세요! ${who}${yeyo(who)} 😊",
                    "통화 반가웠습니다. 제 명함 보내드려요.",
                    "",
                    contactBlock,
                    "",
                    "궁금한 점 있으시면 편하게 연락 주세요!",
                )
                missed = lines(
                    "안녕하세요! ${who}${yeyo(who)}.",
                    "전화 못 받아서 죄송해요 🙏",
                    "확인하고 금방 다시 연락드릴게요.",
                    "",
                    "급하시면 문자로 남겨 주세요!",
                    contactBlock,
                )
            }
            Tone.SHORT -> {
                intro = lines(
                    "${who}입니다.",
                    "통화 감사합니다. 명함 보내드립니다.",
                    contact.joinToString("  "),
                )
                missed = lines(
                    "${who}입니다.",
                    "부재중이라 죄송합니다. 곧 연락드리겠습니다.",
                    contact.joinToString("  "),
                )
            }
        }
        return Result(subject, intro, missed)
    }

    /** 받침이 있으면 '이에요', 없으면 '예요' */
    private fun yeyo(word: String): String {
        val c = word.lastOrNull() ?: return "예요"
        if (c !in '가'..'힣') return "이에요"
        return if ((c - '가') % 28 != 0) "이에요" else "예요"
    }

    /** 빈 연락처 블록 때문에 생기는 빈 줄 중복을 정리합니다. */
    private fun lines(vararg l: String): String =
        l.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
