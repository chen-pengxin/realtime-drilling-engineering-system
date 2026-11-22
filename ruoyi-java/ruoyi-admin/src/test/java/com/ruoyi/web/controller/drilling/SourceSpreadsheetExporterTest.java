package com.ruoyi.web.controller.drilling;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
class SourceSpreadsheetExporterTest {
 @Test void writesOffsetsCurvatureAndNotesWithoutChangingOtherSheets() throws Exception {
  byte[] source;try(var w=new HSSFWorkbook();var out=new ByteArrayOutputStream()){
   var sheet=w.createSheet("单点数据");var header=sheet.createRow(0);
   String[] names={"MD","INC","AZI","N","E","DLS","备注"};for(int i=0;i<names.length;i++)header.createCell(i).setCellValue(names[i]);
   sheet.createRow(1).createCell(0).setCellValue(0);sheet.createRow(2).createCell(0).setCellValue(100);w.createSheet("封皮").createRow(0).createCell(0).setCellValue("原件封皮");w.write(out);source=out.toByteArray();
  }
  var extraction=new HashMap<String,Object>();extraction.put("locator","sheet:单点数据:header:1");extraction.put("mapping",Map.of("md",0,"inclination",1,"azimuth",2,"north",3,"east",4,"dogleg",5,"remark",6));extraction.put("rows",List.of(Map.of("source_row",2),Map.of("source_row",3)));
  var rows=List.<Map<String,Object>>of(Map.of("md",0.,"inclination",0.,"azimuth",0.,"north",4200000.,"east",19400000.,"dogleg",0.,"remark","井口"),Map.of("md",100.,"inclination",2.,"azimuth",90.,"north",4200001.,"east",19400002.,"dogleg",.6,"remark","修订"));
  byte[] output=SourceSpreadsheetExporter.patch(source,extraction,rows,Map.of("coordinates_are_offsets",true,"origin_north",4200000.,"origin_east",19400000.));
  try(var w=WorkbookFactory.create(new ByteArrayInputStream(output))){var r=w.getSheet("单点数据").getRow(2);assertEquals(1,r.getCell(3).getNumericCellValue());assertEquals(2,r.getCell(4).getNumericCellValue());assertEquals(.6,r.getCell(5).getNumericCellValue());assertEquals("修订",r.getCell(6).getStringCellValue());assertEquals("原件封皮",w.getSheet("封皮").getRow(0).getCell(0).getStringCellValue());}
 }
}
