# 显式对照实验

本目录不被 main.js、悬浮窗或 src 的生产模块加载。

- vision/recognize-board.js：原 YOLO 棋子分类工具，原 tools 命令保留为兼容包装。
- vision/recognizer.js、sequence.js：旧分类与多帧投票。
- vision/vision-pipeline.js、pipeline-factory.js：旧 YOLO + PP-OCR 融合链。
- panel/ocr.js、difference.js：旧面板 OCR/差分辅助动作，保存依赖注入接口供研究；它们不是独立运行脚本，也未在生产面板装配。

单独运行旧工具或自检会加载相应实验模块。不能将其重新作为字形失败后的自动回退。若将来重新启用，必须先定义验收标准并更新架构守卫。
