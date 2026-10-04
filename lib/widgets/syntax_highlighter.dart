import 'package:flutter/material.dart';

/// 按文件扩展名推断的语言标识（零依赖，ROADMAP 任务 22）。
///
/// Dart 正则不支持命名捕获组（`(?<name>...)`）也不允许类内嵌类，
/// 因此这里全部用普通捕获组（数字索引）区分 token，`_MdLine` 为顶层类。
enum Lang {
  dart,
  kotlin,
  java,
  python,
  js,
  json,
  shell,
  rust,
  c,
  yaml,
  markdown,
  xml,
  text,
}

/// 从路径推断语言；无扩展名或未知扩展名返回 [Lang.text]。
Lang langFromPath(String path) {
  final base = path.split('/').last.toLowerCase();
  final dot = base.lastIndexOf('.');
  final ext = dot <= 0 ? '' : base.substring(dot + 1);
  switch (ext) {
    case 'dart':
      return Lang.dart;
    case 'kt':
    case 'kts':
      return Lang.kotlin;
    case 'java':
      return Lang.java;
    case 'py':
      return Lang.python;
    case 'js':
    case 'jsx':
    case 'ts':
    case 'tsx':
    case 'mjs':
    case 'cjs':
      return Lang.js;
    case 'json':
    case 'jsonc':
      return Lang.json;
    case 'sh':
    case 'bash':
    case 'zsh':
    case 'fish':
    case 'ksh':
      return Lang.shell;
    case 'rs':
      return Lang.rust;
    case 'c':
    case 'h':
    case 'cpp':
    case 'cc':
    case 'cxx':
    case 'hpp':
    case 'hh':
      return Lang.c;
    case 'yml':
    case 'yaml':
    case 'toml':
    case 'ini':
    case 'conf':
      return Lang.yaml;
    case 'md':
    case 'markdown':
    case 'mdown':
      return Lang.markdown;
    case 'xml':
    case 'html':
    case 'htm':
    case 'svg':
    case 'xsl':
    case 'gradle':
      return Lang.xml;
    default:
      switch (base) {
        case 'makefile':
        case 'dockerfile':
        case 'jenkinsfile':
          return Lang.shell;
      }
      return Lang.text;
  }
}

/// 语言显示名（编辑器标题栏用）。
String langLabel(Lang lang) {
  switch (lang) {
    case Lang.dart:
      return 'Dart';
    case Lang.kotlin:
      return 'Kotlin';
    case Lang.java:
      return 'Java';
    case Lang.python:
      return 'Python';
    case Lang.js:
      return 'JavaScript';
    case Lang.json:
      return 'JSON';
    case Lang.shell:
      return 'Shell';
    case Lang.rust:
      return 'Rust';
    case Lang.c:
      return 'C/C++';
    case Lang.yaml:
      return 'YAML';
    case Lang.markdown:
      return 'Markdown';
    case Lang.xml:
      return 'XML/HTML';
    case Lang.text:
      return '文本';
  }
}

class _Palette {
  const _Palette(
    this.comment,
    this.string,
    this.number,
    this.keyword,
    this.type,
    this.fn,
    this.attr,
    this.punct,
  );

  final Color comment;
  final Color string;
  final Color number;
  final Color keyword;
  final Color type;
  final Color fn;
  final Color attr;
  final Color punct;

  static const dark = _Palette(
    Color(0xFF6A7A8A),
    Color(0xFF98C379),
    Color(0xFFD19A66),
    Color(0xFFC678DD),
    Color(0xFF61AFEF),
    Color(0xFFE5C07B),
    Color(0xFF56B6C2),
    Color(0xFFABB2BF),
  );

  static const light = _Palette(
    Color(0xFF6E7781),
    Color(0xFF22863A),
    Color(0xFF0550AE),
    Color(0xFFCF222E),
    Color(0xFF0969DA),
    Color(0xFF953800),
    Color(0xFF0550AE),
    Color(0xFF57606A),
  );
}

/// Markdown 一行解析结果（顶层类；Dart 不允许类内嵌类）。
class _MdLine {
  const _MdLine(this.span, this.inCode);
  final InlineSpan span;
  final bool inCode;
}

/// 纯 Dart 语法高亮器：把纯文本渲染成带样式的 [TextSpan]。
/// 零依赖（项目惯例：零依赖方案风险最低）。仅预览模式使用。
class SyntaxHighlighter {
  const SyntaxHighlighter(this.lang);

