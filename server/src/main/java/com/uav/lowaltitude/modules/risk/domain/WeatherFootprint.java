package com.uav.lowaltitude.modules.risk.domain;

import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.List;

/** Explicit WGS84 forecast footprint. A station or district label never supplies a polygon. */
public final class WeatherFootprint {
    public static final int MAX_VERTICES = 1000;
    private WeatherFootprint() { }

    public static void validate(List<List<Double>> ring) {
        if (ring == null) return;
        if (ring.size() < 4 || ring.size() > MAX_VERTICES + 1) throw new IllegalArgumentException("气象范围须有3至" + MAX_VERTICES + "个顶点并闭合");
        for (var p : ring) if (p == null || p.size() != 2 || p.stream().anyMatch(v -> v == null || !Double.isFinite(v))
                || Math.abs(p.get(0)) > 180 || Math.abs(p.get(1)) > 85.051129)
            throw new IllegalArgumentException("气象范围须为有效WGS84地图坐标");
        int n = ring.size() - 1;
        if (!ring.get(0).equals(ring.get(n))) throw new IllegalArgumentException("气象范围必须闭合");
        double area = 0, ox = ring.get(0).get(0), oy = ring.get(0).get(1);
        for (int i = 0; i < n; i++) {
            var a = ring.get(i); var b = ring.get(i + 1);
            if (a.equals(b) || Math.abs(a.get(0) - b.get(0)) > 180) throw new IllegalArgumentException("气象范围存在无效边");
            var cNext=ring.get((i+2)%n);
            double ux=b.get(0)-a.get(0),uy=b.get(1)-a.get(1),vx=cNext.get(0)-b.get(0),vy=cNext.get(1)-b.get(1);
            if (ux*vy-uy*vx==0 && ux*vx+uy*vy<0) throw new IllegalArgumentException("气象范围相邻边不得重叠");
            area += (a.get(0)-ox)*(b.get(1)-oy) - (b.get(0)-ox)*(a.get(1)-oy);
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue;
                var c = ring.get(j); var d = ring.get(j + 1);
                if (Line2D.linesIntersect(a.get(0),a.get(1),b.get(0),b.get(1),c.get(0),c.get(1),d.get(0),d.get(1)))
                    throw new IllegalArgumentException("气象范围不得自交");
            }
        }
        if (Math.abs(area) < 1e-12) throw new IllegalArgumentException("气象范围不能为零面积");
    }

    /** Horizontal candidate overlap only; not a confirmation of flight impact or altitude coverage. */
    public static boolean intersects(List<List<Double>> ring, List<? extends List<? extends Number>> route, double corridorWidthM) {
        if (ring == null) return true;
        if (route == null || route.size() < 2) return false;
        validate(ring);
        double lat = ring.stream().mapToDouble(p -> p.get(1)).average().orElseThrow();
        double sx = 111320 * Math.cos(Math.toRadians(lat)), sy = 111320;
        Path2D polygon = new Path2D.Double();
        polygon.moveTo(ring.get(0).get(0)*sx, ring.get(0).get(1)*sy);
        for (var p : ring) polygon.lineTo(p.get(0)*sx, p.get(1)*sy);
        polygon.closePath();
        double pad = Double.isFinite(corridorWidthM) ? Math.max(0, corridorWidthM)/2 : 0;
        for (int i=1; i<route.size(); i++) {
            var a=route.get(i-1); var b=route.get(i);
            if (a.size()<2 || b.size()<2) return false;
            double ax=a.get(0).doubleValue()*sx, ay=a.get(1).doubleValue()*sy, bx=b.get(0).doubleValue()*sx, by=b.get(1).doubleValue()*sy;
            if (polygon.contains(ax,ay) || polygon.contains(bx,by)) return true;
            for (int j=1; j<ring.size(); j++) {
                var c=ring.get(j-1); var d=ring.get(j);
                double cx=c.get(0)*sx, cy=c.get(1)*sy, dx=d.get(0)*sx, dy=d.get(1)*sy;
                if (Line2D.linesIntersect(ax,ay,bx,by,cx,cy,dx,dy)
                    || Math.min(Math.min(Line2D.ptSegDist(ax,ay,bx,by,cx,cy),Line2D.ptSegDist(ax,ay,bx,by,dx,dy)),
                        Math.min(Line2D.ptSegDist(cx,cy,dx,dy,ax,ay),Line2D.ptSegDist(cx,cy,dx,dy,bx,by))) <= pad) return true;
            }
        }
        return false;
    }
}
