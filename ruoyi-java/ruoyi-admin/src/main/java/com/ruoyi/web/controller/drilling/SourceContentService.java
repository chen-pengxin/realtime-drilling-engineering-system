package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Archived report content; approved design/actual station versions remain independent. */
@Service
public class SourceContentService {
  private final JdbcTemplate db;

  public SourceContentService(SourceRegistrySupport schema) { this.db = schema.jdbc; }

  public boolean exists(Long sourceId){return db.queryForObject("SELECT COUNT(*) FROM drilling_document_parse WHERE source_id=? AND parse_status IN ('PARSED','PARTIAL')",Integer.class,sourceId)>0;}

  @SuppressWarnings("unchecked")
  private static Map<String,Object> object(Object value) {
    return value instanceof Map<?,?> ? new LinkedHashMap<>((Map<String,Object>)value)
        : JSON.parseObject(Objects.toString(value,"{}"),Map.class);
  }

  static String digest(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  @Transactional
  public Long save(Long sourceId, Long referenceId, Map<String,Object> inspected) {
    if (!(inspected.get("document") instanceof Map<?,?>)) return null;
    var document=object(inspected.get("document"));
    if (!(document.get("blocks") instanceof List<?> blocks)) return null;
    var manifest=new LinkedHashMap<>(document); manifest.remove("blocks");
    String version=Objects.toString(document.get("parser_version"),"");
    if(version.isBlank())throw new IllegalArgumentException("文档解析版次缺失");
    String hash=Objects.toString(inspected.get("sha256"),Objects.toString(document.get("source_sha256"),""));
    String contentHash=Objects.toString(document.get("content_sha256"),"");
    if(!contentHash.matches("[0-9a-f]{64}"))contentHash=digest(JSON.toJSONString(document));
    db.update("INSERT IGNORE INTO drilling_document_parse(source_id,reference_id,parser_version,source_sha256,content_sha256,parse_status,manifest_json) VALUES(?,?,?,?,?,?,?)",
        sourceId,referenceId,version,hash,contentHash,document.get("status"),JSON.toJSONString(manifest));
    String scope=sourceId!=null?"source_id":"reference_id";
    Long owner=sourceId!=null?sourceId:referenceId;
    Long parseId=db.queryForObject("SELECT parse_id FROM drilling_document_parse WHERE "+scope+"=? AND parser_version=? AND content_sha256=?",Long.class,owner,version,contentHash);
    int order=0;
    for(Object value:blocks){
      var section=object(value);
      db.update("INSERT IGNORE INTO drilling_document_section(parse_id,locator,section_order,title,content_type,semantic_type,payload_json) VALUES(?,?,?,?,?,?,?)",
          parseId,section.get("locator"),order++,section.get("title"),section.get("type"),section.getOrDefault("semantic_type","TEXT"),JSON.toJSONString(section));
    }
    return parseId;
  }

  private Map<String,Object> parse(Long sourceId,Long referenceId,Long parseId) {
    String scope=sourceId!=null?"source_id":"reference_id";Long owner=sourceId!=null?sourceId:referenceId;
    var rows=parseId==null
        ?db.queryForList("SELECT * FROM drilling_document_parse WHERE "+scope+"=? ORDER BY parse_id DESC LIMIT 1",owner)
        :db.queryForList("SELECT * FROM drilling_document_parse WHERE "+scope+"=? AND parse_id=?",owner,parseId);
    if(rows.isEmpty())throw new IllegalArgumentException("该文件尚未生成文档内容，请重新解析");
    return rows.get(0);
  }

  public Map<String,Object> manifest(Long sourceId,Long referenceId,Long parseId) {
    var parsed=parse(sourceId,referenceId,parseId);
    Long id=((Number)parsed.get("parse_id")).longValue();
    var result=object(parsed.get("manifest_json"));
    result.put("parse_id",id); result.put("parsed_at",Objects.toString(parsed.get("create_time"),""));
    result.put("sections",db.queryForList("SELECT section_id,locator,section_order,title,content_type,semantic_type,"
        +"JSON_EXTRACT(payload_json,'$.row_count') AS row_count,JSON_EXTRACT(payload_json,'$.column_count') AS column_count,"
        +"JSON_EXTRACT(payload_json,'$.page') AS page_number,JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.sheet')) AS sheet_name "
        +"FROM drilling_document_section WHERE parse_id=? ORDER BY section_order",id));
    String scope=sourceId!=null?"source_id":"reference_id";Long owner=sourceId!=null?sourceId:referenceId;
    result.put("versions",db.queryForList("SELECT parse_id,parser_version,parse_status,create_time FROM drilling_document_parse WHERE "+scope+"=? ORDER BY parse_id DESC",owner));
    return result;
  }

  public Map<String,Object> section(Long sourceId,Long referenceId,Long parseId,Long sectionId,int page,int size,String keyword) {
    var parsed=parse(sourceId,referenceId,parseId);
    var rows=db.queryForList("SELECT payload_json FROM drilling_document_section WHERE parse_id=? AND section_id=?",parsed.get("parse_id"),sectionId);
    if(rows.isEmpty())throw new IllegalArgumentException("该章节不属于所选文件及解析版次");
    var content=object(rows.get(0).get("payload_json"));
    page=Math.max(1,page);size=Math.max(1,Math.min(200,size));
    if(content.get("rows") instanceof List<?> cells){
      int header=Math.min(cells.size(),((Number)content.getOrDefault("header_rows",0)).intValue());
      if(content.get("merges") instanceof List<?> merges){
        List<Object> enriched=new ArrayList<>();
        for(Object value:merges){var merge=object(value);int r=((Number)merge.get("row")).intValue(),c=((Number)merge.get("column")).intValue();
          if(r>=0&&r<cells.size()&&cells.get(r) instanceof List<?> row&&c>=0&&c<row.size())merge.put("value",row.get(c));enriched.add(merge);}
        content.put("merges",enriched);
      }
      List<Object> selected=new ArrayList<>();List<Integer> indexes=new ArrayList<>();
      String term=Objects.toString(keyword,"").toLowerCase(Locale.ROOT);
      for(int i=header;i<cells.size();i++)if(term.isBlank()||JSON.toJSONString(cells.get(i)).toLowerCase(Locale.ROOT).contains(term))indexes.add(i);
      int from=(int)Math.min(indexes.size(),(long)(page-1)*size),to=Math.min(indexes.size(),from+size);
      var provenance=content.get("source_rows") instanceof List<?> list?list:List.of();
      for(int i=from;i<to;i++){
        int index=indexes.get(i);var row=new LinkedHashMap<String,Object>();row.put("index",index);row.put("cells",cells.get(index));
        if(index-header<provenance.size())row.put("source",provenance.get(index-header));
        selected.add(row);
      }
      content.put("headers",cells.subList(0,header));content.remove("rows");content.remove("records");content.remove("source_rows");
      content.put("data",selected);content.put("total",indexes.size());content.put("pageNum",page);content.put("pageSize",size);
    } else if(content.get("text") instanceof String text){
      int chunk=20000;int from=(int)Math.min(text.length(),(long)(page-1)*chunk);
      content.put("text",text.substring(from,Math.min(text.length(),from+chunk)));
      content.put("total",text.length());content.put("pageSize",chunk);content.put("pageNum",page);
      content.remove("paragraphs");
    }
    return content;
  }

  public Map<String,Object> asset(Long sourceId,Long referenceId,Long parseId,Long sectionId) {
    var parsed=parse(sourceId,referenceId,parseId);
    var rows=db.queryForList("SELECT payload_json FROM drilling_document_section WHERE parse_id=? AND section_id=?",parsed.get("parse_id"),sectionId);
    if(rows.isEmpty())throw new IllegalArgumentException("插图不属于所选文件及解析版次");
    var asset=object(rows.get(0).get("payload_json")).get("asset");
    if(!(asset instanceof Map<?,?>))throw new IllegalArgumentException("所选章节没有原件图像");
    return object(asset);
  }
}
