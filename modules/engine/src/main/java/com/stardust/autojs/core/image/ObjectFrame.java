package com.stardust.autojs.core.image;

import org.opencv.core.Point;

/**
 * PDF §2.18.4（第 166~167 页）：{@code $images.matchFeatures()} 返回的结果，表示一个四边形。
 *
 * <p>四个角 + 中心点；坐标已经按 {@code mScreenMetrics} 缩放（与 {@code images.matchTemplate}
 * 的 Match.point 一致，脚本可以直接拿去 click）。
 */
public class ObjectFrame {

    private final Point topLeft;
    private final Point topRight;
    private final Point bottomLeft;
    private final Point bottomRight;
    private final Point center;

    public ObjectFrame(Point topLeft, Point topRight, Point bottomLeft, Point bottomRight) {
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.bottomLeft = bottomLeft;
        this.bottomRight = bottomRight;
        this.center = new Point((topLeft.x + topRight.x + bottomLeft.x + bottomRight.x) / 4,
                (topLeft.y + topRight.y + bottomLeft.y + bottomRight.y) / 4);
    }

    public Point getTopLeft() {
        return topLeft;
    }

    public Point getTopRight() {
        return topRight;
    }

    public Point getBottomLeft() {
        return bottomLeft;
    }

    public Point getBottomRight() {
        return bottomRight;
    }

    /** PDF: {Point} 四边形中心点。 */
    public Point getCenter() {
        return center;
    }

    /** PDF: {number} 中心点 x 坐标。 */
    public double getCenterX() {
        return center.x;
    }

    /** PDF: {number} 中心点 y 坐标。 */
    public double getCenterY() {
        return center.y;
    }

    @Override
    public String toString() {
        return "ObjectFrame{topLeft=" + topLeft + ", topRight=" + topRight
                + ", bottomLeft=" + bottomLeft + ", bottomRight=" + bottomRight + '}';
    }
}
