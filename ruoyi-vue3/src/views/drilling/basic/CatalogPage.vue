<template>
  <DrillingPageFrame title="工程资料目录" :show-field="false" :basic-context="true">
    <section class="library-page">
      <div class="library-layout">
        <aside class="library-tree-panel"><div class="tree-title"><span>工程目录</span><el-button text circle icon="Refresh" :loading="loading" aria-label="刷新目录" @click="load" /><el-button v-if="canCreateBlock" link type="primary" icon="Plus" @click="openBlockDialog">区块</el-button><el-input v-model="treeKeyword" size="small" clearable placeholder="搜索名称" prefix-icon="Search" /></div><el-tree ref="treeRef" :data="treeData" node-key="node_id" highlight-current default-expand-all :filter-node-method="filterNode" @node-click="selectNode"><template #default="{ data }"><span class="tree-node"><el-icon><component :is="iconFor(data.node_type)" /></el-icon><span>{{ nodeLabel(data) }}</span></span></template></el-tree><el-empty v-if="!treeData.length && !loading" description="暂无可访问的工程层级" /></aside>
        <main class="library-content"><template v-if="selectedNode">
          <div class="content-heading">
            <el-breadcrumb separator="/"><el-breadcrumb-item v-for="item in breadcrumbs" :key="item.node_id">{{ nodeLabel(item) }}</el-breadcrumb-item></el-breadcrumb>
            <div class="node-actions">
              <el-dropdown v-if="canAddNode && selectedNode.node_type==='WELLBORE'" @command="openNodeDialog">
                <el-button type="primary" plain icon="Plus">添加资料<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
                <template #dropdown><el-dropdown-menu><el-dropdown-item command="PLAN">新建设计方案</el-dropdown-item><el-dropdown-item command="SURVEY">新增实钻测斜资料</el-dropdown-item></el-dropdown-menu></template>
              </el-dropdown>
              <el-button v-else-if="canAddNode" type="primary" plain icon="Plus" @click="openNodeDialog()">{{ addNodeLabel }}</el-button>
              <el-button v-if="canEditNode" icon="Edit" @click="openEditDialog">编辑{{ nodeTypeLabels[selectedNode.node_type] }}</el-button>
            </div>
          </div>
          <el-tabs v-model="activeTab" class="library-tabs">
            <el-tab-pane v-if="childTabLabel" :label="childTabLabel" name="children" />
            <el-tab-pane v-if="selectedNode.node_type!=='BLOCK' && access.readFiles" label="资料文件" name="files" />
            <el-tab-pane v-if="selectedNode.node_type!=='BLOCK' && access.readData" label="轨迹数据版本" name="trajectory" />
            <el-tab-pane v-if="['PLAN','SURVEY'].includes(selectedNode.node_type) && access.readData" label="修订记录" name="revisions" />
          </el-tabs>
          <section v-if="activeTab === 'children'" class="content-section">
            <el-table :data="children" v-loading="contentLoading" border @row-click="selectChild">
              <el-table-column label="名称" min-width="240"><template #default="s"><el-button link type="primary" @click.stop="selectChild(s.row)">{{ nodeLabel(s.row) }}</el-button></template></el-table-column>
              <el-table-column label="类别" width="140"><template #default="s">{{ nodeTypeLabels[s.row.node_type] }}</template></el-table-column>
              <el-table-column v-if="selectedNode.node_type==='WELL'" label="井眼状态" width="120"><template #default="s">{{ statusLabels[s.row.engineering_status] || '未填写' }}</template></el-table-column>
              <el-table-column label="资料状态" width="100"><template #default="s">{{ nodeStateLabel(s.row.status) }}</template></el-table-column>
            </el-table>
          </section>
          <section v-else-if="activeTab === 'files'" class="content-section"><div class="section-toolbar"><el-form inline @submit.prevent="loadContents"><el-form-item label="名称"><el-input v-model="query.keyword" clearable placeholder="系统名称或原始文件名" /></el-form-item><el-form-item label="用途"><el-select v-model="query.role" clearable placeholder="全部" style="width:150px"><el-option v-for="(label,key) in documentRoles" :key="key" :value="key" :label="label" /></el-select></el-form-item><el-form-item label="轨迹类别"><el-select v-model="query.trajectoryKind" clearable placeholder="全部" style="width:145px"><el-option label="设计轨道" value="DESIGN" /><el-option label="实钻测斜" value="ACTUAL" /></el-select></el-form-item><el-button type="primary" icon="Search" @click="search">查询</el-button></el-form><div class="section-actions"><el-button v-if="canUpload" icon="Upload" @click="sourceUpload.show(selectedNode)">上传文件</el-button><el-button v-if="canAttach" type="primary" plain icon="Link" @click="openAttachDialog">选用已有文件</el-button><el-button v-if="access.readFiles && files.length" icon="Download" @click="exportOriginals">导出原件资料包</el-button></div></div><el-table :data="files" v-loading="contentLoading" border><el-table-column prop="display_name" label="系统名称" min-width="270" show-overflow-tooltip><template #default="s">{{ displayFileName(s.row) }}</template></el-table-column><el-table-column label="工程层级" min-width="260" show-overflow-tooltip><template #default="s">{{ s.row.engineering_path || '当前节点' }}</template></el-table-column><el-table-column prop="document_role" label="文件用途" width="130"><template #default="s">{{ documentRoles[s.row.document_role] || s.row.document_role }}</template></el-table-column><el-table-column label="轨迹类别" width="120"><template #default="s"><el-tag v-if="s.row.trajectory_kind" :type="s.row.trajectory_kind==='DESIGN'?'primary':'success'">{{ trajectoryKindLabel(s.row.trajectory_kind) }}</el-tag><span v-else>{{ ['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(s.row.document_role)?'未指定':'—' }}</span></template></el-table-column><el-table-column label="来源阶段" width="100"><template #default="s">{{ phaseLabel(s.row.phase) }}</template></el-table-column><el-table-column prop="business_revision" label="业务修订" width="120" /><el-table-column prop="representation_format" label="表示格式" width="100" /><el-table-column label="文件版次" width="110"><template #default="s">第 {{ s.row.edition_no }} 版</template></el-table-column><el-table-column label="文件状态" width="100"><template #default="s">{{ fileStateLabel(s.row.file_usage_state) }}</template></el-table-column><el-table-column prop="original_name" label="原始文件名" min-width="220" show-overflow-tooltip></el-table-column><el-table-column label="操作" width="390" fixed="right"><template #default="s"><el-button v-if="canRenameFile(s.row)" link type="primary" @click="editFileDisplayName(managementFieldId,s.row,loadContents)">修改系统名称</el-button><el-button v-if="canUploadFileVersion(s.row)" link type="primary" @click="sourceUpload.showVersion(s.row)">上传新版本</el-button><el-button v-if="s.row.source_id" link type="primary" @click="showSourceContent(s.row)">查看内容</el-button><el-button link type="primary" @click="showFileHistory(s.row)">版次</el-button><el-button link type="primary" @click="download(s.row)">原件</el-button></template></el-table-column></el-table><pagination v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="loadContents" /></section>
          <section v-else-if="activeTab === 'trajectory'" class="content-section"><div class="section-toolbar"><div v-if="selectedNode.wellbore_id"><el-button v-if="canUpload && selectedNode.node_type!=='SURVEY'" type="primary" plain icon="Upload" @click="catalogData.startImport('DESIGN')">导入设计版本</el-button><el-button v-if="canUpload && selectedNode.node_type!=='PLAN'" plain icon="Upload" @click="catalogData.startImport('ACTUAL')">导入实钻版本</el-button></div></div><el-table :data="trajectoryVersions" v-loading="contentLoading" border><el-table-column label="井眼" min-width="240"><template #default="s">{{ trajectoryLabel(s.row) }}</template></el-table-column><el-table-column label="用途" width="90"><template #default="s">{{ s.row.trajectory_kind === 'DESIGN' ? '设计' : '实测' }}</template></el-table-column><el-table-column label="数据版本" width="100"><template #default="s">v{{ s.row.version_no }}</template></el-table-column><el-table-column label="父版本" width="100"><template #default="s">{{ s.row.parent_version_no ? `v${s.row.parent_version_no}` : '—' }}</template></el-table-column><el-table-column prop="station_count" label="测点数" width="90" /><el-table-column label="最大 MD（m）" width="120"><template #default="s">{{ numberText(s.row.max_md) }}</template></el-table-column><el-table-column prop="source_format" label="来源格式" width="110" /><el-table-column label="来源文件" min-width="200" show-overflow-tooltip><template #default="s">{{ displayFileName(s.row) }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="s"><el-tag v-if="Number(s.row.is_current)" type="success">当前</el-tag><span v-else>历史</span></template></el-table-column><el-table-column label="操作" width="470" fixed="right"><template #default="s"><el-button link type="primary" @click="showTrajectoryVersion(s.row)">数据</el-button><el-button v-if="access.readFiles" link type="primary" @click="exportSource(s.row,'ORIGINAL')">来源原件</el-button><el-button v-if="access.readFiles && canPatchSource(s.row)" link type="primary" @click="exportSource(s.row,'PATCHED')">来源格式</el-button><el-button link type="primary" @click="exportTrajectory(s.row)">归算表</el-button><el-button v-if="access.readFiles" link type="primary" @click="exportCompletePackage(s.row)">资料包</el-button><el-button v-if="canWriteTrajectory(s.row) && !Number(s.row.is_current)" link type="warning" @click="activateTrajectory(s.row)">设为当前</el-button></template></el-table-column></el-table><el-empty v-if="!trajectoryVersions.length&&!contentLoading" description="当前层级没有轨迹数据版本" /></section>
          <section v-else-if="activeTab === 'revisions'" class="content-section"><div class="section-toolbar"><el-button v-if="access.writeData" type="primary" plain icon="Plus" @click="openRevisionDialog">新增修订</el-button></div><el-table :data="revisions" v-loading="contentLoading" border><el-table-column prop="business_revision" label="修订号" width="150" /><el-table-column prop="outcome_name" label="名称" min-width="220" show-overflow-tooltip /><el-table-column label="阶段" width="100"><template #default="s">{{ phaseLabel(s.row.phase) }}</template></el-table-column><el-table-column label="使用状态" width="110"><template #default="s">{{ fileStateLabel(s.row.usage_state) }}</template></el-table-column><el-table-column prop="dataset_count" label="数据版本数" width="90" /><el-table-column label="操作" width="240" fixed="right"><template #default="s"><el-button link type="primary" @click="showRevision(s.row)">查看</el-button><el-button v-if="access.writeData && canLinkData" link type="primary" @click="openDataLink(s.row)">选择数据版本</el-button><el-button v-if="access.writeData" link type="warning" :disabled="!!s.row.published_revision_id" @click="publishRevision(s.row)">发布</el-button></template></el-table-column></el-table></section>

        </template><el-empty v-else description="请从左侧选择区块、井场、井或井眼" /></main>
      </div>
    </section>
    <el-dialog v-model="nodeOpen" :title="`新增${nodeTypeLabels[nodeForm.nodeType]}`" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form label-width="100px" @submit.prevent="saveNode">
        <el-form-item v-for="item in nodeTarget?.path" :key="item.node_id" :label="`所属${nodeTypeLabels[item.node_type]}`"><span>{{ nodeLabel(item) }}</span></el-form-item>
        <el-form-item :label="nameLabel(nodeForm.nodeType)" required><el-input v-model="nodeForm.name" maxlength="255" /></el-form-item>
        <el-form-item :label="nodeForm.nodeType==='WELL'?'井号':'编码'"><el-input v-model="nodeForm.code" maxlength="128" placeholder="留空时使用名称" /></el-form-item>
        <el-form-item v-if="nodeForm.nodeType==='WELLBORE'" label="分支起始井眼"><WellboreSelect v-model="nodeForm.parentWellboreId" :rows="parentBoreRows" placeholder="主井眼留空" /></el-form-item>
        <el-form-item v-if="nodeForm.nodeType==='WELLBORE'" label="井眼状态"><el-select v-model="nodeForm.engineeringStatus"><el-option v-for="(label,key) in statusLabels" :key="key" :label="label" :value="key" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="nodeOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveNode">确 定</el-button></template>
    </el-dialog>

    <el-dialog v-model="editOpen" :title="`编辑${nodeTypeLabels[editTarget?.node_type] || ''}`" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form label-width="100px" @submit.prevent="saveEdit">
        <el-form-item label="所属位置"><span>{{ editLocation }}</span></el-form-item>
        <el-form-item :label="nameLabel(editTarget?.node_type)" required><el-input v-model="editForm.name" maxlength="255" /></el-form-item>
        <el-form-item v-if="editTarget?.node_type==='WELLBORE'" label="井眼状态"><el-select v-model="editForm.engineeringStatus"><el-option v-for="(label,key) in statusLabels" :key="key" :label="label" :value="key" /></el-select></el-form-item>
        <el-form-item label="资料状态"><el-select v-model="editForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="归档" value="ARCHIVED" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="editOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveEdit">保 存</el-button></template>
    </el-dialog>
    <el-dialog v-model="attachOpen" title="选用已有文件" width="min(1120px, calc(100vw - 32px))"><div class="attachment-location">所属位置：{{ attachmentTarget?.engineering_path }}</div><el-form inline class="source-search" @submit.prevent="searchSources"><el-form-item label="文件名称"><el-input v-model="sourceQuery.keyword" clearable /></el-form-item><el-form-item label="解析状态"><el-select v-model="sourceQuery.status" clearable style="width:150px"><el-option label="已解析" value="PARSED" /><el-option label="待复核" value="REVIEW_REQUIRED" /><el-option label="解析失败" value="FAILED" /></el-select></el-form-item><el-button type="primary" icon="Search" @click="searchSources">查询</el-button></el-form><el-table :data="sourceRows" v-loading="sourceLoading" border max-height="430"><el-table-column label="文件名称" min-width="260" show-overflow-tooltip><template #default="s">{{ displayFileName(s.row) }}</template></el-table-column><el-table-column prop="source_format" label="格式" width="90" /><el-table-column label="解析状态" width="110"><template #default="s">{{ parseStateLabel(s.row.parse_status) }}</template></el-table-column><el-table-column label="来源文件" min-width="180" show-overflow-tooltip><template #default="s">{{ displaySourcePath(s.row) }}</template></el-table-column><el-table-column label="用途" width="180"><template #default="s"><el-select v-model="sourceRole[s.row.source_id]" size="small"><el-option v-for="(label,key) in documentRoles" :key="key" :value="key" :label="label" /></el-select></template></el-table-column><el-table-column label="轨迹类别" width="150"><template #default="s"><el-select v-if="['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(sourceRole[s.row.source_id])" v-model="sourceKind[s.row.source_id]" clearable :disabled="['PLAN','SURVEY'].includes(attachmentTarget?.node_type)" placeholder="请选择"><el-option label="设计轨道" value="DESIGN" /><el-option label="实钻测斜" value="ACTUAL" /></el-select><span v-else>—</span></template></el-table-column><el-table-column label="操作" width="80"><template #default="s"><el-button link type="primary" :loading="attaching" @click="attach(s.row)">添加</el-button></template></el-table-column></el-table><pagination v-model:page="sourceQuery.pageNum" v-model:limit="sourceQuery.pageSize" :total="sourceTotal" @pagination="openSources" /></el-dialog>
    <el-dialog v-model="revisionOpen" title="新增修订" width="min(560px, calc(100vw - 32px))"><el-form label-width="min(100px, calc(100vw - 32px))"><el-form-item label="修订号"><el-input v-model="revisionForm.businessRevision" placeholder="例如 rev#7、rev0.11" /></el-form-item><el-form-item label="名称"><el-input v-model="revisionForm.outcomeName" /></el-form-item><el-form-item label="阶段"><el-select v-model="revisionForm.phase"><el-option v-if="revisionTarget?.node_type==='PLAN'" label="设计" value="PLAN" /><el-option v-if="revisionTarget?.node_type==='PLAN'" label="方案草稿" value="PROTOTYPE" /><el-option v-if="revisionTarget?.node_type==='SURVEY'" label="实钻" value="ACTUAL" /></el-select></el-form-item><el-form-item label="修订说明"><el-input v-model="revisionForm.note" type="textarea" /></el-form-item></el-form><template #footer><el-button @click="revisionOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveRevision">保存</el-button></template></el-dialog>
    <el-dialog v-model="catalogOpen" :close-on-click-modal="false" :title="`${catalogMode==='create'?'新增':'编辑'}${catalogType==='block'?'区块':'井场'}`" width="min(520px, calc(100vw - 32px))"><el-form label-width="min(90px, calc(100vw - 32px))"><el-form-item v-if="catalogType==='field'" label="所属区块"><span>{{ catalogBlockName }}</span></el-form-item><el-form-item label="编码"><el-input v-model="catalogForm.code" :disabled="catalogMode==='edit'" maxlength="64" /></el-form-item><el-form-item label="名称"><el-input v-model="catalogForm.name" maxlength="128" /></el-form-item><el-form-item label="说明"><el-input v-model="catalogForm.description" type="textarea" maxlength="500" /></el-form-item></el-form><template #footer><el-button @click="catalogOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveCatalog">保存</el-button></template></el-dialog>
    <el-dialog v-model="fileHistoryOpen" title="文件版次历史" width="min(820px, calc(100vw - 32px))"><el-table :data="fileHistory" border><el-table-column prop="edition_no" label="版次" width="90" /><el-table-column prop="representation_format" label="格式" width="100" /><el-table-column label="系统名称" min-width="240"><template #default="s">{{ displayFileName(s.row) }}</template></el-table-column><el-table-column prop="original_name" label="原始文件名" min-width="260" show-overflow-tooltip /><el-table-column prop="source_path" label="来源定位" min-width="260" show-overflow-tooltip /><el-table-column prop="create_time" label="登记时间" width="170" /><el-table-column label="操作" width="90" fixed="right"><template #default="s"><el-button link type="primary" @click="download(s.row)">原件</el-button></template></el-table-column></el-table></el-dialog>
    <el-dialog v-model="revisionDetailOpen" title="修订详情" width="min(620px, calc(100vw - 32px))"><el-descriptions v-if="revisionDetail" :column="1" border><el-descriptions-item label="修订号">{{ revisionDetail.business_revision }}</el-descriptions-item><el-descriptions-item label="名称">{{ revisionDetail.outcome_name }}</el-descriptions-item><el-descriptions-item label="阶段">{{ phaseLabel(revisionDetail.phase) }}</el-descriptions-item><el-descriptions-item label="轨迹数据版本">{{ revisionDetail.datasets?.length || 0 }} 个版本</el-descriptions-item><el-descriptions-item label="修订说明">{{ revisionDetail.revision_note || '—' }}</el-descriptions-item></el-descriptions></el-dialog>
    <el-dialog v-model="dataLinkOpen" title="关联轨迹数据版本" width="min(680px, calc(100vw - 32px))"><el-form label-width="min(100px, calc(100vw - 32px))"><el-form-item label="数据版本"><el-select v-model="selectedDataVersionId" filterable placeholder="请选择同一井眼、同一用途的数据版本" style="width:100%"><el-option v-for="v in dataLinkTarget?.versions || []" :key="v.survey_version_id" :value="v.survey_version_id" :label="`${v.wellbore_code || '井眼'} · ${v.trajectory_kind === 'DESIGN' ? '设计' : '实测'} v${v.version_no}${Number(v.is_current) ? '（当前）' : ''}`" /></el-select></el-form-item><el-form-item label="说明"><el-input v-model="selectedDataLabel" maxlength="255" /></el-form-item></el-form><template #footer><el-button @click="dataLinkOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveDataLink">关联</el-button></template></el-dialog>


    <CatalogTrajectoryData ref="catalogData" :field-id="managementFieldId" :node="selectedNode" :wellbores="trajectoryWellbores" :writable-wellbore-ids="writableWellboreIds" :can-read-files="access.readFiles" :engineering-path="breadcrumbs.filter(n=>['BLOCK','FIELD','WELL','WELLBORE'].includes(n.node_type)).map(nodeLabel).join(' / ')" :versions="trajectoryVersions" @saved="load" />
    <DocumentContent ref="sourcePreview" :field-id="managementFieldId" @parsed="loadContents" />
    <SourceOriginalPreview ref="sourceOriginalPreview" :field-id="managementFieldId" />
    <SourceLibrary ref="sourceReviewLibrary" :field-id="Number(reviewFieldId) || Number(fieldId)" @select="()=>{}" />
    <SourceUploadDialog ref="sourceUpload" :nodes="uploadNodes" :document-roles="documentRoles" @uploaded="sourcesUploaded" />
  </DrillingPageFrame>