  final Lang lang;

  /// 渲染为 [TextSpan]。[base] 为基础样式；[dark] 为 false 时使用亮色调色板。
  TextSpan render(String text, {TextStyle? base, bool dark = true}) {
    final baseStyle = base ??
        const TextStyle(
          fontFamily: 'monospace',
          fontFamilyFallback: ['RobotoMono', 'Droid Sans Mono', 'sans-serif-mono'],
          fontSize: 13,
          height: 1.5,
          letterSpacing: 0.2,
        );
    final p = dark ? _Palette.dark : _Palette.light;
    switch (lang) {
      case Lang.markdown:
        return _markdown(text, baseStyle, p);
      case Lang.json:
        return _json(text, baseStyle, p);
      case Lang.xml:
        return _xml(text, baseStyle, p);
      default:
        return _code(text, baseStyle, p);
    }
  }

  static final Map<Lang, RegExp> _codeReCache = {};

  /// 组合正则：group 1=注释、2=字符串、3=数字、4=标识符。结果按语言缓存。
  static RegExp _codeRegex(Lang lang) => _codeReCache.putIfAbsent(lang, () {
    final String comment;
    if (lang == Lang.python || lang == Lang.shell || lang == Lang.yaml) {
      comment = r'#[^\n]*';
    } else if (lang == Lang.c) {
      comment = r'^\s*#\s*[\w.]+[^\n]*|//[^\n]*|/\*[\s\S]*?\*/';
    } else {
      comment = r'//[^\n]*|/\*[\s\S]*?\*/';
    }
    return RegExp(
      '($comment)'
      "|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)"
      r'|(\b\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b0x[0-9a-fA-F]+\b)'
      r'|([A-Za-z_][A-Za-z0-9_]*)',
      multiLine: true,
    );
  });

  TextSpan _code(String text, TextStyle base, _Palette p) {
    final re = _codeRegex(lang);
    final spans = <InlineSpan>[];
    var last = 0;
    for (final m in re.allMatches(text)) {
      if (m.start > last) {
        spans.add(TextSpan(text: text.substring(last, m.start), style: base));
      }
      final style = _codeStyle(m, text, base, p);
      if (style == null) continue; // 普通标识符并入相邻纯文本
      spans.add(TextSpan(text: text.substring(m.start, m.end), style: style));
      last = m.end;
    }
    if (last < text.length) {
      spans.add(TextSpan(text: text.substring(last), style: base));
    }
    return TextSpan(children: spans);
  }

  TextStyle? _codeStyle(Match m, String text, TextStyle base, _Palette p) {
    if (m.group(1) != null) return base.copyWith(color: p.comment);
    if (m.group(2) != null) return base.copyWith(color: p.string);
    if (m.group(3) != null) return base.copyWith(color: p.number);
    if (m.group(4) == null) return null;
    final s = text.substring(m.start, m.end);
    final kw = _keywords[lang];
    if (kw != null && kw.contains(s)) {
      return base.copyWith(color: p.keyword, fontWeight: FontWeight.bold);
    }
    final bi = _builtins[lang];
    if (bi != null && bi.contains(s)) return base.copyWith(color: p.type);
    if (text.substring(m.end).startsWith('(')) return base.copyWith(color: p.fn);
    final c = s.codeUnitAt(0);
    if (c >= 65 && c <= 90) return base.copyWith(color: p.type); // 大写开头视为类型
    return null;
  }

  // group 1=字符串、2=数字、3=关键字。
  static final _jsonRe = RegExp(
    r'("(?:\\.|[^"\\])*")'
    r'|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)'
    r'|(true|false|null)',
  );

  TextSpan _json(String text, TextStyle base, _Palette p) {
    final spans = <InlineSpan>[];
    var last = 0;
    for (final m in _jsonRe.allMatches(text)) {
      if (m.start > last) {
        spans.add(TextSpan(text: text.substring(last, m.start), style: base));
      }
      final s = text.substring(m.start, m.end);
      TextStyle? style;
      if (m.group(1) != null) {
        // 后跟 ':' 的是 key，用类型色；否则是字符串值。
        style = text.substring(m.end).trimLeft().startsWith(':')
            ? base.copyWith(color: p.type)
            : base.copyWith(color: p.string);
      } else if (m.group(2) != null) {
        style = base.copyWith(color: p.number);
      } else {
        style = base.copyWith(color: p.keyword, fontWeight: FontWeight.bold);
      }
      spans.add(TextSpan(text: s, style: style));
      last = m.end;
    }
    if (last < text.length) {
      spans.add(TextSpan(text: text.substring(last), style: base));
    }
    return TextSpan(children: spans);
  }

