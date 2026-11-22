package com.drilling.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.*;

/** Read a copy of Word 97-2003 without running Office or altering the source. */
public final class LegacyWordReader {
  private static String text(String value) {
    return Range.stripFields(value).replace('\r','\n').replace('\u000b','\n')
        .replaceAll("[\\x00-\\x08\\x0c\\x0e-\\x1f]", "").strip();
  }

  private static Map<String,Object> table(Table table, int index, String caption) {
    // Word may use a different cell grid on every row. Union its physical
    // boundaries so that merged cells retain their source position.
    SortedSet<Integer> edges=new TreeSet<>();
    for(int r=0;r<table.numRows();r++) {
      TableRow row=table.getRow(r);
      for(int c=0;c<row.numCells();c++) {
        TableCell cell=row.getCell(c); edges.add(cell.getLeftEdge()); edges.add(cell.getLeftEdge()+cell.getWidth());
      }
    }
    List<Integer> grid=new ArrayList<>(edges);
    List<List<String>> rows=new ArrayList<>(); List<Map<String,Object>> merges=new ArrayList<>();
    Map<Integer,Map<String,Object>> vertical=new HashMap<>();
    List<List<Map<String,Object>>> sourceCells=new ArrayList<>();
    for(int r=0;r<table.numRows();r++) {
      TableRow row=table.getRow(r);
      List<String> values=new ArrayList<>(Collections.nCopies(Math.max(0,grid.size()-1),""));
      List<Map<String,Object>> positions=new ArrayList<>();
      for(int c=0;c<row.numCells();c++) {
        TableCell cell=row.getCell(c);
        int start=grid.indexOf(cell.getLeftEdge()), end=grid.indexOf(cell.getLeftEdge()+cell.getWidth());
        if(start<0||start>=values.size())continue;
        int span=Math.max(1,end-start); String content=text(cell.text());
        positions.add(Map.of("column",start,"start_offset",cell.getStartOffset(),"end_offset",cell.getEndOffset()));
        if(cell.isMerged()&&!cell.isFirstMerged()&&c>0) {
          Map<String,Object> prior=merges.isEmpty()?null:merges.get(merges.size()-1);
          if(prior!=null&&Objects.equals(prior.get("row"),r))prior.put("colspan",end-(int)prior.get("column"));
          // Do not discard unexpected nonempty source content.
          if(!content.isBlank())values.set(start,content);
          continue;
        }
        Map<String,Object> above=vertical.get(start);
        if(cell.isVerticallyMerged()&&!cell.isFirstVerticallyMerged()&&above!=null) {
          above.put("rowspan",r-(int)above.get("row")+1);
          if(!content.isBlank())values.set(start,content);
          continue;
        }
        values.set(start,content);vertical.remove(start);
        if(span>1||cell.isFirstMerged()||cell.isFirstVerticallyMerged()) {
          Map<String,Object> merge=new LinkedHashMap<>();merge.put("row",r);merge.put("column",start);merge.put("rowspan",1);merge.put("colspan",span);
          merges.add(merge);if(cell.isFirstVerticallyMerged())vertical.put(start,merge);
        }
      }
      rows.add(values);sourceCells.add(positions);
    }
    Map<String,Object> result=new LinkedHashMap<>();result.put("locator","legacy:table:"+index);
    result.put("title",caption.isBlank()?"表"+index:caption);result.put("type","TABLE");
    result.put("rows",rows);result.put("merges",merges);result.put("source_cells",sourceCells);
    result.put("start_offset",table.getStartOffset());result.put("end_offset",table.getEndOffset());return result;
  }

