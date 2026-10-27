package com.uav.lowaltitude.modules.device.domain;

import java.util.Map;

/** Configured coverage is independent of the camera's current pointing direction. */
public final class EoCoverage {
    private EoCoverage() { }
    public static boolean contains(Map<String,Object> profile,double[] point) {
        if(profile==null || point==null || point.length<2 || !Double.isFinite(point[0]) || !Double.isFinite(point[1])
                || Math.abs(point[0])>180 || Math.abs(point[1])>90 || !("WGS84".equals(profile.get("coordinate_system"))
                || "WGS-84".equals(profile.get("coordinate_system")))) return false;
        Double lon=number(profile,"longitude"),lat=number(profile,"latitude");
        if(lon==null || lat==null || Math.abs(lon)>180 || Math.abs(lat)>90) return false;
        double a=Math.toRadians(lat),b=Math.toRadians(point[1]),delta=Math.toRadians(point[0]-lon);
        double h=Math.pow(Math.sin((b-a)/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(delta/2),2);
        double distance=6371008.8*2*Math.asin(Math.sqrt(Math.min(1,h)));
        boolean circle="CIRCLE".equals(profile.get("coverage_kind"));
        if(!circle && !"SECTOR".equals(profile.get("coverage_kind"))) return false;
        Double range=number(profile,circle?"radius_m":"range_m");
        if(range==null || range<=0 || distance>range) return false;
        if(circle) return true;
        Double heading=number(profile,"azimuth_deg"),fov=number(profile,"fov_deg");
        if(heading==null || heading<0 || heading>=360 || fov==null || fov<=0 || fov>360) return false;
        if(distance<0.001) return true;
        double bearing=(Math.toDegrees(Math.atan2(Math.sin(delta)*Math.cos(b),
                Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(delta)))+360)%360;
        return Math.abs((bearing-heading+540)%360-180)<=fov/2;
    }
    private static Double number(Map<String,Object> p,String key) {
        return p.get(key) instanceof Number n && Double.isFinite(n.doubleValue())?n.doubleValue():null;
    }
}
