import 'package:flutter/material.dart';
import 'workspace_models.dart';

/// Shared file and project presentation; behavior stays in the browser policy.
class WorkspaceFileIcon extends StatelessWidget {
  final Map<String, dynamic> file;
  final double size;
  const WorkspaceFileIcon({super.key, required this.file, this.size = 42});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final dark = scheme.brightness == Brightness.dark;
    final directory = file['directory'] == true;
    final project = file['project'] == true;
    final extension = fileExtension(file);
    var icon = Icons.insert_drive_file_outlined;
    var tint = scheme.onSurfaceVariant;
    if (file['pendingSample'] == true) {
      icon = Icons.cloud_download_outlined;
      tint = scheme.primary;
    } else if (directory) {
      icon = project ? Icons.folder_special_outlined : Icons.folder_outlined;
      tint = project ? scheme.primary : dark ? const Color(0xff9bb9dc) : const Color(0xff52749d);
    } else if (const {'js', 'mjs', 'ts', 'tsx', 'jsx', 'java', 'kt', 'py', 'lua', 'vue'}.contains(extension)) {
      icon = Icons.code_rounded;
      tint = scheme.primary;
    } else if (extension == 'json') {
      icon = Icons.data_object_rounded;
      tint = scheme.primary;
    } else if (extension == 'xml' || extension == 'html' || extension == 'css') {
      icon = Icons.web_asset_outlined;
      tint = scheme.primary;
    } else if (const {'c', 'cc', 'cpp', 'h', 'hpp', 'sh'}.contains(extension)) {
      icon = Icons.terminal_rounded;
      tint = scheme.primary;
    } else if (extension == 'auto') {
      icon = Icons.touch_app_outlined;
      tint = scheme.tertiary;
    } else if (const {'png', 'jpg', 'jpeg', 'webp', 'gif', 'svg', 'bmp'}.contains(extension)) {
      icon = Icons.image_outlined;
      tint = scheme.tertiary;
    } else if (const {'mp3', 'flac', 'wav', 'ogg', 'aac', 'm4a'}.contains(extension)) {
      icon = Icons.music_note_rounded;
      tint = scheme.tertiary;
    } else if (const {'mp4', 'mkv', 'webm', 'avi', 'mov'}.contains(extension)) {
      icon = Icons.play_circle_outline_rounded;
      tint = scheme.tertiary;
    } else if (const {'zip', '7z', 'rar', 'gz', 'tar'}.contains(extension)) {
      icon = Icons.folder_zip_outlined;
    } else if (extension == 'apk') {
      icon = Icons.android_rounded;
      tint = scheme.primary;
    } else if (const {'so', 'dex', 'jar', 'class', 'bin'}.contains(extension)) {
      icon = Icons.memory_outlined;
    } else if (editable(file)) {
      icon = Icons.article_outlined;
    }
    return ExcludeSemantics(child: Container(
        width: size, height: size,
        decoration: BoxDecoration(
            color: Color.alphaBlend(tint.withValues(alpha: dark ? .12 : .07), scheme.surface),
            borderRadius: BorderRadius.circular(size * .29),
            border: Border.all(color: tint.withValues(alpha: .13))),
        child: Icon(icon, size: size * .53, color: tint)));
  }
}

class WorkspaceTag extends StatelessWidget {
  final String label;
  const WorkspaceTag(this.label, {super.key});
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
        constraints: const BoxConstraints(maxWidth: 200),
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
        decoration: BoxDecoration(
            color: scheme.primary.withValues(alpha: .08),
            borderRadius: BorderRadius.circular(6)),
        child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: scheme.primary)));
  }
}
