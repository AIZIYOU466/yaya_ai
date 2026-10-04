package com.yaya.ai.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 纯 Kotlin 语法高亮（移植自 syntax_highlighter.dart，零依赖）。
 *
 * 正则用 Kotlin raw string（`"""`），反斜杠字面保留——天然规避 Dart 里
 * `\'` 在 raw string 中提前闭合的坑。颜色来自原 8 色暗/亮双调色板。
 */
object SyntaxHighlighter {

    private data class Palette(
        val comment: Color,
        val string: Color,
        val number: Color,
        val keyword: Color,
        val type: Color,
        val fn: Color,
        val attr: Color,
        val punct: Color,
    )

    private val dark = Palette(
        Color(0xFF6A7A8A), Color(0xFF98C379), Color(0xFFD19A66), Color(0xFFC678DD),
        Color(0xFF61AFEF), Color(0xFFE5C07B), Color(0xFF56B6C2), Color(0xFFABB2BF),
    )
    private val light = Palette(
        Color(0xFF6E7781), Color(0xFF22863A), Color(0xFF0550AE), Color(0xFFCF222E),
        Color(0xFF0969DA), Color(0xFF953800), Color(0xFF0550AE), Color(0xFF57606A),
    )

    /** 从路径推断语言。 */
    fun langFromPath(path: String): String {
        val base = path.substringAfterLast('/').lowercase()
        val dot = base.lastIndexOf('.')
        val ext = if (dot <= 0) "" else base.substring(dot + 1)
        return when (ext) {
            "dart" -> "dart"
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "jsx", "ts", "tsx", "mjs", "cjs" -> "js"
            "json", "jsonc" -> "json"
            "sh", "bash", "zsh", "fish", "ksh" -> "shell"
            "rs" -> "rust"
            "c", "h", "cpp", "cc", "cxx", "hpp", "hh" -> "c"
            "yml", "yaml", "toml", "ini", "conf" -> "yaml"
            "md", "markdown", "mdown" -> "markdown"
            "xml", "html", "htm", "svg", "xsl", "gradle" -> "xml"
            else -> if (base in setOf("makefile", "dockerfile", "jenkinsfile")) "shell" else "text"
        }
    }

    /** 语言显示名。 */
    fun langLabel(lang: String): String = when (lang) {
        "dart" -> "Dart"
        "kotlin" -> "Kotlin"
        "java" -> "Java"
        "python" -> "Python"
        "js" -> "JavaScript"
        "json" -> "JSON"
        "shell" -> "Shell"
        "rust" -> "Rust"
        "c" -> "C/C++"
        "yaml" -> "YAML"
        "markdown" -> "Markdown"
        "xml" -> "XML/HTML"
        else -> "文本"
    }

    /** 渲染为 AnnotatedString；未匹配部分沿用 Text 默认色，仅高亮命中段。 */
    fun render(text: String, lang: String, darkMode: Boolean): AnnotatedString {
        val p = if (darkMode) dark else light
        return when (lang) {
            "json" -> renderGeneric(text, jsonRe(), p) { m ->
                when {
                    m.groupValues[1].isNotEmpty() -> SpanStyle(color = if (text.getOrNull(m.range.last + 1)?.toString()?.trimStart()?.startsWith(":") == true) p.type else p.string)
                    m.groupValues[2].isNotEmpty() -> SpanStyle(color = p.number)
                    else -> SpanStyle(color = p.keyword)
                }
            }
            "xml" -> renderGeneric(text, xmlRe(), p) { m ->
                when {
                    m.groupValues[1].isNotEmpty() -> SpanStyle(color = p.comment)
                    m.groupValues[2].isNotEmpty() -> SpanStyle(color = p.string)
                    m.groupValues[3].isNotEmpty() -> SpanStyle(color = p.punct)
                    else -> SpanStyle(color = p.attr)
                }
            }
            else -> renderGeneric(text, codeRegex(lang), p) { m ->
                when {
                    m.groupValues[1].isNotEmpty() -> SpanStyle(color = p.comment)
                    m.groupValues[2].isNotEmpty() -> SpanStyle(color = p.string)
                    m.groupValues[3].isNotEmpty() -> SpanStyle(color = p.number)
                    else -> codeIdentifierStyle(lang, m.groupValues[4], text, m.range.last, p)
                }
            }
        }
    }

