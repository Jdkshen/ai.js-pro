/**
 * aijspro.exclude 匹配器。
 *
 * 支持三种写法（大小写敏感，按 gitignore 习惯）：
 * 1. 纯名称（无 /，无通配符）：匹配任意层级同名的目录或文件，例如 node_modules、.git；
 * 2. 名称通配：无 / 但含 * 或 ?，匹配任意层级的名称，例如 *.apk、*.tmp；
 * 3. 路径通配：含 / 的 glob，相对工程根匹配，** 匹配任意层目录，例如 assets/**、
 *    **!/node_modules/**（写成 ** + / + node_modules + /** 这种形式）、out/*.log。
 *
 * 以 / 结尾表示只匹配目录，例如 cache/。空字符串和 . 会被忽略。
 * 暂不支持 ! 取反；需要保留的目录请用更精确的正向规则。
 */
export interface ExcludeMatcher {
  ignoresDirectory(relativePath: string): boolean;
  ignoresFile(relativePath: string): boolean;
}

interface Rule {
  readonly directoryOnly: boolean;
  readonly namePattern?: RegExp;
  readonly pathPattern?: RegExp;
}

function hasGlobChars(value: string): boolean {
  return value.includes('*') || value.includes('?');
}

function escapeRegExp(value: string): string {
  const special = '\\^$.*+?()[]{}|';
  let out = '';
  for (const ch of value) {
    out += special.includes(ch) ? '\\' + ch : ch;
  }
  return out;
}

/** 把 glob 转成正则源码；nameOnly 时 * 可匹配任意字符（无 / 语义）。 */
function globToRegExpSource(glob: string, nameOnly: boolean): string {
  let source = '';
  for (let i = 0; i < glob.length; i++) {
    const ch = glob.charAt(i);
    if (ch === '*') {
      if (!nameOnly && glob.charAt(i + 1) === '*') {
        if (glob.charAt(i + 2) === '/') {
          source += '(?:[^/]+/)*';
          i += 2;
        } else {
          source += '.*';
          i += 1;
        }
      } else {
        source += nameOnly ? '.*' : '[^/]*';
        if (!nameOnly && glob.charAt(i + 1) === '*') {
          i += 1;
        }
      }
    } else if (ch === '?') {
      source += nameOnly ? '.' : '[^/]';
    } else if (ch === '/') {
      source += '/';
    } else {
      source += escapeRegExp(ch);
    }
  }
  return '^' + source + '$';
}

function normalizePattern(pattern: string): string {
  let value = pattern.trim().replace(/\\/g, '/');
  while (value.startsWith('./')) {
    value = value.slice(2);
  }
  while (value.startsWith('/')) {
    value = value.slice(1);
  }
  return value;
}

function compileRule(raw: string): Rule | null {
  let pattern = normalizePattern(raw);
  if (!pattern || pattern === '.') {
    return null;
  }
  const directoryOnly = pattern.endsWith('/');
  if (directoryOnly) {
    pattern = pattern.slice(0, -1);
  }
  if (!pattern) {
    return null;
  }
  if (!pattern.includes('/')) {
    const source = hasGlobChars(pattern)
      ? globToRegExpSource(pattern, true)
      : '^' + escapeRegExp(pattern) + '$';
    return { directoryOnly, namePattern: new RegExp(source) };
  }
  return { directoryOnly, pathPattern: new RegExp(globToRegExpSource(pattern, false)) };
}

function splitRelative(relativePath: string): string {
  return relativePath.replace(/\\/g, '/').replace(/^\.\//, '').replace(/\/+$/, '');
}

function nameOf(relativePath: string): string {
  const normalized = splitRelative(relativePath);
  const index = normalized.lastIndexOf('/');
  return index < 0 ? normalized : normalized.slice(index + 1);
}

export function createExcludeMatcher(patterns: readonly string[]): ExcludeMatcher {
  const rules: Rule[] = [];
  for (const pattern of patterns) {
    const rule = compileRule(pattern);
    if (rule) {
      rules.push(rule);
    }
  }
  if (rules.length === 0) {
    return { ignoresDirectory: () => false, ignoresFile: () => false };
  }
  const directoryMatches = (rule: Rule, relativePath: string): boolean => {
    if (rule.namePattern && !rule.namePattern.test(nameOf(relativePath))) {
      return false;
    }
    if (rule.pathPattern) {
      const withSlash = splitRelative(relativePath) + '/';
      if (!rule.pathPattern.test(withSlash)) {
        return false;
      }
    }
    return true;
  };
  const fileMatches = (rule: Rule, relativePath: string): boolean => {
    if (rule.directoryOnly) {
      return false;
    }
    if (rule.namePattern && !rule.namePattern.test(nameOf(relativePath))) {
      return false;
    }
    if (rule.pathPattern && !rule.pathPattern.test(splitRelative(relativePath))) {
      return false;
    }
    return true;
  };
  return {
    ignoresDirectory(relativePath: string): boolean {
      return rules.some((rule) => directoryMatches(rule, relativePath));
    },
    ignoresFile(relativePath: string): boolean {
      return rules.some((rule) => fileMatches(rule, relativePath));
    },
  };
}
