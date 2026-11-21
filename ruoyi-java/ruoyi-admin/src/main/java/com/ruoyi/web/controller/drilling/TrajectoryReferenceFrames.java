package com.ruoyi.web.controller.drilling;

import java.util.Map;

/** Checks declared frames; coordinates alone never establish a shared CRS. */
final class TrajectoryReferenceFrames {
  private TrajectoryReferenceFrames() {}

  static String incompatibility(Map<String,Object> source,Map<String,Object> target){
    String src=text(source,"crs"),dst=text(target,"crs");
    if(src.isEmpty()!=dst.isEmpty())return "坐标基准不一致，缺少坐标转换信息";
    if(src.isEmpty()){
      for(String key:new String[]{"local_frame_id","coordinate_system","coordinate_system_text"}){
        String a=text(source,key),b=text(target,key);
        if(!a.isEmpty()&&!b.isEmpty()&&!a.equalsIgnoreCase(b))return "局部坐标基准不一致";
      }
    }else if(source.get("depth_datum_elevation_m")==null||target.get("depth_datum_elevation_m")==null){
      return "缺少深度基准高程";
    }
    if((source.get("depth_datum_elevation_m")==null||target.get("depth_datum_elevation_m")==null)
        &&!text(source,"depth_datum").isEmpty()&&!text(target,"depth_datum").isEmpty()
        &&!text(source,"depth_datum").equals(text(target,"depth_datum")))return "深度基准不一致，缺少高程转换信息";
    return null;
  }

  static boolean sameFrame(Map<String,Object> source,Map<String,Object> target){
    if(target==null||target.isEmpty())return true;
    if(incompatibility(source,target)!=null||!text(source,"crs").equalsIgnoreCase(text(target,"crs")))return false;
    return equalNumber(source.get("depth_datum_elevation_m"),target.get("depth_datum_elevation_m"))
        &&equalNumber(source.getOrDefault("meridian_convergence",0),target.getOrDefault("meridian_convergence",0));
  }

  private static String text(Map<String,Object> values,String key){
    Object value=values.get(key);return value==null?"":value.toString().trim();
  }
  private static boolean equalNumber(Object a,Object b){
    if(a==null||b==null)return a==b;
    try{return Math.abs(Double.parseDouble(a.toString())-Double.parseDouble(b.toString()))<1e-9;}
    catch(NumberFormatException error){return false;}
  }
}