</template>
<script setup>
import {computed,nextTick,onMounted,reactive,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import useUserStore from '@/store/modules/user'
import {catalogTree,libraryNode,createLibraryNode,updateLibraryNode,attachLibraryDocument,createLibraryRevision,activateLibraryRevision,attachRevisionData,libraryDocumentHistory,libraryRevision,exportOriginalPackage} from '@/api/engineeringLibrary'
import { fileDisplayName } from '@/utils/fileNames'
import { catalogPath, catalogCreationTarget, catalogTypeLabels, catalogAccess, catalogNodeWritable, catalogUploadError } from '@/utils/engineeringCatalog'
import { editFileDisplayName } from '../composables/fileDisplayName'
import {registry,downloadSourceOriginal,downloadBlob,exportReduction,exportTrajectorySource,exportTrajectoryPackage,activateTrajectoryVersion} from '@/api/drillingRegistry'
import {createDrillingBlock,updateDrillingBlock,createDrillingField,updateDrillingField} from '@/api/drillingField'
import DrillingPageFrame from '../layout/DrillingPageFrame.vue'
import WellboreSelect from '../layout/WellboreSelect.vue'
import CatalogTrajectoryData from './survey/CatalogTrajectoryData.vue'
import DocumentContent from './survey/DocumentContent.vue'
import SourceLibrary from './survey/SourceLibrary.vue'
import SourceOriginalPreview from './survey/SourceOriginalPreview.vue'
import SourceUploadDialog from './survey/SourceUploadDialog.vue'
const catalogData=ref(),sourcePreview=ref(),sourceOriginalPreview=ref(),sourceReviewLibrary=ref(),sourceUpload=ref(),reviewFieldId=ref(null),route=useRoute()
const nodeTarget=ref(null),editTarget=ref(null),catalogTarget=ref(null),attachmentTarget=ref(null),revisionTarget=ref(null),dataLinkTarget=ref(null),attaching=ref(false)
const statusLabels={UNPLANNED:'待规划',PLANNED:'已规划',DRILLING:'正在钻进',SUSPENDED:'暂停',COMPLETED:'已完成',ABANDONED:'废弃',UNKNOWN:'未填写'}

import {useDrillingContext} from '../composables/useDrillingContext'
const {fields,fieldId,loadFields}=useDrillingContext(),user=useUserStore(),loading=ref(false),contentLoading=ref(false),treeRef=ref(),treeKeyword=ref(''),activeTab=ref(['trajectory','versions'].includes(route.query.tab)?'trajectory':'files'),selectedNode=ref(null),nodes=ref([]),files=ref([]),revisions=ref([]),children=ref([]),trajectoryVersions=ref([]),fileHistory=ref([]),revisionDetail=ref(null),total=ref(0),query=reactive({pageNum:1,pageSize:20,keyword:'',role:'',trajectoryKind:'',sort:'date',order:'desc'}),nodeOpen=ref(false),editOpen=ref(false),catalogOpen=ref(false),attachOpen=ref(false),revisionOpen=ref(false),fileHistoryOpen=ref(false),revisionDetailOpen=ref(false),dataLinkOpen=ref(false),saving=ref(false),sourceLoading=ref(false),sourceRows=ref([]),sourceTotal=ref(0),sourceQuery=reactive({pageNum:1,pageSize:20,keyword:'',status:'',includeAccessible:true}),sourceRole=reactive({}),sourceKind=reactive({}),catalogMode=ref('create'),catalogType=ref('field'),catalogForm=reactive({code:'',name:'',description:''}),nodeForm=reactive({nodeType:'WELL',name:'',code:'',parentId:null,fieldId:null,parentWellboreId:null,entityKind:'WELL',engineeringStatus:'UNPLANNED'}),editForm=reactive({name:'',status:'ACTIVE',expectedVersion:1}),revisionForm=reactive({businessRevision:'',outcomeName:'',phase:'PLAN',note:''}),selectedRevisionId=ref(null),selectedDataVersionId=ref(null),selectedDataLabel=ref('')
const parentBoreRows=computed(()=>{
  return nodes.value.filter(n=>n.node_type==='WELLBORE'&&Number(n.well_id)===Number(nodeTarget.value?.path.find(item=>item.node_type==='WELL')?.well_id)).map(n=>{
    const lineage=catalogPath(n,nodes.value)
    const ancestor=type=>lineage.find(item=>(item.scope_type||item.node_type)===type)
    const block=ancestor('BLOCK'),field=ancestor('FIELD'),well=ancestor('WELL')
    return {...n,block_id:block?.block_id,block_name:block?.node_name,field_id:field?.field_id,field_name:field?.node_name,well_name:well?.well_name||well?.node_name,well_code:well?.well_code||well?.node_code,wellbore_name:n.wellbore_name||n.node_name,wellbore_code:n.wellbore_code||n.node_code}
  })
})
const nodeTypeLabels=catalogTypeLabels
const documentRoles={TRAJECTORY_DATA:'轨迹数据',TRAJECTORY_REPORT:'轨迹报告',WELL_SUMMARY:'井级汇总',ENGINEERING_REPORT:'工程报告',PRODUCTION_DATA:'生产数据',ARCHIVE:'压缩归档',REFERENCE:'参考资料'}
const childTypes=computed(()=>({BLOCK:['FIELD'],FIELD:['WELL'],WELL:['WELLBORE'],WELLBORE:['PLAN','SURVEY']}[selectedNode.value?.node_type]||[]))
const childTabLabel=computed(()=>({BLOCK:'井场列表',FIELD:'井列表',WELL:'井眼列表',WELLBORE:'设计与测斜资料'}[selectedNode.value?.node_type]||''))
const permissions=computed(()=>user.permissions||[]),hasPermission=key=>permissions.value.includes('*:*:*')||permissions.value.includes(key)
const managementFieldId=computed(()=>Number(selectedNode.value?.field_id)||null)
const canCreateBlock=computed(()=>hasPermission('drilling:block:add'))
const canEditNode=computed(()=>catalogNodeWritable(selectedNode.value)&&hasPermission({BLOCK:'drilling:block:edit',FIELD:'drilling:field:edit'}[selectedNode.value.node_type]||'drilling:well:edit'))
const canAddNode=computed(()=>childTypes.value.length>0&&catalogNodeWritable(selectedNode.value)&&hasPermission(selectedNode.value?.node_type==='BLOCK'?'drilling:field:add':'drilling:well:add'))
const addNodeLabel=computed(()=>({BLOCK:'新增井场',FIELD:'新增井',WELL:'新增井眼',WELLBORE:'添加资料'}[selectedNode.value?.node_type]||''))
const access=computed(()=>catalogAccess(selectedNode.value,permissions.value))
const canAttach=computed(()=>!!managementFieldId.value&&access.value.attach)
const canUpload=computed(()=>!!managementFieldId.value&&access.value.upload)
const writableWellboreIds=computed(()=>access.value.writeData?nodes.value.filter(n=>n.node_type==='WELLBORE'&&Number(n.field_id)===managementFieldId.value&&catalogNodeWritable(n)).map(n=>Number(n.wellbore_id)):[])
function canWriteTrajectory(row){return access.value.writeData&&writableWellboreIds.value.includes(Number(row.wellbore_id))}
function canUploadFileVersion(row){return catalogNodeWritable(selectedNode.value)&&catalogAccess(nodes.value.find(n=>Number(n.node_id)===Number(row.node_id)),permissions.value).upload}
function canRenameFile(row){return catalogNodeWritable(selectedNode.value)&&catalogAccess(nodes.value.find(n=>Number(n.node_id)===Number(row.node_id)),permissions.value).renameFile&&Number(row.source_field_id||managementFieldId.value)===managementFieldId.value}
const canLinkData=computed(()=>['PLAN','SURVEY'].includes(selectedNode.value?.node_type)&&trajectoryVersions.value.some(v=>v.trajectory_kind===(selectedNode.value.node_type==='PLAN'?'DESIGN':'ACTUAL')))
const editLocation=computed(()=>editTarget.value?nodePath(editTarget.value).slice(0,-1).map(nodeLabel).join(' / '):'')
const catalogBlockName=computed(()=>catalogTarget.value?.path.find(item=>item.node_type==='BLOCK')?.node_name||'')
const nameLabel=type=>({WELL:'井名',WELLBORE:'井眼名称',PLAN:'方案名称',SURVEY:'测斜资料名称',INBOX:'资料名称'}[type]||'名称')
const nodeStateLabel=value=>({ACTIVE:'启用',ARCHIVED:'已归档'}[value]||'—')
const parseStateLabel=value=>({PARSED:'已解析',REVIEW_REQUIRED:'待核对',FAILED:'解析失败',PENDING:'待解析',PARSING:'解析中'}[value]||'—')
function nodeLabel(node){
  const type=node?.node_type
  if(type==='WELL')return cleanSourceText(node.well_name||node.node_name)||`井 ${cleanSourceText(node.well_code||node.node_code)||'未命名'}`
  if(type==='WELLBORE'){
    const parent=nodes.value.find(item=>Number(item.node_id)===Number(node.parent_id)),bore=cleanSourceText(node.wellbore_name||node.node_name),boreCode=cleanSourceText(node.wellbore_code||node.node_code),well=cleanSourceText(parent?.well_name||parent?.node_name),wellCode=cleanSourceText(parent?.well_code||parent?.node_code)
    if(!bore||bore===well||bore===wellCode||boreCode===well||boreCode===wellCode)return '主井眼'
    return bore||`井眼 ${boreCode}`
  }
  if(type==='PLAN')return cleanSourceText(node.node_name)||'设计方案'
  if(type==='SURVEY')return cleanSourceText(node.node_name)||'实钻测斜资料'
  if(type==='INBOX'&&['项目级资料','工程资料'].includes(node.node_name))return '井场资料'
  return cleanSourceText(node?.node_name)||nodeTypeLabels[type]||'工程节点'
}
const hiddenSourceRoots=['采气二厂煤岩气完井资料+设计（11口）','采气二厂煤岩气完井资料+设计(11口)','Volve数据集','well_control_iscwsa'];
function cleanSourceText(value){let text=String(value||'');for(const root of hiddenSourceRoots)text=text.replaceAll(root,'');text=text.replace(/^[/\\]+/,'').replaceAll('\\','/');return text}
const displayFileName=fileDisplayName
function displaySourcePath(row){const value=cleanSourceText(row?.source_path||'');return value.split('/').pop()||'来源文件'}
function trajectoryLabel(row){return [row?.block_name,row?.field_name,row?.well_name,row?.wellbore_name].map(cleanSourceText).filter(Boolean).join(' / ')||humanNodeLabel(row?.wellbore_name||row?.wellbore_code)}
function humanNodeLabel(value){return cleanSourceText(value)||'井眼'}
const treeData=computed(()=>{const rows=nodes.value.map(n=>({...n,children:[]}));const map=new Map(rows.map(n=>[Number(n.node_id),n]));const roots=[];rows.forEach(n=>{if(n.parent_id&&Number(n.parent_id)!==Number(n.node_id)&&map.has(Number(n.parent_id)))map.get(Number(n.parent_id)).children.push(n);else roots.push(n)});return roots})
function nodePath(node){return catalogPath(node,nodes.value)}
const trajectoryWellbores=computed(()=>nodes.value.filter(n=>n.node_type==='WELLBORE').map(n=>{
  const path=nodePath(n),block=path.find(p=>p.node_type==='BLOCK'),field=path.find(p=>p.node_type==='FIELD'),well=path.find(p=>p.node_type==='WELL')
  return {...n,block_id:block?.block_id,block_name:block?nodeLabel(block):'',field_name:field?nodeLabel(field):'',well_name:well?nodeLabel(well):'',wellbore_name:nodeLabel(n)}
}))
const breadcrumbs=computed(()=>nodePath(selectedNode.value));const message=e=>e?.response?.data?.msg||e?.message||'操作失败'
watch(treeKeyword,v=>treeRef.value?.filter(v));onMounted(load)
function iconFor(type){return {BLOCK:'MapLocation',FIELD:'Location',INBOX:'Folder',WELL:'OfficeBuilding',WELLBORE:'Connection',PLAN:'Document',SURVEY:'Tickets'}[type]||'Document'}function filterNode(v,d){return !v||`${d.node_name} ${d.node_code||''}`.toLowerCase().includes(v.toLowerCase())}
async function load(){loading.value=true;try{if(!fields.value.length)await loadFields({basicOnly:true});nodes.value=await catalogTree()||[];const active=fields.value.find(p=>Number(p.field_id)===Number(fieldId.value));const current=nodes.value.find(n=>Number(n.node_id)===Number(selectedNode.value?.node_id))||nodes.value.find(n=>n.scope_type==='FIELD'&&Number(n.field_id)===Number(active?.field_id))||nodes.value.find(n=>n.scope_type==='FIELD')||nodes.value[0];if(current){selectedNode.value=current;if(['BLOCK','FIELD'].includes(current.scope_type))activeTab.value='children';else if(!['files','trajectory','revisions','children'].includes(activeTab.value)||activeTab.value==='revisions'&&!['PLAN','SURVEY'].includes(current.node_type))activeTab.value='files';await loadContents()}else{selectedNode.value=null;files.value=[];revisions.value=[];children.value=[];trajectoryVersions.value=[]}}catch(e){ElMessage.error(message(e))}finally{loading.value=false}}
let contentsRequest=0
async function loadContents(){const request=++contentsRequest;if(!selectedNode.value)return;if(activeTab.value==='files'&&!access.value.readFiles||['trajectory','revisions'].includes(activeTab.value)&&!access.value.readData)activeTab.value=childTabLabel.value?'children':access.value.readData?'trajectory':access.value.readFiles?'files':'';const scope=selectedNode.value.scope_type;contentLoading.value=false;if(scope==='BLOCK'){files.value=[];revisions.value=[];trajectoryVersions.value=[];children.value=nodes.value.filter(item=>Number(item.parent_id)===Number(selectedNode.value.node_id));total.value=0;return}const p=Number(selectedNode.value.field_id);if(!p)return;contentLoading.value=true;try{const r=await libraryNode(p,selectedNode.value.node_id,query);if(request!==contentsRequest)return;files.value=r.files||[];revisions.value=r.revisions||[];children.value=nodes.value.filter(item=>Number(item.parent_id)===Number(selectedNode.value.node_id)&&childTypes.value.includes(item.node_type));trajectoryVersions.value=r.trajectoryVersions||[];total.value=r.total||0}catch(e){if(request===contentsRequest)ElMessage.error(message(e))}finally{if(request===contentsRequest)contentLoading.value=false}}
function search(){query.pageNum=1;loadContents()}
async function selectNode(n){
  selectedNode.value=n;query.pageNum=1;query.keyword='';query.role='';query.trajectoryKind=''
  activeTab.value=['BLOCK','FIELD','WELL'].includes(n.node_type)?'children':'files'
  files.value=[];revisions.value=[];children.value=[];trajectoryVersions.value=[]
  await nextTick();treeRef.value?.setCurrentKey(n.node_id)
  await loadContents()
}
function selectChild(row){return selectNode(nodes.value.find(n=>Number(n.node_id)===Number(row.node_id))||row)}
function openNodeDialog(type){
  try{
    const target=catalogCreationTarget(selectedNode.value,nodes.value,typeof type==='string'?type:childTypes.value[0])
    if(target.nodeType==='FIELD'){
      catalogTarget.value=target;catalogMode.value='create';catalogType.value='field'
      Object.assign(catalogForm,{code:'',name:'',description:''});catalogOpen.value=true;return
    }
    nodeTarget.value=target
    Object.assign(nodeForm,{nodeType:target.nodeType,name:'',code:'',parentId:target.parentId,fieldId:target.fieldId,parentWellboreId:null,entityKind:'WELL',engineeringStatus:'UNPLANNED'})
    nodeOpen.value=true
  }catch(e){ElMessage.warning(message(e))}
}
function openBlockDialog(){catalogTarget.value=null;catalogMode.value='create';catalogType.value='block';Object.assign(catalogForm,{code:'',name:'',description:''});catalogOpen.value=true}
async function saveNode(){
  if(saving.value)return
  if(!nodeForm.name.trim())return ElMessage.warning(`请输入${nameLabel(nodeForm.nodeType)}`)
  const target=nodeTarget.value
  if(!target?.fieldId||!target.parentId)return ElMessage.warning('请重新选择所属工程目录')
  saving.value=true
  try{
    const payload={...nodeForm,name:nodeForm.name.trim(),fieldId:target.fieldId,parentId:target.parentId,nodeType:target.nodeType}
    const created=await createLibraryNode(target.fieldId,payload)
    nodeOpen.value=false;await load();const row=nodes.value.find(n=>Number(n.node_id)===Number(created.node_id));if(row)await selectNode(row)
    ElMessage.success(`${nodeTypeLabels[target.nodeType]}已新增`)
  }catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
function openEditDialog(){
  const n=selectedNode.value;if(!n)return
  if(['BLOCK','FIELD'].includes(n.node_type)){
    catalogTarget.value={node:{...n},path:nodePath(n).map(item=>({...item}))};catalogMode.value='edit';catalogType.value=n.node_type==='BLOCK'?'block':'field'
    Object.assign(catalogForm,{code:n.node_code||'',name:n.node_name||'',description:n.description||''});catalogOpen.value=true;return
  }
  editTarget.value={...n};Object.assign(editForm,{name:nodeLabel(n),status:n.status,expectedVersion:n.row_version,engineeringStatus:n.engineering_status||'UNKNOWN'});editOpen.value=true
}
async function saveEdit(){
  if(saving.value||!editTarget.value)return
  if(!editForm.name.trim())return ElMessage.warning('请输入名称')
  saving.value=true
  try{
    const target=editTarget.value,{engineeringStatus,...payload}=editForm
    if(target.node_type==='WELLBORE')payload.engineeringStatus=engineeringStatus
    await updateLibraryNode(target.field_id,target.node_id,payload);editOpen.value=false;await load();ElMessage.success('已保存')
  }catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
async function saveCatalog(){
  if(saving.value)return
  if(!catalogForm.name.trim()||!catalogForm.code.trim())return ElMessage.warning('请填写编码和名称')
  saving.value=true
  try{
    const target=catalogTarget.value,n=target?.node
    if(catalogMode.value==='create'){
      if(catalogType.value==='block')await createDrillingBlock({blockCode:catalogForm.code,blockName:catalogForm.name,description:catalogForm.description})
      else {if(!target?.blockId)throw new Error('请先选择所属区块');await createDrillingField({blockId:target.blockId,fieldCode:catalogForm.code,fieldName:catalogForm.name,description:catalogForm.description})}
    }else if(catalogType.value==='block')await updateDrillingBlock(n.block_id,{blockName:catalogForm.name,description:catalogForm.description})
    else await updateDrillingField(n.field_id,{fieldName:catalogForm.name,description:catalogForm.description})
    catalogOpen.value=false;await loadFields({basicOnly:true});await load();ElMessage.success('已保存')
  }catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
function openAttachDialog(){
  if(!canAttach.value)return
  attachmentTarget.value={...selectedNode.value,engineering_path:breadcrumbs.value.map(nodeLabel).join(' / ')}
  sourceQuery.pageNum=1;Object.keys(sourceRole).forEach(key=>delete sourceRole[key]);Object.keys(sourceKind).forEach(key=>delete sourceKind[key]);attachOpen.value=true
}
async function openSources(){
  const target=attachmentTarget.value;if(!target?.field_id)return
  sourceLoading.value=true
  try{
    const r=await registry(target.field_id,'/sources','get',undefined,sourceQuery,{silentError:true})
    sourceRows.value=r.rows||[];sourceTotal.value=r.total||0
    sourceRows.value.forEach(s=>{
      sourceRole[s.source_id]??=target.wellbore_id?(s.source_format==='pdf'?'TRAJECTORY_REPORT':'TRAJECTORY_DATA'):'REFERENCE'
      sourceKind[s.source_id]??=target.node_type==='PLAN'?'DESIGN':target.node_type==='SURVEY'?'ACTUAL':''
    })
  }catch(e){ElMessage.error(message(e))}finally{sourceLoading.value=false}
}
watch(attachOpen,v=>{if(v)openSources()})
async function searchSources(){sourceQuery.pageNum=1;await openSources()}
async function attach(s){
  if(attaching.value)return
  const target=attachmentTarget.value,role=sourceRole[s.source_id]||'REFERENCE',kind=['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(role)?sourceKind[s.source_id]||'':''
  if(!catalogAccess(target,permissions.value).attach)return
  const invalid=catalogUploadError(target,role,kind);if(invalid)return ElMessage.warning(invalid)
  attaching.value=true
  try{
    await attachLibraryDocument(Number(target.field_id),target.node_id,{sourceId:s.source_id,documentRole:role,trajectoryKind:kind})
    await load();await selectNode(nodes.value.find(n=>Number(n.node_id)===Number(target.node_id))||target)
    activeTab.value='files';attachOpen.value=false;ElMessage.success('文件已添加')
  }catch(e){ElMessage.error(message(e))}finally{attaching.value=false}
}
const uploadNodes=computed(()=>nodes.value.map(node=>({...node,label:nodeLabel(node),engineering_path:nodePath(node).map(nodeLabel).join(' / ')})))
async function sourcesUploaded(rows){
  if(!rows.length)return
  const target=rows[0].target,targetFieldId=Number(target.field_id)
  nodes.value=await catalogTree()||[]
  selectedNode.value=nodes.value.find(n=>Number(n.node_id)===Number(target.node_id))||target
  activeTab.value='files';query.pageNum=1;query.keyword=''
  await loadContents();reviewFieldId.value=targetFieldId;await nextTick()
  await sourceReviewLibrary.value?.showReviewQueue(rows)
}
async function exportOriginals(){if(!selectedNode.value)return;try{downloadBlob(await exportOriginalPackage(managementFieldId.value,selectedNode.value.node_id),`${displayFileName(selectedNode.value)}-原件资料包.zip`)}catch(e){ElMessage.error(message(e))}}
async function showFileHistory(row){try{fileHistory.value=await libraryDocumentHistory(managementFieldId.value,row.document_id)||[];fileHistoryOpen.value=true}catch(e){ElMessage.error(message(e))}}




function fileStateLabel(value){return {DRAFT:'草稿',HISTORICAL:'已归档',ACTIVE:'使用中',WITHDRAWN:'已撤回',FORBIDDEN:'禁用',UNASSIGNED:'未指定'}[value]||'—'}
function trajectoryKindLabel(value){return {DESIGN:'设计轨道',ACTUAL:'实钻测斜'}[value]||'—'}
function phaseLabel(value){return {PLAN:'设计',DESIGN:'设计',ACTUAL:'实钻',PROTOTYPE:'方案草稿',DOCUMENT:'工程资料'}[value]||value||'—'}
function numberText(value){return value==null?'—':Number(value).toFixed(2)}
function showTrajectoryVersion(row){catalogData.value.show(row)}
async function exportTrajectory(row){try{downloadBlob(await exportReduction(managementFieldId.value,row.survey_version_id),`${row.wellbore_code||'trajectory'}-v${row.version_no}.xlsx`)}catch(e){ElMessage.error(message(e))}}
function canPatchSource(row){const name=String(row.original_name||'').toLowerCase();return /\.(xlsx|xlsm|xls|docx|csv|tsv|txt|asc|dat|mwd|survey|xml|witsml)$/.test(name)||!name.includes('.')}
function exportedSourceName(name){const value=String(name||'trajectory');const dot=value.lastIndexOf('.');const hasExtension=dot>0&&dot<value.length-1;return hasExtension?`${value.slice(0,dot)}-exported${value.slice(dot)}`:`${value}-exported`}
async function exportSource(row,mode){try{const blob=await exportTrajectorySource(managementFieldId.value,row.survey_version_id,mode);const name=String(row.original_name||`${row.wellbore_code||'trajectory'}-v${row.version_no}`);downloadBlob(blob,mode==='ORIGINAL'?name:exportedSourceName(name))}catch(e){ElMessage.error(message(e))}}
async function exportCompletePackage(row){try{downloadBlob(await exportTrajectoryPackage(managementFieldId.value,row.survey_version_id),`${row.wellbore_code||'trajectory'}-v${row.version_no}-资料包.zip`)}catch(e){ElMessage.error(message(e))}}
function showSourceContent(row){const name=String(row?.original_name||'').toLowerCase();if(name.endsWith('.pdf'))sourceOriginalPreview.value?.show(row);else sourcePreview.value?.show(row,{readOnly:!canUploadFileVersion(row)||Number(row.source_field_id||managementFieldId.value)!==managementFieldId.value})}
async function activateTrajectory(row){
  if(!canWriteTrajectory(row))return
  const targetFieldId=managementFieldId.value,versionId=row.survey_version_id
  const previous=trajectoryVersions.value.find(v=>Number(v.wellbore_id)===Number(row.wellbore_id)&&v.trajectory_kind===row.trajectory_kind&&Number(v.is_current))?.survey_version_id||null
  try{
    await ElMessageBox.confirm(`将 v${row.version_no} 设为该井眼的当前${row.trajectory_kind==='DESIGN'?'设计':'实测'}版本？`,'版本发布确认')
    await activateTrajectoryVersion(targetFieldId,versionId,previous)
    await loadContents();ElMessage.success('当前数据版本已更新')
  }catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(message(e))}
}
function openRevisionDialog(){
  if(!access.value.writeData)return
  revisionTarget.value={...selectedNode.value}
  Object.assign(revisionForm,{businessRevision:'',outcomeName:revisionTarget.value.node_name,phase:revisionTarget.value.node_type==='SURVEY'?'ACTUAL':'PLAN',note:''})
  revisionOpen.value=true
}
async function saveRevision(){
  const target=revisionTarget.value
  if(saving.value||!catalogAccess(target,permissions.value).writeData)return
  if(!revisionForm.businessRevision||!revisionForm.note)return ElMessage.warning('修订号和修订说明不能为空')
  saving.value=true
  try{await createLibraryRevision(target.field_id,target.node_id,{...revisionForm});revisionOpen.value=false;await loadContents();ElMessage.success('修订已创建')}
  catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
function showRevision(row){revisionDetail.value=row;revisionDetailOpen.value=true;libraryRevision(managementFieldId.value,row.revision_id).then(v=>{revisionDetail.value=v}).catch(e=>ElMessage.error(message(e)))}
function openDataLink(row){
  if(!access.value.writeData)return
  const node={...selectedNode.value},kind=node.node_type==='PLAN'?'DESIGN':'ACTUAL'
  dataLinkTarget.value={node,versions:trajectoryVersions.value.filter(v=>Number(v.wellbore_id)===Number(node.wellbore_id)&&v.trajectory_kind===kind).map(v=>({...v}))}
  selectedRevisionId.value=row.revision_id;selectedDataVersionId.value=null;selectedDataLabel.value=row.outcome_name||'';dataLinkOpen.value=true
}
async function saveDataLink(){
  const target=dataLinkTarget.value
  if(saving.value||!catalogAccess(target?.node,permissions.value).writeData)return
  if(!target.versions.some(v=>v.survey_version_id===selectedDataVersionId.value))return ElMessage.warning('请选择当前井眼对应用途的轨迹数据版本')
  saving.value=true
  try{await attachRevisionData(target.node.field_id,selectedRevisionId.value,selectedDataVersionId.value,selectedDataLabel.value||'轨迹数据');dataLinkOpen.value=false;await loadContents();ElMessage.success('轨迹数据已关联')}
  catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
async function publishRevision(row){
  if(!access.value.writeData)return
  const targetFieldId=managementFieldId.value,revisionId=row.revision_id,expectedRevisionId=row.published_revision_id||null
  try{await ElMessageBox.confirm('发布后将作为该成果当前业务版本，是否继续？','发布确认');await activateLibraryRevision(targetFieldId,revisionId,{expectedRevisionId,note:'工程人员确认发布'});await loadContents();ElMessage.success('已发布')}
  catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(message(e))}
}
async function download(row){try{downloadBlob(await downloadSourceOriginal(row.source_field_id||managementFieldId.value,row.source_id),row.original_name)}catch(e){ElMessage.error(message(e))}}
</script>
<style scoped>
.library-page{min-height:650px}.library-layout{display:grid;grid-template-columns:280px minmax(0,1fr);min-height:620px;border:1px solid var(--el-border-color-light);background:var(--el-bg-color)}
.library-tree-panel{border-right:1px solid var(--el-border-color-light);padding:14px;overflow:auto;max-height:max(480px,calc(100vh - 190px))}
.tree-title{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-bottom:12px;color:var(--el-text-color-primary);font-weight:600}.tree-title>span{white-space:nowrap}.tree-title .el-input{flex-basis:100%}
.tree-node{display:flex;align-items:center;gap:6px;min-width:0;font-size:13px}.tree-node>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.library-content{padding:18px;min-width:0}.content-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:16px}.content-heading :deep(.el-breadcrumb){line-height:1.8}.content-heading :deep(.el-breadcrumb__item){float:none;display:inline}
.node-actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end;flex-shrink:0}.library-tabs{margin-bottom:12px}.content-section{min-width:0}
.attachment-location{margin-bottom:16px;line-height:1.7;color:var(--el-text-color-regular)}
.section-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.section-toolbar .el-form{margin-bottom:0}.section-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.library-content :deep(.el-table){width:100%}
@media(max-width:1100px){.content-heading,.section-toolbar{align-items:flex-start;flex-wrap:wrap}.section-actions{padding-bottom:4px}}
@media(max-width:900px){.library-layout{grid-template-columns:1fr}.library-tree-panel{max-height:260px;border-right:0;border-bottom:1px solid var(--el-border-color-light)}.library-content{padding:12px}.node-actions{justify-content:flex-start}.section-toolbar :deep(.el-form-item){margin-right:10px}.section-toolbar :deep(.el-input){max-width:100%}}
</style>
