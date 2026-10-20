// Port of the native AutoCompletion dictionary model (AI.js Pro / Auto.js,
// Stardust 2018, MPL-2.0 with the upstream non-commercial condition; see
// third_party/aijs-editor/LICENSE.md). The index data itself stays in the
// original APK asset `indices/all.json` and is read through the bridge.
import 'dart:convert';
import 'dart:math' as math;

class CompletionProperty {
  final String key;
  final String? url;
  final String? summary;
  final bool global;
  const CompletionProperty(
      {required this.key, this.url, this.summary, this.global = false});
}

class CompletionModule {
  final String name;
  final String? url;
  final List<CompletionProperty> properties;
  const CompletionModule(
      {required this.name, this.url, required this.properties});
}

/// Parses `indices/all.json` (a JSON array of modules). Malformed entries are
/// skipped so one broken record cannot disable completion entirely.
List<CompletionModule> parseCompletionIndices(String source) {
  final decoded = json.decode(source);
  if (decoded is! List) return const [];
  final modules = <CompletionModule>[];
  for (final entry in decoded) {
    if (entry is! Map || entry['name'] is! String) continue;
    final name = entry['name'] as String;
    final rawProperties = entry['properties'];
    final properties = <CompletionProperty>[];
    if (rawProperties is List) {
      for (final property in rawProperties) {
        if (property is! Map || property['key'] is! String) continue;
        properties.add(CompletionProperty(
            key: property['key'] as String,
            url: property['url'] is String ? property['url'] as String : null,
            summary: property['summary'] is String
                ? property['summary'] as String
                : null,
            global: property['global'] == true));
      }
    }
    modules.add(CompletionModule(
        name: name,
        url: entry['url'] is String ? entry['url'] as String : null,
        properties: properties));
  }
  return modules;
}

class CodeCompletion {
  final String hint;
  final String? url;
  final int insertPos;
  const CodeCompletion(this.hint, this.url, this.insertPos);
  String get insertText =>
      insertPos <= 0 ? hint : hint.substring(math.min(insertPos, hint.length));
}

/// Same shape as the native STATEMENT pattern: optional `Module.` prefix and
/// an identifier fragment directly before the cursor.
final RegExp kStatementPattern =
    RegExp(r'([A-Za-z]+\.)?([a-zA-Z][a-zA-Z0-9_]*)?$');

String? statementModule(String lineBeforeCursor) {
  final match = kStatementPattern.firstMatch(lineBeforeCursor);
  final group = match?.group(1);
  return group == null ? null : group.substring(0, group.length - 1);
}

String? statementPrefill(String lineBeforeCursor) {
  return kStatementPattern.firstMatch(lineBeforeCursor)?.group(2);
}

/// The bundled LSP returns all scope names. Filter before applying the popup limit
/// so late names such as toast are not hidden behind unrelated entries.
List<CodeCompletion> filterCompletionHints(
    {required Iterable<String> hints,
    required String lineBeforeCursor,
    int max = 40}) {
  final prefill = statementPrefill(lineBeforeCursor);
  if (prefill == null && statementModule(lineBeforeCursor) == null) return const [];
  final results = <CodeCompletion>[];
  final seen = <String>{};
  for (final hint in hints) {
    if (hint.isEmpty || (prefill != null && !hint.startsWith(prefill))) continue;
    if (!seen.add(hint)) continue;
    if (results.length >= max) break;
    results.add(CodeCompletion(hint, null, prefill?.length ?? 0));
  }
  return results;
}

/// Builds completions the way the native editor does: module members when the
/// cursor follows `module.`, otherwise global names (module names plus global
/// properties) followed by words already present in the document.
List<CodeCompletion> findCompletions(
    {required List<CompletionModule> modules,
    required String lineBeforeCursor,
    Set<String>? documentWords,
    int max = 40}) {
  final module = statementModule(lineBeforeCursor);
  final prefill = statementPrefill(lineBeforeCursor);
  if (module == null && prefill == null) return const [];
  CompletionModule? owner;
  if (module != null) {
    for (final candidate in modules) {
      if (candidate.name == module) {
        owner = candidate;
        break;
      }
    }
    if (owner == null) return const [];
  }
  final completions = <CodeCompletion>[];
  final seen = <String>{};
  void add(String hint, String? url) {
    if (!seen.add(hint)) return;
    if (completions.length >= max) return;
    completions.add(CodeCompletion(hint, url,
        prefill == null ? 0 : math.min(prefill.length, hint.length)));
  }

  if (owner != null) {
    for (final property in owner.properties) {
      if (prefill == null || property.key.startsWith(prefill)) {
        add(property.key, property.url);
      }
    }
    return completions;
  }
  for (final candidate in modules) {
    if (candidate.name != 'globals' && candidate.name.startsWith(prefill!)) {
      add(candidate.name, candidate.url);
    }
    for (final property in candidate.properties) {
      if (property.global && property.key.startsWith(prefill!)) {
        add(property.key, property.url);
      }
    }
  }
  if (documentWords != null) {
    final ordered = documentWords.toList()..sort();
    for (final word in ordered) {
      if (word.length > prefill!.length && word.startsWith(prefill)) {
        add(word, null);
      }
    }
  }
  return completions;
}

/// Splits a document into words with the native AnyWordsCompletion rule
/// (`\W` separators), keeping identifier-like tokens of any script language.
Set<String> documentCompletionWords(String source) {
  if (source.length > 200000) return const {};
  final words = <String>{};
  for (final word in source.split(RegExp(r'[^A-Za-z0-9_$]+'))) {
    if (word.length > 1) words.add(word);
  }
  return words;
}