    private fun renderGeneric(
        text: String,
        re: Regex,
        p: Palette,
        styleOf: (MatchResult) -> SpanStyle?,
    ): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in re.findAll(text)) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            val style = styleOf(m)
            if (style != null) {
                withStyle(style) { append(text.substring(m.range.first, m.range.last + 1)) }
            } else {
                append(text.substring(m.range.first, m.range.last + 1))
            }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

    private fun codeIdentifierStyle(
        lang: String,
        id: String,
        text: String,
        end: Int,
        p: Palette,
    ): SpanStyle? {
        if (id.isEmpty()) return null
        keywords[lang]?.let { if (id in it) return SpanStyle(color = p.keyword, fontWeight = FontWeight.Bold) }
        builtins[lang]?.let { if (id in it) return SpanStyle(color = p.type) }
        if (text.getOrNull(end + 1) == '(') return SpanStyle(color = p.fn) // 函数调用
        if (id.firstOrNull()?.isUpperCase() == true) return SpanStyle(color = p.type) // 大写开头视为类型
        return null
    }

    // 组：1 注释、2 字符串、3 数字、4 标识符（标识符样式按语言表判定）。
    private val codeReCache = HashMap<String, Regex>()
    private fun codeRegex(lang: String): Regex = codeReCache.getOrPut(lang) {
        val comment = when (lang) {
            "python", "shell", "yaml" -> """#[^\n]*"""
            "c" -> """^\s*#\s*[\w.]+[^\n]*|//[^\n]*|/\*[\s\S]*?\*/"""
            else -> """//[^\n]*|/\*[\s\S]*?\*/"""
        }
        Regex(
            "($comment)" +
                """|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)""" +
                """|(\b\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b0x[0-9a-fA-F]+\b)""" +
                """|([A-Za-z_][A-Za-z0-9_]*)""",
            setOf(RegexOption.MULTILINE),
        )
    }

    private val jsonCache = lazy { Regex(
        """("(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null)"""
    ) }
    private fun jsonRe() = jsonCache.value

    private val xmlCache = lazy { Regex(
        """(<!--[\s\S]*?-->)""" +
            """|("[^"\n]*"|'[^'\n]*')""" +
            """|(<|>|/>)""" +
            """|([A-Za-z_][\w:.-]*)""",
    ) }
    private fun xmlRe() = xmlCache.value

    private fun set(s: String) = s.split(' ').toSet()

    private val keywords = mapOf(
        "dart" to set("abstract as async await break case catch class const continue covariant default defer do dynamic else enum export extends external factory final finally for get if implements import in interface is late library mixin new null on required return rethrow set static super switch sync this throw true false try typedef var void while with yield"),
        "kotlin" to set("abstract actual annotation as break by catch companion const constructor continue crossinline data do else enum external final finally for fun get if import in infix init inline inner interface internal is lateinit noinline null object open operator out override package private protected public reified return sealed set super suspend tailrec this throw true false try typealias typeof var val when where while"),
        "java" to set("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws true false transient try void volatile while var record"),
        "python" to set("and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield None True False match case"),
        "js" to set("async await break case catch class const continue debugger default delete do else enum export extends false finally for function get if implements import in instanceof interface let new null of package private protected public return set static super switch this throw true try typeof var void while with yield"),
        "rust" to set("as async await break const crate continue dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true try type unsafe use where while abstract become box do if macro priv typeof yield"),
        "c" to set("auto break case char const continue default do double else enum extern float for goto if int long register return short signed sizeof static struct switch typedef union unsigned void volatile while inline restrict _Bool _Complex true false"),
        "shell" to set("if then else elif fi case esac for while until do done in function return local export readonly declare typeset set unset shift exit exec source alias trap then break continue select time coproc"),
    )

    private val builtins = mapOf(
        "dart" to set("String int double num bool List Map Set Future Stream Widget BuildContext void Object dynamic print"),
        "python" to set("print len range int str float bool list dict tuple set type isinstance issubclass open file input abs max min sum sorted enumerate zip map filter any all repr id hash"),
        "js" to set("console Math JSON Object Array String Number Boolean Promise Map Set Symbol document window fetch require exports"),
        "rust" to set("String Vec HashMap Option Result Box Rc Arc Cell RefCell Some None Ok Err impl trait"),
        "c" to set("printf fprintf sprintf scanf malloc calloc realloc free NULL size_t char int void"),
    )
}