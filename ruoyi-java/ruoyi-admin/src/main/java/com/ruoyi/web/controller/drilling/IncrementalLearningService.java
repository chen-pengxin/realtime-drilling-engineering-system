package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.config.RuoYiConfig;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Server-owned learning loop. Only committed, new measured stations train a model. */
@Service
@EnableScheduling
public class IncrementalLearningService {
    private static final Logger log = LoggerFactory.getLogger(IncrementalLearningService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TrainingTaskCallbackController models;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.web.controller.drilling.centers.CenterTaskSnapshots centerSnapshots;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${drilling.algorithm.url:http://localhost:8000}") private String algorithmUrl;

    public IncrementalLearningService(JdbcTemplate jdbc, PlatformTransactionManager manager, TrainingTaskCallbackController models) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.models = models;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("drilling/incremental-learning.sql"));
            return null;
        });
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("enabled", false); p.put("wellId", null); p.put("wellboreId", null); p.put("templateId", null);
        p.put("minimumNewSamples", 10); p.put("intervalSeconds", 300); p.put("windowSize", 256);
        p.put("epochs", 3); p.put("learningRate", 0.0001); p.put("batchSize", 16);
        p.put("validationRatio", 0.2); p.put("replayRatio", 0.25); p.put("device", "auto");
        p.put("autoActivate", true);
        return p;
    }

    public Map<String, Object> get(Long fieldId) {
        ensure(fieldId);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM drilling_incremental_learning WHERE field_id=?", fieldId);
        Map<String, Object> policy = defaults(); policy.putAll(object(row.get("policy_json")));
        Map<String, Object> state = object(row.get("state_json"));
        Object taskId = state.get("activeTaskId");
        List<Map<String, Object>> tasks = jdbc.queryForList("""
            SELECT training_task_id, external_task_id, status, hyperparams_json, metrics_json,
                   model_version_id, error_message, create_time, finish_time
              FROM training_task WHERE field_id=? AND create_by='incremental-learning'
             ORDER BY training_task_id DESC LIMIT 20
            """, fieldId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy", policy); result.put("state", state); result.put("history", tasks);
        result.put("activeTaskId", taskId); return result;
    }

    public Map<String, Object> save(Long fieldId, Map<String, Object> body) {
        Map<String, Object> policy = defaults(); policy.putAll(body);
        validate(policy);
        ensure(fieldId);
        transactions.executeWithoutResult(tx -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM drilling_incremental_learning WHERE field_id=? FOR UPDATE", fieldId);
            Map<String, Object> old = object(row.get("policy_json"));
            Map<String, Object> state = object(row.get("state_json"));
            if (Boolean.TRUE.equals(policy.get("enabled"))) {
                Map<String,Object> source = source(fieldId, policy);
                policy.put("wellId", source.get("well_id")); policy.put("wellboreId", source.get("wellbore_id"));
            }
            boolean newWell = !Objects.equals(String.valueOf(old.get("wellId")), String.valueOf(policy.get("wellId")))
                || !Objects.equals(String.valueOf(old.get("wellboreId")), String.valueOf(policy.get("wellboreId")));
            if (state.get("activeTaskId") != null && newWell) throw new IllegalArgumentException("请等待当前模型更新完成后再切换增量训练井");
            if (Boolean.TRUE.equals(policy.get("enabled"))) {
                model(fieldId);
                template(fieldId, policy.get("templateId"));
            }
            if (newWell || !state.containsKey("trainedThroughMd")) {
                state.put("trainedThroughMd", Boolean.TRUE.equals(policy.get("enabled")) ? maximumMd(fieldId, policy) : 0);
            }
            state.remove("error"); state.put("nextCheckAt", 0);
            jdbc.update("UPDATE drilling_incremental_learning SET policy_json=?, state_json=? WHERE field_id=?",
                JSON.toJSONString(policy), JSON.toJSONString(state), fieldId);
        });
        return get(fieldId);
    }

    private void ensure(Long id) {
        jdbc.update("INSERT IGNORE INTO drilling_incremental_learning(field_id, policy_json, state_json) VALUES (?, ?, '{}')", id, JSON.toJSONString(defaults()));
    }

    @Scheduled(fixedDelayString = "${drilling.prediction.learning-poll-ms:10000}", initialDelay = 15000)
    public void poll() {
        for (Map<String, Object> row : jdbc.queryForList("""
            SELECT l.field_id FROM drilling_incremental_learning l
              JOIN drilling_field p ON p.field_id=l.field_id WHERE p.status='0'
            """)) {
            Long id = ((Number) row.get("field_id")).longValue();
            try { transactions.executeWithoutResult(tx -> tick(id)); }
            catch (Exception ex) { log.warn("Incremental model update for fieldId {} failed: {}", id, ex.getMessage()); }
        }
    }

    private void tick(Long fieldId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM drilling_incremental_learning WHERE field_id=? FOR UPDATE", fieldId);
        Map<String, Object> p = defaults(); p.putAll(object(row.get("policy_json")));
        Map<String, Object> s = object(row.get("state_json"));
        try {
            if (s.get("activeTaskId") != null) {
                sync(fieldId, p, s);
            } else if (Boolean.TRUE.equals(p.get("enabled")) && System.currentTimeMillis() >= number(s, "nextCheckAt", 0)) {
                s.put("nextCheckAt", System.currentTimeMillis() + (long) number(p, "intervalSeconds", 300) * 1000);
                startIfReady(fieldId, p, s);
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            s.put("error", ex.getMessage());
            s.put("nextCheckAt", System.currentTimeMillis() + (long) number(p, "intervalSeconds", 300) * 1000);
        }
        jdbc.update("UPDATE drilling_incremental_learning SET state_json=? WHERE field_id=?", JSON.toJSONString(s), fieldId);
    }

    private void startIfReady(Long fieldId, Map<String, Object> p, Map<String, Object> s) throws Exception {
        Map<String,Object> source = source(fieldId, p);
        Object versionId = source.get("survey_version_id");
        if (versionId == null) { s.put("status", "WAITING_FOR_DATA"); return; }
        double cursor = number(s, "trainedThroughMd", 0);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drilling_survey_station WHERE survey_version_id=? AND md>?", Integer.class, versionId, cursor);
        s.put("newSamples", count); s.put("status", "WAITING_FOR_DATA");
        if (count == null || count < number(p, "minimumNewSamples", 10)) return;
        Map<String, Object> base = model(fieldId);
        Map<String, Object> script = template(fieldId, p.get("templateId"));
        // Window contains the latest real observations and a chronological replay tail.
        List<Map<String, Object>> stations = jdbc.queryForList("SELECT md, inclination, azimuth, north, east, tvd FROM drilling_survey_station WHERE survey_version_id=? ORDER BY md DESC LIMIT ?", versionId, ((Number) p.get("windowSize")).intValue());
        Collections.reverse(stations);
        double through = ((Number) stations.get(stations.size()-1).get("md")).doubleValue();
        StringBuilder csv = new StringBuilder("md,inclination,azimuth,north,east,tvd,is_new\n");
        for (Map<String, Object> station : stations) {
            for (String key : List.of("md", "inclination", "azimuth", "north", "east", "tvd")) csv.append(station.get(key)).append(',');
            csv.append(((Number)station.get("md")).doubleValue() > cursor ? 1 : 0).append('\n');
        }
        String relative = "drilling-files/" + fieldId + "/incremental-" + UUID.randomUUID() + ".csv";
        Path destination = Path.of(RuoYiConfig.getProfile(), relative);
        Files.createDirectories(destination.getParent());
        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(destination, bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.update("""
            INSERT INTO data_file(field_id,file_category,business_type,original_name,object_key,content_type,file_size,sha256,create_by)
            VALUES (?,'DATASET','INCREMENTAL_MEASURED_SURVEY','增量实测数据.csv',?,'text/csv',?,?,'incremental-learning')
            """, fieldId, relative, bytes.length, hash);
        Long fileId = jdbc.queryForObject("SELECT file_id FROM data_file WHERE object_key=?", Long.class, relative);
        jdbc.update("INSERT INTO training_dataset(field_id,file_id,runtime_path,dataset_name,schema_json,create_by) VALUES (?,?,?,'自动增量实测窗口','{}','incremental-learning')", fieldId,fileId,relative);
        Long datasetId = jdbc.queryForObject("SELECT dataset_id FROM training_dataset WHERE file_id=?", Long.class,fileId);
        Map<String,Object> params = new LinkedHashMap<>(p);
        params.put("training_mode", "INCREMENTAL"); params.put("parent_model_version_id",base.get("model_version_id"));
        params.put("survey_version_id", versionId); params.put("wellbore_id", source.get("wellbore_id")); params.put("from_md",cursor); params.put("to_md",through);
        params.put("learning_rate",p.get("learningRate")); params.put("batch_size",p.get("batchSize"));
        params.put("validation_ratio",p.get("validationRatio")); params.put("replay_ratio",p.get("replayRatio"));
        Map<String,Object> input = new LinkedHashMap<>(); input.put("field_id",fieldId);
        input.put("template",script.get("runtime_path")); input.put("dataset",relative);
        input.put("base_model_path", artifact(base.get("object_key"))); input.put("device",p.get("device")); input.put("hyperparameters",params);
        Map<String,Object> task = call("POST","/train/start",input);
        jdbc.update("""
            INSERT INTO training_task(field_id,dataset_id,template_id,device,hyperparams_json,status,external_task_id,log_stream_key,create_by,update_time)
            VALUES (?,?,?,?,?,?,?,?,'incremental-learning',CURRENT_TIMESTAMP)
            """,fieldId,datasetId,p.get("templateId"),p.get("device"),JSON.toJSONString(params),task.get("status"),task.get("task_id"),"drilling:task:"+task.get("task_id"));
        Long centerId=jdbc.queryForObject("SELECT training_task_id FROM training_task WHERE external_task_id=?",Long.class,task.get("task_id"));
        centerSnapshots.capture("TRAINING",centerId,fieldId,input);
        s.put("activeTaskId", task.get("task_id")); s.put("pendingThroughMd",through);
        s.put("parentModelVersionId",base.get("model_version_id")); s.put("status",task.get("status"));
        s.remove("error");
    }

    private void sync(Long fieldId, Map<String,Object> p, Map<String,Object> s) throws Exception {
        String taskId = String.valueOf(s.get("activeTaskId"));
        Map<String,Object> task = call("GET","/train/status/"+taskId,null);
        String status = String.valueOf(task.get("status"));
        s.put("status",status);
        jdbc.update("UPDATE training_task SET status=?,metrics_json=?,error_message=?,update_time=CURRENT_TIMESTAMP WHERE external_task_id=? AND field_id=?",status,JSON.toJSONString(task.get("metrics")),task.get("error"),taskId,fieldId);
        if ("SUCCEEDED".equals(status)) {
            if (task.get("model_artifact_uri") == null) {
                if (number(s,"artifactWaitSince",0)==0) s.put("artifactWaitSince",System.currentTimeMillis());
                if (System.currentTimeMillis()-number(s,"artifactWaitSince",0)>60000) status="FAILED";
                else return;
            } else {
                Long localId=jdbc.queryForObject("SELECT training_task_id FROM training_task WHERE external_task_id=? AND field_id=?",Long.class,taskId,fieldId);
                Map<String,Object> artifact = new LinkedHashMap<>();
                artifact.put("uri",task.get("model_artifact_uri")); artifact.put("sha256",task.get("model_artifact_sha256")); artifact.put("size_bytes",task.get("model_artifact_size_bytes"));
                String modelName = jdbc.queryForObject("SELECT model_name FROM model_version WHERE model_version_id=? AND field_id=?",String.class,s.get("parentModelVersionId"),fieldId);
                artifact.put("name",modelName+".zip");
                models.registerModelVersion(localId, fieldId, "incremental-learning",artifact,JSON.toJSONString(task.get("metrics")));
                Long modelId=jdbc.queryForObject("SELECT model_version_id FROM training_task WHERE training_task_id=?",Long.class,localId);
                s.put("trainedThroughMd",s.get("pendingThroughMd")); s.put("latestModelVersionId",modelId); s.put("newSamples",0);
                if (Boolean.TRUE.equals(p.get("autoActivate"))) activateIfUnchanged(fieldId,s.get("parentModelVersionId"),modelId);
            }
        }
        if (List.of("SUCCEEDED","FAILED","STOPPED","LOST").contains(status)) {
            s.remove("activeTaskId"); s.remove("artifactWaitSince");
            if (!"SUCCEEDED".equals(status)) s.put("error",Objects.toString(task.get("error"),"模型更新未完成，保留原模型与数据进度"));
            s.put("status",status); s.put("nextCheckAt",System.currentTimeMillis()+(long)number(p,"intervalSeconds",300)*1000);
            jdbc.update("UPDATE training_task SET status=?,finish_time=CURRENT_TIMESTAMP WHERE external_task_id=?",status,taskId);
        }
    }

    private void activateIfUnchanged(Long fieldId,Object previous,Long modelId) {
        jdbc.queryForList("SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE",fieldId);
        Map<String,Object> current=jdbc.queryForMap("SELECT * FROM drilling_field_config WHERE field_id=? AND status='0' AND effective_to IS NULL ORDER BY config_version DESC LIMIT 1",fieldId);
        Map<String,Object> defaults=DrillingAlgorithmDefaults.merged(current.get("algorithm_defaults_json"));
        @SuppressWarnings("unchecked") Map<String,Object> prediction=(Map<String,Object>)defaults.get("prediction");
        if (!Objects.equals(String.valueOf(prediction.get("modelVersionId")),String.valueOf(previous))) return;
        prediction.put("modelVersionId",modelId);
        Integer next=jdbc.queryForObject("SELECT MAX(config_version)+1 FROM drilling_field_config WHERE field_id=?",Integer.class,fieldId);
        jdbc.update("""
            INSERT INTO drilling_field_config(field_id,config_version,coordinate_system,north_reference,length_unit,angle_unit,tvd_positive,origin_north,origin_east,origin_tvd,magnetic_declination,meridian_convergence,algorithm_defaults_json,status,create_by)
            SELECT field_id,?,coordinate_system,north_reference,length_unit,angle_unit,tvd_positive,origin_north,origin_east,origin_tvd,magnetic_declination,meridian_convergence,?,'1','incremental-learning'
            FROM drilling_field_config WHERE field_id=? AND config_version=?
            """,next,JSON.toJSONString(defaults),fieldId,current.get("config_version"));
        jdbc.update("UPDATE drilling_field_config SET status='1',effective_to=CURRENT_TIMESTAMP WHERE field_id=? AND config_version=?",fieldId,current.get("config_version"));
        jdbc.update("UPDATE drilling_field_config SET status='0',effective_to=NULL WHERE field_id=? AND config_version=?",fieldId,next);
        jdbc.update("UPDATE drilling_field SET config_json=? WHERE field_id=?",JSON.toJSONString(defaults),fieldId);
    }

    private Map<String,Object> model(Long fieldId) {
        Map<String,Object> config=jdbc.queryForMap("SELECT algorithm_defaults_json FROM drilling_field_config WHERE field_id=? AND status='0' AND effective_to IS NULL ORDER BY config_version DESC LIMIT 1",fieldId);
        Map<String,Object> prediction=object(DrillingAlgorithmDefaults.merged(config.get("algorithm_defaults_json")).get("prediction"));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT mv.model_version_id,mv.model_name,f.object_key FROM model_version mv JOIN data_file f ON f.file_id=mv.file_id WHERE mv.field_id=? AND mv.model_version_id=? AND mv.status='READY' AND f.status='ACTIVE'",fieldId,prediction.get("modelVersionId"));
        if(rows.isEmpty())throw new IllegalArgumentException("请先在模型仓库启用一个现有模型");
        return rows.get(0);
    }
    private Map<String,Object> template(Long fieldId,Object id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT t.runtime_path,t.hyperparam_schema_json FROM training_template t JOIN data_file f ON f.file_id=t.file_id WHERE t.template_id=? AND t.field_id=? AND t.status='ACTIVE' AND f.status='ACTIVE' AND f.file_category='TEMPLATE' AND f.is_template='1' AND f.object_key=t.runtime_path",id,fieldId);
        if(rows.isEmpty())throw new IllegalArgumentException("请选择当前井场有效的增量更新适配器");
        if("INFERENCE".equals(object(rows.get(0).get("hyperparam_schema_json")).get("capability")))throw new IllegalArgumentException("推理适配器不能用于增量训练");
        return rows.get(0);
    }
    private Map<String,Object> source(Long fieldId,Map<String,Object> policy) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT b.well_id,b.wellbore_id,h.survey_version_id,t.quality_status
              FROM drilling_field_wellbore pb JOIN drilling_wellbore b ON b.wellbore_id=pb.wellbore_id
              JOIN drilling_well w ON w.well_id=b.well_id
              LEFT JOIN drilling_trajectory_head h ON h.wellbore_id=b.wellbore_id AND h.trajectory_kind='ACTUAL'
              LEFT JOIN drilling_trajectory_version t ON t.survey_version_id=h.survey_version_id
             WHERE pb.field_id=? AND w.status<>'ARCHIVED'
               AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en WHERE en.field_id=w.field_id
                 AND en.well_id=w.well_id AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF'
                 AND en.status='ACTIVE') AND
                   ((? IS NOT NULL AND b.wellbore_id=?) OR (? IS NULL AND b.well_id=?))
            """,fieldId,policy.get("wellboreId"),policy.get("wellboreId"),policy.get("wellboreId"),policy.get("wellId"));
        if(rows.size()!=1)throw new IllegalArgumentException("请选择当前井场唯一的实测来源井眼；多井眼不能仅按物理井自动选取");
        return rows.get(0);
    }
    private double maximumMd(Long fieldId,Map<String,Object> policy) {
        Object version=source(fieldId,policy).get("survey_version_id");
        if(version==null)return 0;
        Double value=jdbc.queryForObject("SELECT MAX(md) FROM drilling_survey_station WHERE survey_version_id=?",Double.class,version);
        return value==null?0:value;
    }
    private static void validate(Map<String,Object> p) {
        for(String key:List.of("enabled","autoActivate"))if(!(p.get(key) instanceof Boolean))throw new IllegalArgumentException(key+" must be boolean");
        range(p,"minimumNewSamples",1,10000,true); range(p,"intervalSeconds",10,86400,true); range(p,"windowSize",2,10000,true);
        range(p,"epochs",1,1000,true); range(p,"batchSize",1,4096,true); range(p,"learningRate",0.00000001,1,false);
        range(p,"validationRatio",0,0.5,false); range(p,"replayRatio",0,0.9,false);
        if(number(p,"minimumNewSamples",10)>number(p,"windowSize",256))throw new IllegalArgumentException("新增样本阈值不能大于窗口长度");
        if(!List.of("auto","cpu","cuda:0").contains(String.valueOf(p.get("device"))))throw new IllegalArgumentException("invalid device");
    }
    private static void range(Map<String,Object> p,String key,double min,double max,boolean integer) {
        double value=number(p,key,Double.NaN);
        if(!Double.isFinite(value)||value<min||value>max||(integer&&value!=Math.rint(value)))throw new IllegalArgumentException("invalid "+key);
        if(integer)p.put(key,(long)value); else p.put(key,value);
    }
    static Map<String,Object> object(Object raw) { if(raw==null)return new LinkedHashMap<>(); return new LinkedHashMap<>(JSON.parseObject(raw instanceof String text?text:JSON.toJSONString(raw))); }
    private static double number(Map<String,Object> p,String key,double fallback) { try{return p.get(key)==null?fallback:Double.parseDouble(String.valueOf(p.get(key)));}catch(NumberFormatException ex){return Double.NaN;} }
    static String artifact(Object raw) { String uri=String.valueOf(raw); return uri.substring(uri.lastIndexOf('/')+1); }
    public Map<String,Object> call(String method,String path,Map<String,Object> data) throws Exception {
        String base=algorithmUrl.replaceAll("/$",""); if(!base.endsWith("/api/algorithm"))base+="/api/algorithm";
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.toJSONString(data)));
        HttpResponse<String> response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()>=400)throw new IllegalArgumentException("模型服务: "+response.body());
        return object(response.body());
    }
}