  private static void patch(HWPFDocument document,Path payload) throws Exception {
    var request=new ObjectMapper().readTree(payload.toFile());int wanted=request.path("table_index").asInt();
    Range range=document.getRange();Table selected=null;int index=0,end=-1;
    for(int i=0;i<range.numParagraphs();i++){var paragraph=range.getParagraph(i);if(paragraph.getStartOffset()<end)continue;if(paragraph.isInTable()){var current=range.getTable(paragraph);end=current.getEndOffset();if(++index==wanted){selected=current;break;}}}
    if(selected==null)throw new IllegalArgumentException("来源Word表格不存在");
    var values=request.path("rows");
    if(values.size()>selected.numRows()){
      if(values.size()>100000)throw new IllegalArgumentException("Word导出行数过多");
      org.apache.poi.hwpf.usermodel.SourceTableRows.append(document,selected,values.size()-selected.numRows());
      int originalStart=selected.getStartOffset();range=document.getRange();
      for(int i=0;i<range.numParagraphs();i++){var paragraph=range.getParagraph(i);if(paragraph.getStartOffset()==originalStart){selected=range.getTable(paragraph);break;}}
      if(values.size()>selected.numRows())throw new IllegalArgumentException("Word新增数据行未能保留来源表结构");
    }
    SortedSet<Integer> edges=new TreeSet<>();for(int r=0;r<selected.numRows();r++)for(int c=0;c<selected.getRow(r).numCells();c++){var cell=selected.getRow(r).getCell(c);edges.add(cell.getLeftEdge());edges.add(cell.getLeftEdge()+cell.getWidth());}
    var grid=new ArrayList<>(edges);record Edit(int start,int end,String value){}var edits=new ArrayList<Edit>();
    for(int r=0;r<values.size();r++){var row=selected.getRow(r);for(int c=0;c<row.numCells();c++){var cell=row.getCell(c);int column=grid.indexOf(cell.getLeftEdge());if(column<0||column>=values.get(r).size())continue;
      String replacement=values.get(r).get(column).asText(),old=cell.text();int keep=old.length();while(keep>0&&(old.charAt(keep-1)=='\r'||old.charAt(keep-1)=='\u0007'))keep--;
      if(!text(old).equals(replacement))edits.add(new Edit(cell.getStartOffset(),cell.getStartOffset()+keep,replacement));
    }}
    edits.sort(Comparator.comparingInt(Edit::start).reversed());for(var edit:edits)new Range(edit.start(),edit.end(),document).replaceText(edit.value(),false);
    document.write(System.out);
  }

  public static void main(String[] args) throws Exception {
    if(args.length<2)throw new IllegalArgumentException("inspect|asset source.doc [picture-index]");
    try(InputStream input=Files.newInputStream(Path.of(args[1])); HWPFDocument document=new HWPFDocument(input)) {
      if(args[0].equals("asset")) {
        int index=Integer.parseInt(args[2]);var pictures=document.getPicturesTable().getAllPictures();
        if(index<0||index>=pictures.size())throw new IllegalArgumentException("原件插图不存在");
        System.out.write(pictures.get(index).getContent());return;
      }
      if(args[0].equals("patch")){patch(document,Path.of(args[2]));return;}
      Range range=document.getRange();List<Map<String,Object>> blocks=new ArrayList<>();int tables=0,paragraphs=0;
      int end=-1;String caption="";
      for(int i=0;i<range.numParagraphs();i++) {
        Paragraph paragraph=range.getParagraph(i);if(paragraph.getStartOffset()<end)continue;
        if(paragraph.isInTable()) {
          Table value=range.getTable(paragraph);blocks.add(table(value,++tables,caption));caption="";end=value.getEndOffset();
        } else {
          String value=text(paragraph.text());if(value.isBlank())continue;
          blocks.add(Map.of("locator","legacy:paragraph:"+(++paragraphs),"title","正文 · 第"+paragraphs+"段","type","TEXT","text",value,
              "start_offset",paragraph.getStartOffset(),"end_offset",paragraph.getEndOffset()));
          caption=value.matches("^\\s*[表图].*")&&value.length()<160?value:"";
        }
      }
      // Headers, footnotes and text boxes are independent source stories.
      Map<String,Range> stories=new LinkedHashMap<>();stories.put("页眉与页脚",document.getHeaderStoryRange());
      stories.put("脚注",document.getFootnoteRange());stories.put("尾注",document.getEndnoteRange());stories.put("文本框",document.getMainTextboxRange());
      for(var entry:stories.entrySet()) {
        String value=text(entry.getValue().text());if(!value.isBlank())blocks.add(Map.of("locator","legacy:story:"+entry.getKey(),"title",entry.getKey(),"type","TEXT","text",value));
      }
      var pictures=document.getPicturesTable().getAllPictures();
      for(int i=0;i<pictures.size();i++) {
        Picture picture=pictures.get(i);blocks.add(Map.of("locator","legacy:picture:"+i,"title","文档插图 "+(i+1),"type","FIGURE",
            "asset",Map.of("legacy_picture",i,"mime",picture.getMimeType())));
      }
      if(blocks.isEmpty())throw new IOException("Word文档没有可读正文、表格或插图");
      new ObjectMapper().writeValue(System.out,Map.of("blocks",blocks,"reader","Apache POI HWPF 5.5.1"));
    }
  }
}
