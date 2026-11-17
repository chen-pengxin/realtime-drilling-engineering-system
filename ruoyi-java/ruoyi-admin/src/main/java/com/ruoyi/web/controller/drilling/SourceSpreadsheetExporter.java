package com.ruoyi.web.controller.drilling;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;

/** Edits the selected binary Excel table while retaining the workbook container. */
public final class SourceSpreadsheetExporter {
  private SourceSpreadsheetExporter() {}

  @SuppressWarnings("unchecked")
  public static byte[] patch(byte[] original, Map<String,Object> extraction,
      List<Map<String,Object>> stations, Map<String,Object> reference) throws IOException {
    String locator=Objects.toString(extraction.get("locator"),"");
    if(!locator.startsWith("sheet:")||!locator.contains(":header:"))
      throw new IllegalArgumentException("Excel 来源表定位无效");
    String name=locator.substring(6,locator.lastIndexOf(":header:"));
    var sourceRows=(List<Map<String,Object>>)extraction.get("rows");
    var mapping=SourceRegistryController.obj(extraction.get("mapping"));
    if(sourceRows==null||sourceRows.isEmpty()||mapping.isEmpty())throw new IllegalArgumentException("来源行或字段映射缺失");
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(original));var output=new ByteArrayOutputStream()) {
      Sheet sheet=book.getSheet(name);
      if(sheet==null)throw new IllegalArgumentException("来源工作表不存在");
      List<Integer> positions=new ArrayList<>();
      for(var row:sourceRows)positions.add(((Number)row.get("source_row")).intValue()-1);
      if(!positions.equals(positions.stream().distinct().sorted().toList())||positions.get(0)<0||positions.get(positions.size()-1)>sheet.getLastRowNum())
        throw new IllegalArgumentException("来源行定位失效");
      int last=positions.get(positions.size()-1),extra=Math.max(0,stations.size()-positions.size());
      if((long)sheet.getLastRowNum()+extra>=book.getSpreadsheetVersion().getMaxRows())throw new IllegalArgumentException("超出原 Excel 格式行数限制，请导出标准轨道表");
      Row template=sheet.getRow(last);
      if(extra>0&&last<sheet.getLastRowNum())sheet.shiftRows(last+1,sheet.getLastRowNum(),extra,true,false);
      for(int i=1;i<=extra;i++) {
        Row row=sheet.createRow(last+i);
        if(template!=null){row.setHeight(template.getHeight());for(Cell c:template){Cell cell=row.createCell(c.getColumnIndex());cell.setCellStyle(c.getCellStyle());}}
        positions.add(last+i);
      }
      double scale="ft".equals(reference.get("length_unit"))?0.3048:1;
      double angular="rad".equals(reference.get("angle_unit"))?Math.PI/180:1;
      String north=Objects.toString(reference.get("north_reference"),"GRID_NORTH");
      double correction="GRID_NORTH".equals(north)?0:-number(reference.get("meridian_convergence"))+("MAGNETIC_NORTH".equals(north)?number(reference.get("magnetic_declination")):0);
      Set<String> values=Set.of("md","inclination","azimuth","north","east","tvd");
      for(int i=0;i<positions.size();i++) {
        Row row=sheet.getRow(positions.get(i));if(row==null)row=sheet.createRow(positions.get(i));
        for(var field:mapping.entrySet()) {
          if(!values.contains(field.getKey()))continue;
          int column=((Number)field.getValue()).intValue();Cell cell=row.getCell(column,Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
          if(i>=stations.size()){cell.setBlank();continue;}
          Object raw=stations.get(i).get(field.getKey());if(!(raw instanceof Number n)){cell.setBlank();continue;}
          double value=n.doubleValue();
          if(field.getKey().equals("azimuth"))value=((value-correction)%360+360)%360*angular;
          else if(field.getKey().equals("inclination"))value*=angular;
          else value/=scale;
          cell.setCellValue(value);
        }
      }
      book.setForceFormulaRecalculation(true);book.write(output);return output.toByteArray();
    }
  }
  private static double number(Object value){return value==null?0:Double.parseDouble(value.toString());}
}