  // group 1=注释、2=字符串、3=标点、4=名称。
  static final _xmlRe = RegExp(
    r'(<!--[\s\S]*?-->)'
    "|(\"[^\"\\n]*\"|'[^'\\n]*')"
    r'|(<|>|/>)'
    r'|([A-Za-z_][\w:.-]*)',
  );

  TextSpan _xml(String text, TextStyle base, _Palette p) {
    final spans = <InlineSpan>[];
    var last = 0;
    var expectName = false; // 刚经过 '<' 或 '</'，下一个标识符是标签名
    for (final m in _xmlRe.allMatches(text)) {
      if (m.start > last) {
        spans.add(TextSpan(text: text.substring(last, m.start), style: base));
      }
      final s = text.substring(m.start, m.end);
      TextStyle style;
      if (m.group(1) != null) {
        style = base.copyWith(color: p.comment);
      } else if (m.group(2) != null) {
        style = base.copyWith(color: p.string);
      } else if (m.group(3) != null) {
        style = base.copyWith(color: p.punct, fontWeight: FontWeight.bold);
        if (s == '<' || s == '</') expectName = true;
      } else if (expectName) {
        style = base.copyWith(color: p.type);
        expectName = false;
      } else {
        style = base.copyWith(color: p.attr);
      }
      spans.add(TextSpan(text: s, style: style));
      last = m.end;
    }
    if (last < text.length) {
      spans.add(TextSpan(text: text.substring(last), style: base));
    }
    return TextSpan(children: spans);
  }

  static final _mdHeading = RegExp(r'^(#{1,6})\s+(.*)$');
  static final _mdFence = RegExp(r'^\s*```');
  static final _mdList = RegExp(r'^(\s*)([-*+]|\d+\.)(\s+)(.*)$');
  static final _mdInline = RegExp(r'(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^\]\n]*\]\([^)\n]*\))');
  static final _mdLink = RegExp(r'^\[([^\]]*)\]\(([^)]*)\)$');

  TextSpan _markdown(String text, TextStyle base, _Palette p) {
    final spans = <InlineSpan>[];
    var inCode = false;
    final lines = text.split('\n');
    for (var i = 0; i < lines.length; i++) {
      if (i > 0) spans.add(TextSpan(text: '\n', style: base));
      final r = _mdLine(lines[i], base, p, inCode);
      spans.add(r.span);
      inCode = r.inCode;
    }
    return TextSpan(children: spans);
  }

  _MdLine _mdLine(String line, TextStyle base, _Palette p, bool inCode) {
    if (_mdFence.hasMatch(line)) {
      return _MdLine(
        TextSpan(text: line, style: base.copyWith(color: p.punct)),
        !inCode,
      );
    }
    if (inCode) {
      return _MdLine(TextSpan(text: line, style: base.copyWith(color: p.comment)), inCode);
    }
    final h = _mdHeading.firstMatch(line);
    if (h != null) {
      return _MdLine(
        TextSpan(children: [
          TextSpan(text: h[1]! + ' ', style: base.copyWith(color: p.punct)),
          TextSpan(
            text: h[2],
            style: base.copyWith(color: p.keyword, fontWeight: FontWeight.bold, fontSize: 1.25),
          ),
        ]),
        inCode,
      );
    }
    final li = _mdList.firstMatch(line);
    if (li != null) {
      return _MdLine(
        TextSpan(children: [
          TextSpan(text: li[1], style: base),
          TextSpan(text: li[2], style: base.copyWith(color: p.keyword, fontWeight: FontWeight.bold)),
          TextSpan(text: li[3], style: base),
          ..._mdInlineSpans(li[4] ?? '', base, p),
        ]),
        inCode,
      );
    }
    return _MdLine(TextSpan(children: _mdInlineSpans(line, base, p)), inCode);
  }

