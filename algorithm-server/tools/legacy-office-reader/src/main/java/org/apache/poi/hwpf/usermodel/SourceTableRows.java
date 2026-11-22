package org.apache.poi.hwpf.usermodel;

import org.apache.poi.hwpf.HWPFDocument;

/** Append native Word rows using the source's cell grid and borders. */
public final class SourceTableRows {
 private SourceTableRows(){}
 public static void append(HWPFDocument document,Table source,int count){
  TableRow model=source.getRow(source.numRows()-1);short columns=(short)model.numCells();
  TableProperties properties=new TableProperties(columns);short[] edges=new short[columns+1];TableCellDescriptor[] cells=new TableCellDescriptor[columns];
  for(int i=0;i<columns;i++){var cell=model.getCell(i);edges[i]=(short)cell.getLeftEdge();edges[i+1]=(short)(cell.getLeftEdge()+cell.getWidth());cells[i]=cell.getDescriptor().copy();}
  properties.setRgdxaCenter(edges);properties.setRgdxaCenterPrint(edges.clone());properties.setRgtc(cells);
  properties.setJc((short)model.getRowJustification());properties.setDxaGapHalf(model.getGapHalf());properties.setDyaRowHeight(model.getRowHeight());properties.setFCantSplit(model.cantSplit());
  properties.setBrcTop(model.getTopBorder());properties.setBrcBottom(model.getBottomBorder());properties.setBrcLeft(model.getLeftBorder());properties.setBrcRight(model.getRightBorder());properties.setBrcHorizontal(model.getHorizontalBorder());properties.setBrcVertical(model.getVerticalBorder());
  // Insert before the last source row, which keeps the insertion inside
  // its section even when the table is the document's final content.
  int anchor=model.getStartOffset();Table added=new Range(anchor,anchor,document).insertTableBefore(columns,count);
  for(int i=0;i<added.numRows();i++){TableRow row=added.getRow(i);row.getParagraph(row.numParagraphs()-1).setTableRowEnd(properties.copy());}
  // HWPF's factory emits a separate empty CR paragraph before each cell
  // marker. Source Word tables use the cell marker itself as the paragraph
  // boundary; remove only the new factory CRs, preserving their table SPRMs.
  String inserted=added.text();int start=added.getStartOffset();
  for(int i=inserted.length()-1;i>=0;i--)if(inserted.charAt(i)=='\r')new Range(start+i,start+i+1,document).delete();
 }
}
