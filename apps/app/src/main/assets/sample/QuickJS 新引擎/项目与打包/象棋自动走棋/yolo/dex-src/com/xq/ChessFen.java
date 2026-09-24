package com.xq; // 声明包名 com.xq

import java.util.Arrays; // 导入 Arrays 工具类

public final class ChessFen { // 定义不可继承的 ChessFen 类

    public static final int BOARD_CLS = 14; // 棋盘类别索引为 14
    public static final int NUM_PIECE_CLS = 14; // 棋子类别总数（0~13）
    public static final char[] FCH = {'n', 'b', 'a', 'k', 'r', 'c', 'p', 'R', 'N', 'A', 'K', 'B', 'C', 'P'}; // 类别索引 -> FEN 字符
    public static final String INITIAL_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"; // 象棋初始 FEN

    private static final int MIN_CELLS = 2; // 最少有效格子数
    private static final int MAX_PTS = 128; // 最多处理的棋子点数

    private final YoloDecode yd = new YoloDecode(); // 创建 YOLO 解码器实例

    public Result compute(float[] dets) { // 核心方法：从检测结果计算 FEN
        final Result res = new Result(); // 创建结果对象
        final int dn = (dets == null) ? 0 : dets.length / 6; // 检测框数量（每个 6 个 float）

        double bx = 0, by = 0, bx2 = 0, by2 = 0; // 棋盘框坐标
        boolean hasBoard = false; // 是否检测到棋盘
        final double[] px = new double[MAX_PTS]; // 棋子中心 x
        final double[] py = new double[MAX_PTS]; // 棋子中心 y
        final double[] ps = new double[MAX_PTS]; // 棋子置信度
        final int[] pc = new int[MAX_PTS]; // 棋子类别
        int pn = 0; // 有效棋子计数
        for (int i = 0; i < dn; i++) { // 遍历所有检测框
            final int o = i * 6; // 当前框在数组中的起始偏移
            final int c = (int) dets[o + 5]; // 类别
            if (c == BOARD_CLS) { // 如果是棋盘
                bx = dets[o]; // 左
                by = dets[o + 1]; // 上
                bx2 = dets[o + 2]; // 右
                by2 = dets[o + 3]; // 下
                hasBoard = true; // 标记有棋盘
                continue; // 继续下一个
            }
            if (c >= 0 && c < NUM_PIECE_CLS && pn < MAX_PTS) { // 如果是棋子且未超限
                px[pn] = (dets[o] + dets[o + 2]) / 2.0; // 计算中心 x
                py[pn] = (dets[o + 1] + dets[o + 3]) / 2.0; // 计算中心 y
                ps[pn] = dets[o + 4]; // 置信度
                pc[pn] = c; // 类别
                pn++; // 计数加一
            }
        }
        if (!hasBoard) { // 没有棋盘
            res.ok = false; // 失败
            res.reason = "no_board"; // 原因
            return res; // 返回
        }

        final double sx0 = (bx2 - bx) / 8.0; // 初始列间距（8 格）
        final double sy0 = (by2 - by) / 9.0; // 初始行间距（9 格）
        if (sx0 <= 0 || sy0 <= 0) { // 间距不合理
            res.ok = false; // 失败
            res.reason = "bad_board"; // 原因
            return res; // 返回
        }

        final double[] cellSc = new double[90]; // 每格最高置信度
        final double[] cellX = new double[90]; // 每格中心 x
        final double[] cellY = new double[90]; // 每格中心 y
        final int[] cellCls = new int[90]; // 每格类别
        Arrays.fill(cellSc, -1.0); // 初始化为 -1 表示空
        int cellN = 0; // 有效格子数
        for (int i = 0; i < pn; i++) { // 遍历棋子
            final int col = (int) Math.round((px[i] - bx) / sx0); // 估计列
            final int row = (int) Math.round((py[i] - by) / sy0); // 估计行
            if (col < 0 || col > 8 || row < 0 || row > 9) continue; // 越界跳过
            final int idx = row * 9 + col; // 格子索引
            if (cellSc[idx] < 0) cellN++; // 新格子
            if (ps[i] > cellSc[idx]) { // 置信度更高
                cellSc[idx] = ps[i]; // 更新置信度
                cellCls[idx] = pc[i]; // 更新类别
                cellX[idx] = px[i]; // 更新 x
                cellY[idx] = py[i]; // 更新 y
            }
        }
        if (cellN < MIN_CELLS) { // 有效格子太少
            res.ok = false; // 失败
            res.reason = "too_few"; // 原因
            res.n = cellN; // 记录数量
            return res; // 返回
        }

        final double[] xs = new double[90]; // 用于拟合的 x 数组
        final double[] ys = new double[90]; // 用于拟合的 y 数组
        int k = 0; // 有效点计数
        for (int r = 0; r < 10; r++) { // 遍历行
            for (int c = 0; c < 9; c++) { // 遍历列
                final int idx = r * 9 + c; // 索引
                if (cellSc[idx] >= 0) { // 有棋子
                    xs[k] = cellX[idx]; // 收集 x
                    ys[k] = cellY[idx]; // 收集 y
                    k++; // 计数
                }
            }
        }
        final double[] fx = fit(xs, bx, sx0, 8, k); // 拟合 x 方向网格
        final double[] fy = fit(ys, by, sy0, 9, k); // 拟合 y 方向网格
        final char[][] g = new char[10][9]; // 屏幕网格
        for (int r = 0; r < 10; r++) Arrays.fill(g[r], '.'); // 初始化为空
        for (int r = 0; r < 10; r++) { // 遍历行
            for (int c = 0; c < 9; c++) { // 遍历列
                final int idx = r * 9 + c; // 索引
                if (cellSc[idx] < 0) continue; // 空格跳过
                final int col = (int) Math.round((cellX[idx] - fx[0]) / fx[1]); // 重新计算列
                final int row = (int) Math.round((cellY[idx] - fy[0]) / fy[1]); // 重新计算行
                if (col < 0 || col > 8 || row < 0 || row > 9) continue; // 越界跳过
                g[row][col] = FCH[cellCls[idx]]; // 填入 FEN 字符
            }
        }
        int n = 0; // 棋子总数
        for (int r = 0; r < 10; r++) { // 遍历行
            for (int c = 0; c < 9; c++) { // 遍历列
                if (g[r][c] != '.') n++; // 统计非空格
            }
        }

        int rkRow = -1, bkRow = -1; // 红帅行、黑将行
        for (int r = 0; r < 10; r++) { // 遍历行
            for (int c = 0; c < 9; c++) { // 遍历列
                final char ch = g[r][c]; // 取字符
                if (ch == 'K') rkRow = r; // 红帅
                else if (ch == 'k') bkRow = r; // 黑将
            }
        }
        boolean flipped; // 是否翻转
        if (rkRow >= 0 && bkRow >= 0) { // 双方将帅都在
            flipped = rkRow < bkRow; // 红帅在上面则翻转
        } else { // 否则用子力估算
            int topR = 0, topB = 0, botR = 0, botB = 0; // 上下半盘子力
            for (int r = 0; r < 3; r++) { // 上半部分
                for (int c = 0; c < 9; c++) { // 遍历列
                    final char ch = g[r][c]; // 取字符
                    if (ch == '.') continue; // 空格跳过
                    if (Character.isUpperCase(ch)) topR++; else topB++; // 统计红黑
                }
            }
            for (int r = 7; r < 10; r++) { // 下半部分
                for (int c = 0; c < 9; c++) { // 遍历列
                    final char ch = g[r][c]; // 取字符
                    if (ch == '.') continue; // 空格跳过
                    if (Character.isUpperCase(ch)) botR++; else botB++; // 统计红黑
                }
            }
            flipped = (topR > topB && botB > botR); // 判断是否翻转
        }

        final StringBuilder sb = new StringBuilder(90); // 构建 FEN 字符串
        for (int r6 = 0; r6 < 10; r6++) { // 遍历输出行
            final int src = flipped ? (9 - r6) : r6; // 源行
            if (r6 > 0) sb.append('/'); // 行分隔符
            int e = 0; // 空格计数
            for (int c9 = 0; c9 < 9; c9++) { // 遍历列
                final char ch = g[src][flipped ? (8 - c9) : c9]; // 取字符
                if (ch == '.') { // 空格
                    e++; // 计数
                    continue; // 继续
                }
                if (e > 0) { // 有空格
                    sb.append((char) ('0' + e)); // 输出数字
                    e = 0; // 清零
                }
                sb.append(ch); // 输出棋子
            }
            if (e > 0) sb.append((char) ('0' + e)); // 行尾空格
        }

        res.ok = true; // 成功
        res.fen = sb.toString(); // FEN 字符串
        res.flipped = flipped; // 是否翻转
        res.topSide = flipped ? "R" : "B"; // 上方阵营
        res.n = n; // 棋子数
        res.gx0 = fx[0]; // 网格 x 偏移
        res.gy0 = fy[0]; // 网格 y 偏移
        res.gsx = fx[1]; // 网格 x 间距
        res.gsy = fy[1]; // 网格 y 间距
        res.bx = bx; // 棋盘左
        res.by = by; // 棋盘上
        res.bx2 = bx2; // 棋盘右
        res.by2 = by2; // 棋盘下
        res.gridScreen = new String[10]; // 屏幕网格字符串
        for (int r = 0; r < 10; r++) res.gridScreen[r] = new String(g[r]); // 每行转字符串
        return res; // 返回结果
    }