  List<InlineSpan> _mdInlineSpans(String s, TextStyle base, _Palette p) {
    if (_mdInline.firstMatch(s) == null) {
      return [TextSpan(text: s, style: base)];
    }
    final spans = <InlineSpan>[];
    var last = 0;
    for (final m in _mdInline.allMatches(s)) {
      if (m.start > last) {
        spans.add(TextSpan(text: s.substring(last, m.start), style: base));
      }
      final tok = m[0]!;
      if (tok.startsWith('**')) {
        spans.add(TextSpan(
          text: tok,
          style: base.copyWith(fontWeight: FontWeight.bold, color: p.keyword),
        ));
      } else if (tok.startsWith('`')) {
        spans.add(TextSpan(
          text: tok,
          style: base.copyWith(color: p.string, backgroundColor: p.string.withOpacity(0.12)),
        ));
      } else {
        final lm = _mdLink.firstMatch(tok);
        if (lm != null) {
          spans.add(TextSpan(children: [
            TextSpan(
              text: '[' + (lm[1] ?? '') + ']',
              style: base.copyWith(color: p.type, decoration: TextDecoration.underline),
            ),
            TextSpan(text: '(' + (lm[2] ?? '') + ')', style: base.copyWith(color: p.comment)),
          ]));
        } else {
          spans.add(TextSpan(text: tok, style: base));
        }
      }
      last = m.end;
    }
    if (last < s.length) {
      spans.add(TextSpan(text: s.substring(last), style: base));
    }
    return spans;
  }

  static final Map<Lang, Set<String>> _keywords = {
    Lang.dart: _set(
      'abstract as async await break case catch class const continue covariant default defer '
      'do dynamic else enum export extends external factory final finally for get if implements '
      'import in interface is late library mixin new null on required return rethrow set static '
      'super switch sync this throw true false try typedef var void while with yield',
    ),
    Lang.kotlin: _set(
      'abstract actual annotation as break by catch companion const constructor continue crossinline '
      'data do else enum external final finally for fun get if import in infix init inline '
      'inner interface internal is lateinit noinline null object open operator out override package '
      'private protected public reified return sealed set super suspend tailrec this throw true '
      'false try typealias typeof var val when where while',
    ),
    Lang.java: _set(
      'abstract assert boolean break byte case catch char class const continue default do double '
      'else enum extends final finally float for goto if implements import instanceof int '
      'interface long native new package private protected public return short static strictfp '
      'super switch synchronized this throw throws true false transient try void volatile while var record',
    ),
    Lang.python: _set(
      'and as assert async await break class continue def del elif else except finally for from '
      'global if import in is lambda nonlocal not or pass raise return try while with yield '
      'None True False match case',
    ),
    Lang.js: _set(
      'async await break case catch class const continue debugger default delete do else enum '
      'export extends false finally for function get if implements import in instanceof interface '
      'let new null of package private protected public return set static super switch this throw '
      'true try typeof var void while with yield',
    ),
    Lang.rust: _set(
      'as async await break const crate continue dyn else enum extern false fn for if impl in '
      'let loop match mod move mut pub ref return self Self static struct super trait true try '
      'type unsafe use where while abstract become box do if macro priv typeof yield',
    ),
    Lang.c: _set(
      'auto break case char const continue default do double else enum extern float for goto if '
      'int long register return short signed sizeof static struct switch typedef union unsigned '
      'void volatile while inline restrict _Bool _Complex true false',
    ),
    Lang.shell: _set(
      'if then else elif fi case esac for while until do done in function return local export '
      'readonly declare typeset set unset shift exit exec source alias trap then break continue '
      'select time coproc',
    ),
  };

  static final Map<Lang, Set<String>> _builtins = {
    Lang.dart: _set('String int double num bool List Map Set Future Stream Widget BuildContext void Object dynamic print'),
    Lang.python: _set('print len range int str float bool list dict tuple set type isinstance issubclass open file input abs max min sum sorted enumerate zip map filter any all repr id hash'),
    Lang.js: _set('console Math JSON Object Array String Number Boolean Promise Map Set Symbol document window fetch require exports'),
    Lang.rust: _set('String Vec HashMap Option Result Box Rc Arc Cell RefCell Some None Ok Err impl trait'),
    Lang.c: _set('printf fprintf sprintf scanf malloc calloc realloc free NULL size_t char int void'),
  };

  static Set<String> _set(String s) => s.split(' ').toSet();
}