    public String fenOf(float[] dets) { // 便捷方法：直接返回 FEN
        return compute(dets).fen; // 调用 compute 并取 fen
    }

    public Result decodeAndCompute(float[] raw, double x0, double y0, double scale, double conf, double iou) { // 解码并计算
        return compute(yd.decode(raw, x0, y0, scale, conf, iou)); // 先解码再计算
    }

    public String decodeFen(float[] raw, double x0, double y0, double scale, double conf, double iou) { // 解码并返回 FEN
        return decodeAndCompute(raw, x0, y0, scale, conf, iou).fen; // 调用并取 fen
    }

    public static String pikafishFen(String fen, String side) { // 生成 pikafish 需要的 FEN
        final String f = (fen == null) ? "" : fen; // 处理空 FEN
        final String s = (side == null || side.length() == 0) ? "w" : side; // 处理空阵营
        return f + " " + s + " - - 0 1"; // 拼接
    }

    private static double[] fit(double[] xs, double o0, double s0, int nCells, int n) { // 线性拟合网格
        double o = o0; // 初始偏移
        double s = s0 > 1.0 ? s0 : 1.0; // 初始间距
        for (int it = 0; it < 15; it++) { // 最多迭代 15 次
            int m = 0; // 有效点数
            double sk = 0, sx = 0, skk = 0, skx = 0; // 统计量
            for (int i = 0; i < n; i++) { // 遍历点
                final double kk = Math.round((xs[i] - o) / s); // 估计格子索引
                if (kk < -0.5 || kk > nCells + 0.5) continue; // 超出范围跳过
                m++; // 计数
                sk += kk; // 累加 k
                sx += xs[i]; // 累加 x
                skk += kk * kk; // 累加 k^2
                skx += kk * xs[i]; // 累加 k*x
            }
            if (m < 3) break; // 点太少退出
            final double den = m * skk - sk * sk; // 分母
            if (Math.abs(den) < 1e-9) break; // 分母太小退出
            final double no = (sx * skk - sk * skx) / den; // 新偏移
            final double ns = (m * skx - sk * sx) / den; // 新间距
            if (Math.abs(ns - s) < 0.001 && Math.abs(no - o) < 0.001) { // 收敛
                o = no; // 更新偏移
                s = ns; // 更新间距
                break; // 退出
            }
            o = no; // 更新偏移
            s = ns; // 更新间距
        }
        return new double[] { o, s }; // 返回偏移和间距
    }

    public static final class Result { // 结果类

        public boolean ok; // 是否成功

        public String reason = ""; // 失败原因

        public String fen = ""; // FEN 字符串

        public boolean flipped; // 是否翻转
        
        public String topSide = "B"; // 上方阵营
        
        public int n; // 棋子数
        
        public String[] gridScreen = new String[0]; // 屏幕网格
        
        public double gx0, gy0, gsx, gsy; // 网格几何
        
        public double bx, by, bx2, by2; // 棋盘框

        public String pikafish(String side) { // 生成 pikafish FEN
            return ChessFen.pikafishFen(fen, side); // 调用静态方法
        }

        public String pikafish() { // 默认红方
            return ChessFen.pikafishFen(fen, "w"); // 调用静态方法
        }

        public String toString() { // 转字符串
            if (!ok) return "ChessFen[ok=false reason=" + reason + " n=" + n + "]"; // 失败信息
            return "ChessFen[ok=true n=" + n + " flipped=" + flipped + " fen=" + fen + "]"; // 成功信息
        }
    }
}