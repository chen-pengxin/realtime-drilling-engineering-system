<template>
  <DrillingPageFrame title="工程项目管理" :show-field="false" :basic-context="true">
    <section class="library-page">
      <div class="library-layout">
        <aside class="library-tree-panel"><div class="tree-title"><span>工程目录</span><el-button text circle icon="Refresh" :loading="loading" aria-label="刷新目录" @click="load" /><el-button v-if="canCreateBlock" link type="primary" icon="Plus" @click="openBlockDialog">区块</el-button><el-input v-model="treeKeyword" size="small" clearable placeholder="搜索名称" prefix-icon="Search" /></div><el-tree ref="treeRef" :data="treeData" node-key="node_id" highlight-current default-expand-all :filter-node-method="filterNode" @node-click="selectNode"><template #default="{ data }"><span class="tree-node"><el-icon><component :is="iconFor(data.node_type)" /></el-icon><span>{{ nodeLabel(data) }}</span></span></template></el-tree><el-empty v-if="!treeData.length && !loading" description="暂无可访问的工程层级" /></aside>
        <main ref="contentRef" class="library-content"><template v-if="selectedNode">
          <div class="content-heading">
            <el-breadcrumb separator="/"><el-breadcrumb-item v-for="item in breadcrumbs" :key="item.node_id">{{ nodeLabel(item) }}</el-breadcrumb-item></el-breadcrumb>
            <div class="node-actions">
              <el-button v-if="canAddNode && selectedNode.node_type!=='WELLBORE'" type="primary" plain icon="Plus" @click="openNodeDialog()">{{ addNodeLabel }}</el-button>
              <el-button v-if="canEditNode && selectedNode.node_type!=='WELLBORE'" icon="Edit" @click="openEditDialog">编辑{{ nodeTypeLabels[selectedNode.node_type] }}</el-button>
              <el-button v-if="canDeleteSelected" type="danger" plain icon="Delete" @click="deleteSelectedNode">删除{{ nodeTypeLabels[selectedNode.node_type] }}</el-button>
            </div>
          </div>
          <div v-if="selectedNode.node_type==='WELLBORE'" class="wellbore-public-meta">
            <el-descriptions :column="publicMetaColumns" border>
              <el-descriptions-item label="名称">{{ nodeLabel(selectedNode) }}</el-descriptions-item>
              <el-descriptions-item label="编码">{{ selectedNode.node_code || selectedNode.wellbore_code || '—' }}</el-descriptions-item>
              <el-descriptions-item label="施工状态">{{ statusLabels[selectedNode.engineering_status] || '未填写' }}</el-descriptions-item>
              <el-descriptions-item label="资料状态">{{ nodeStateLabel(selectedNode.status) }}</el-descriptions-item>
            </el-descriptions>
            <div class="wellbore-public-actions"><el-button v-if="canEditNode" icon="Edit" @click="openEditDialog">编辑井眼</el-button></div>
          </div>
          <el-tabs v-model="activeTab" class="library-tabs" @tab-change="loadContents">
            <el-tab-pane label="基础数据" name="basicData" />
            <el-tab-pane v-if="selectedNode.node_type==='WELLBORE' && access.readData" label="靶区数据" name="targetData" />
            <el-tab-pane v-if="selectedNode.node_type==='WELLBORE' && access.readData" label="设计轨道节点数据" name="designNodes" />
            <el-tab-pane v-if="selectedNode.node_type==='WELLBORE' && access.readData" label="设计轨道详细数据" name="designDetails" />
            <el-tab-pane v-if="selectedNode.node_type==='WELLBORE' && access.readData" label="实际轨迹测斜数据" name="surveyData" />
            <el-tab-pane v-if="selectedNode.node_type==='WELLBORE' && access.readData" label="实钻轨迹入靶数据" name="targetPoints" />
            <el-tab-pane v-if="selectedNode.node_type!=='WELLBORE'" :label="childTabLabel || '下级列表'" name="children" />
            <el-tab-pane v-if="access.readFiles" label="文件管理" name="files" />
          </el-tabs>
          <section v-if="activeTab === 'basicData'" class="content-section">
            <div class="section-toolbar"><span></span><div><el-button v-if="canEditNode" icon="Edit" @click="selectedNode.node_type==='WELLBORE'?editBasic():openEditDialog()">修改</el-button><el-button icon="Refresh" @click="refreshBasic">刷新</el-button></div></div>
            <AppendixTable v-if="selectedNode.node_type==='WELLBORE'" :show-index="false" :rows="[engineeringData.basic||{}]" :columns="basicColumns" :title="appendixTitle('A.1','基础数据')" />
            <el-descriptions v-if="selectedNode.node_type==='WELL'" :column="2" border class="basic-data-summary">
              <el-descriptions-item v-for="item in wellDisplayFields(selectedNode)" :key="item.key" :label="item.label">{{ item.value == null || item.value === '' ? '—' : item.value }}</el-descriptions-item>
            </el-descriptions>
            <el-descriptions v-else :column="2" border class="basic-data-summary">
              <el-descriptions-item v-for="item in catalogDisplayFields(selectedNode)" :key="item.key" :label="item.label">{{ item.value == null || item.value === '' ? '—' : item.value }}</el-descriptions-item>
            </el-descriptions>

          </section>
          <section v-else-if="activeTab === 'children'" class="content-section">
            <div class="section-toolbar"><span>{{ childTabLabel || '下级列表' }}</span><el-button v-if="canAddNode" type="primary" plain icon="Plus" @click="openNodeDialog">{{ addNodeLabel }}</el-button></div>
            <el-table :data="children" v-loading="contentLoading" border @row-click="selectChild"><el-table-column label="名称" min-width="240"><template #default="s"><el-button link type="primary" @click.stop="selectChild(s.row)">{{ nodeLabel(s.row) }}</el-button></template></el-table-column><el-table-column label="类别" width="140"><template #default="s">{{ nodeTypeLabels[s.row.node_type] }}</template></el-table-column><el-table-column v-if="selectedNode.node_type==='WELL'" label="井眼状态" width="120"><template #default="s">{{ statusLabels[s.row.engineering_status] || '未填写' }}</template></el-table-column><el-table-column label="资料状态" width="100"><template #default="s">{{ nodeStateLabel(s.row.status) }}</template></el-table-column><el-table-column label="操作" width="100"><template #default="s"><el-button v-if="canDeleteNode(s.row)" link type="danger" @click.stop="deleteNode(s.row)">删除</el-button></template></el-table-column></el-table>
            <el-empty v-if="!children.length&&!contentLoading" description="当前层级暂无下级数据" />
          </section>
          <section v-else-if="activeTab === 'files'" class="content-section"><div class="section-toolbar"><el-form inline @submit.prevent="loadContents"><el-form-item label="名称"><el-input v-model="query.keyword" clearable placeholder="系统名称或原始文件名" /></el-form-item><el-form-item label="用途"><el-select v-model="query.role" clearable placeholder="全部" style="width:150px"><el-option v-for="(label,key) in documentRoles" :key="key" :value="key" :label="label" /></el-select></el-form-item><el-form-item label="轨迹类别"><el-select v-model="query.trajectoryKind" clearable placeholder="全部" style="width:145px"><el-option label="设计轨道" value="DESIGN" /><el-option label="实钻测斜" value="ACTUAL" /></el-select></el-form-item><el-button type="primary" icon="Search" @click="search">查询</el-button></el-form><div class="section-actions"><el-button v-if="canUpload" icon="Upload" @click="sourceUpload.show(selectedNode)">文件导入</el-button></div></div><el-table :data="files" v-loading="contentLoading" border><el-table-column prop="display_name" label="系统名称" min-width="270" show-overflow-tooltip><template #default="s">{{ displayFileName(s.row) }}</template></el-table-column><el-table-column label="工程层级" min-width="260" show-overflow-tooltip><template #default="s">{{ s.row.engineering_path || '当前节点' }}</template></el-table-column><el-table-column prop="document_role" label="文件用途" width="130"><template #default="s">{{ documentRoles[s.row.document_role] || s.row.document_role }}</template></el-table-column><el-table-column label="轨迹类别" width="120"><template #default="s"><el-tag v-if="s.row.trajectory_kind" :type="s.row.trajectory_kind==='DESIGN'?'primary':'success'">{{ trajectoryKindLabel(s.row.trajectory_kind) }}</el-tag><span v-else>{{ ['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(s.row.document_role)?'未指定':'—' }}</span></template></el-table-column><el-table-column label="来源阶段" width="100"><template #default="s">{{ phaseLabel(s.row.phase) }}</template></el-table-column><el-table-column prop="business_revision" label="业务修订" width="120" /><el-table-column prop="representation_format" label="表示格式" width="100" /><el-table-column label="文件版次" width="110"><template #default="s">第 {{ s.row.edition_no }} 版</template></el-table-column><el-table-column label="文件状态" width="100"><template #default="s">{{ fileStateLabel(s.row.file_usage_state) }}</template></el-table-column><el-table-column prop="original_name" label="原始文件名" min-width="220" show-overflow-tooltip></el-table-column><el-table-column label="操作" width="110"><template #default="s"><el-dropdown trigger="click" @command="commandFile($event,s.row)"><el-button link type="primary">操作 <el-icon><ArrowDown/></el-icon></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="canRenameFile(s.row)" command="rename">修改文件名称</el-dropdown-item><el-dropdown-item v-if="s.row.source_id" command="preview">文件预览</el-dropdown-item><el-dropdown-item command="status">状态修改</el-dropdown-item><el-dropdown-item v-if="hasPermission('drilling:file:remove')" command="delete">删除</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column></el-table><pagination v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="loadContents" /></section>
          <section v-else-if="activeTab === 'targetData'" class="content-section">
            <div class="section-toolbar"><span>靶区数据</span><div><el-button v-if="access.writeData" type="primary" plain icon="Plus" @click="openTargetDialog()">新增靶区</el-button><el-button icon="Refresh" @click="loadTargets">刷新</el-button></div></div>
            <AppendixTable :rows="targetRows" :columns="targetColumns" :title="appendixTitle('A.2','靶区数据')" :notes="appendixNotes.targetData" actions :editable="()=>access.writeData" @edit="openTargetDialog" @delete="row=>commandTarget('delete',row)" />
          </section>
          <section v-else-if="activeTab === 'targetPoints'" class="content-section">
            <div class="section-toolbar"><span>实钻轨迹入靶数据</span><div><el-select v-model="targetPointVersionId" clearable placeholder="选择实测版本" style="width:220px" @change="loadTargetPoints"><el-option v-for="v in actualVersions" :key="v.survey_version_id" :value="v.survey_version_id" :label="`实测 v${v.version_no}${Number(v.is_current)?'（当前）':''}`" /></el-select><el-button v-if="canEditActual" icon="Plus" @click="newTargetPoint">新增</el-button><el-button icon="Refresh" @click="loadTargetPoints">刷新</el-button></div></div>
            <AppendixTable :rows="targetPointRows" :columns="targetPointColumns" :title="appendixTitle('A.6','实钻轨迹入靶数据')" :notes="appendixNotes.targetPoints" actions :editable="()=>canEditActual" @edit="editTargetPoint" @delete="deleteTargetPoint" />
          </section>
          <section v-else-if="['surveyData','designNodes','designDetails'].includes(activeTab)" class="content-section">
            <div class="section-toolbar"><span></span><div class="section-actions"><el-button v-if="canEditInlineTrajectory" icon="Plus" @click="addInlineRow">新增</el-button><el-button v-if="canUpload" icon="Upload" @click="catalogData.startImport(activeTab==='surveyData'?'ACTUAL':'DESIGN','AUTO')">导入</el-button><el-button icon="Download" :disabled="!inlineDetail" @click="exportCurrentTrajectory">导出</el-button><el-button icon="View" :disabled="!inlineDetail" @click="showCurrentTrajectoryVisual">可视化</el-button><el-button icon="Refresh" @click="loadContents">刷新</el-button></div></div>
            <AppendixTable :rows="inlineRows" :columns="trajectoryColumns(activeTab==='surveyData')" :title="appendixTitle(activeTab==='designNodes'?'A.3':activeTab==='designDetails'?'A.4':'A.5',activeTab==='designNodes'?'设计轨道节点数据':activeTab==='designDetails'?'设计轨道分点数据':'实钻轨迹测斜数据')" :notes="appendixNotes[activeTab]" :calculation="activeTab==='surveyData'" actions :editable="canEditTrajectoryRow" @edit="editTrajectoryRow" @delete="deleteTrajectoryRow" />
            <el-table :data="displayedTrajectoryVersions" v-loading="contentLoading" border class="version-table">
              <el-table-column type="index" label="序号" width="66"/><el-table-column label="井眼" min-width="240"><template #default="s">{{ trajectoryLabel(s.row) }}</template></el-table-column><el-table-column label="数据版本" width="100"><template #default="s">v{{ s.row.version_no }}</template></el-table-column><el-table-column prop="station_count" label="测点数" width="100"/><el-table-column label="最大井深（m）" width="140"><template #default="s">{{ numberText(s.row.max_md) }}</template></el-table-column><el-table-column prop="source_format" label="来源格式" width="110"/><el-table-column label="状态" width="90"><template #default="s">{{ Number(s.row.is_current)?'当前':'历史' }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="s"><el-button v-if="!Number(s.row.is_current)&&canWriteTrajectory(s.row)" link type="primary" @click="activateTrajectory(s.row)">切换版本</el-button></template></el-table-column>
            </el-table>
          </section>
          <section v-else-if="activeTab === 'revisions'" class="content-section"><div class="section-toolbar"><el-button v-if="access.writeData" type="primary" plain icon="Plus" @click="openRevisionDialog">新增修订</el-button></div><el-table :data="revisions" v-loading="contentLoading" border><el-table-column prop="business_revision" label="修订号" width="150" /><el-table-column prop="outcome_name" label="名称" min-width="220" show-overflow-tooltip /><el-table-column label="阶段" width="100"><template #default="s">{{ phaseLabel(s.row.phase) }}</template></el-table-column><el-table-column label="使用状态" width="110"><template #default="s">{{ fileStateLabel(s.row.usage_state) }}</template></el-table-column><el-table-column prop="dataset_count" label="数据版本数" width="90" /><el-table-column label="操作" width="240"><template #default="s"><el-button link type="primary" @click="showRevision(s.row)">查看</el-button><el-button v-if="access.writeData && canLinkData" link type="primary" @click="openDataLink(s.row)">选择数据版本</el-button><el-button v-if="access.writeData" link type="warning" :disabled="!!s.row.published_revision_id" @click="publishRevision(s.row)">发布</el-button></template></el-table-column></el-table></section>

        </template><el-empty v-else description="请从左侧选择区块、井场、井或井眼" /></main>
      </div>
    </section>
    <ResizableDialog v-model="nodeOpen" :title="`新增${nodeTypeLabels[nodeForm.nodeType]}`" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form label-width="100px" @submit.prevent="saveNode">
        <el-form-item v-for="item in nodeTarget?.path" :key="item.node_id" :label="`所属${nodeTypeLabels[item.node_type]}`"><span>{{ nodeLabel(item) }}</span></el-form-item>
        <el-form-item :label="nameLabel(nodeForm.nodeType)" required><el-input v-model="nodeForm.name" maxlength="255" /></el-form-item>
        <el-form-item :label="nodeForm.nodeType==='WELL'?'井号':'编码'"><el-input v-model="nodeForm.code" maxlength="128" placeholder="留空时使用名称" /></el-form-item>
        <el-form-item v-if="nodeForm.nodeType==='WELLBORE'" label="分支起始井眼"><WellboreSelect v-model="nodeForm.parentWellboreId" :rows="parentBoreRows" placeholder="主井眼留空" /></el-form-item>
        <el-form-item v-if="nodeForm.nodeType==='WELLBORE'" label="井眼状态"><el-select v-model="nodeForm.engineeringStatus"><el-option v-for="(label,key) in statusLabels" :key="key" :label="label" :value="key" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="nodeOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveNode">确 定</el-button></template>
    </ResizableDialog>

    <ResizableDialog v-model="editOpen" :title="`编辑${nodeTypeLabels[editTarget?.node_type] || ''}`" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form label-width="100px" @submit.prevent="saveEdit">
        <el-form-item label="所属位置"><span>{{ editLocation }}</span></el-form-item>
        <el-form-item :label="nameLabel(editTarget?.node_type)" required><el-input v-model="editForm.name" maxlength="255" /></el-form-item>
        <el-form-item v-if="editTarget?.node_type==='WELLBORE'" label="编码" required><el-input v-model="editForm.code" maxlength="128" /></el-form-item>
        <template v-if="editTarget?.node_type==='WELL'"><el-form-item label="井型"><el-select v-model="editForm.wellType" style="width:100%"><el-option v-for="item in wellTypeOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item label="井位置"><el-input v-model="editForm.location" maxlength="255" /></el-form-item><el-form-item label="省份"><el-select v-model="editForm.province" filterable clearable style="width:100%"><el-option v-for="item in provinceOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="坐标系"><el-select v-model="editForm.coordinateSystem" filterable style="width:100%"><el-option v-for="item in coordinateOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item label="井口横坐标基准"><el-select v-model="editForm.horizontalDatum" clearable style="width:100%"><el-option v-for="item in coordinateOptions" :key="`datum-${item.value}`" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item label="井口横坐标（m）"><el-input-number v-model="editForm.wellheadE" :controls="false" style="width:100%" /></el-form-item><el-form-item label="井口纵坐标（m）"><el-input-number v-model="editForm.wellheadN" :controls="false" style="width:100%" /></el-form-item><el-form-item label="井口垂深（m）"><el-input-number v-model="editForm.wellheadTvd" :controls="false" style="width:100%" /></el-form-item><el-form-item label="磁偏角（°）"><el-input-number v-model="editForm.magneticDeclination" :controls="false" style="width:100%" /></el-form-item><el-form-item label="子午线收敛角（°）"><el-input-number v-model="editForm.meridianConvergence" :controls="false" style="width:100%" /></el-form-item><el-form-item label="作业单位"><el-input v-model="editForm.operatorName" maxlength="128" /></el-form-item><el-form-item label="所属构造"><el-input v-model="editForm.structuralPosition" maxlength="255" /></el-form-item><el-form-item label="备注"><el-input v-model="editForm.remark" type="textarea" maxlength="500" /></el-form-item><el-form-item label="基础资料来源"><el-input v-model="editForm.sourceReference" maxlength="500" /></el-form-item></template>
        <el-form-item v-if="editTarget?.node_type==='WELLBORE'" label="井眼状态"><el-select v-model="editForm.engineeringStatus"><el-option v-for="(label,key) in statusLabels" :key="key" :label="label" :value="key" /></el-select></el-form-item>
        <el-form-item label="资料状态"><el-select v-model="editForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="归档" value="ARCHIVED" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="editOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveEdit">保 存</el-button></template>
    </ResizableDialog>

    <ResizableDialog v-model="revisionOpen" title="新增修订" width="min(560px, calc(100vw - 32px))"><el-form label-width="min(100px, calc(100vw - 32px))"><el-form-item label="修订号"><el-input v-model="revisionForm.businessRevision" placeholder="例如 rev#7、rev0.11" /></el-form-item><el-form-item label="名称"><el-input v-model="revisionForm.outcomeName" /></el-form-item><el-form-item label="阶段"><el-select v-model="revisionForm.phase"><el-option v-if="revisionTarget?.node_type==='PLAN'" label="设计" value="PLAN" /><el-option v-if="revisionTarget?.node_type==='PLAN'" label="方案草稿" value="PROTOTYPE" /><el-option v-if="revisionTarget?.node_type==='SURVEY'" label="实钻" value="ACTUAL" /></el-select></el-form-item><el-form-item label="修订说明"><el-input v-model="revisionForm.note" type="textarea" /></el-form-item></el-form><template #footer><el-button @click="revisionOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveRevision">保存</el-button></template></ResizableDialog>
    <ResizableDialog v-model="catalogOpen" :close-on-click-modal="false" :title="`${catalogMode==='create'?'新增':'编辑'}${catalogType==='block'?'区块':'井场'}`" width="min(760px, calc(100vw - 32px))"><el-form :model="catalogForm" label-width="120px" class="catalog-form"><el-form-item v-if="catalogType==='field'" label="所属区块"><span>{{ catalogBlockName }}</span></el-form-item><el-row :gutter="18"><el-col :xs="24" :sm="12"><el-form-item :label="catalogType==='block'?'区块编码':'井场编码'" required><el-input v-model="catalogForm.code" :disabled="catalogMode==='edit'" maxlength="64" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item :label="catalogType==='block'?'区块名称':'井场名称'" required><el-input v-model="catalogForm.name" maxlength="128" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="省份" required><el-select v-model="catalogForm.province" filterable style="width:100%"><el-option v-for="item in provinceOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="授权用户" required><el-select v-model="catalogForm.authorizedUser" filterable style="width:100%"><el-option v-for="item in userOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="井位坐标系" required><el-select v-model="catalogForm.coordinateSystem" filterable style="width:100%"><el-option v-for="item in coordinateOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="井口横坐标" required><el-input v-model="catalogForm.wellheadEast" maxlength="32" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="井口纵坐标" required><el-input v-model="catalogForm.wellheadNorth" maxlength="32" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="施工单位"><el-input v-model="catalogForm.operatorName" maxlength="128" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="区块位置"><el-input v-model="catalogForm.location" maxlength="255" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="所属构造"><el-input v-model="catalogForm.structuralPosition" maxlength="255" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="磁偏角"><el-input v-model="catalogForm.magneticDeclination" maxlength="32" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="收敛角"><el-input v-model="catalogForm.meridianConvergence" maxlength="32" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="施工状态"><el-select v-model="catalogForm.constructionStatus" style="width:100%"><el-option label="未完成" value="UNPLANNED"/><el-option label="完成" value="COMPLETED"/></el-select></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="是否启用"><el-radio-group v-model="catalogForm.enabled"><el-radio value="0">否</el-radio><el-radio value="1">是</el-radio></el-radio-group></el-form-item></el-col><el-col :xs="24"><el-form-item label="说明"><el-input v-model="catalogForm.description" type="textarea" maxlength="500" /></el-form-item></el-col><el-col :xs="24"><el-form-item label="备注"><el-input v-model="catalogForm.remark" type="textarea" maxlength="500" /></el-form-item></el-col><el-col :xs="24"><el-form-item label="基础资料来源"><el-input v-model="catalogForm.sourceReference" maxlength="500" /></el-form-item></el-col></el-row></el-form><template #footer><el-button @click="catalogOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveCatalog">确定</el-button></template></ResizableDialog>
    <ResizableDialog v-model="fileHistoryOpen" title="文件版次历史" width="min(820px, calc(100vw - 32px))"><el-table :data="fileHistory" border><el-table-column prop="edition_no" label="版次" width="90" /><el-table-column prop="representation_format" label="格式" width="100" /><el-table-column label="系统名称" min-width="240"><template #default="s">{{ displayFileName(s.row) }}</template></el-table-column><el-table-column prop="original_name" label="原始文件名" min-width="260" show-overflow-tooltip /><el-table-column prop="source_path" label="来源定位" min-width="260" show-overflow-tooltip /><el-table-column prop="create_time" label="登记时间" width="170" /><el-table-column label="操作" width="90"><template #default="s"><el-button link type="primary" @click="download(s.row)">原件</el-button></template></el-table-column></el-table></ResizableDialog>
    <ResizableDialog v-model="revisionDetailOpen" title="修订详情" width="min(620px, calc(100vw - 32px))"><el-descriptions v-if="revisionDetail" :column="1" border><el-descriptions-item label="修订号">{{ revisionDetail.business_revision }}</el-descriptions-item><el-descriptions-item label="名称">{{ revisionDetail.outcome_name }}</el-descriptions-item><el-descriptions-item label="阶段">{{ phaseLabel(revisionDetail.phase) }}</el-descriptions-item><el-descriptions-item label="轨迹数据版本">{{ revisionDetail.datasets?.length || 0 }} 个版本</el-descriptions-item><el-descriptions-item label="修订说明">{{ revisionDetail.revision_note || '—' }}</el-descriptions-item></el-descriptions></ResizableDialog>
    <ResizableDialog v-model="dataLinkOpen" title="关联轨迹数据版本" width="min(680px, calc(100vw - 32px))"><el-form label-width="min(100px, calc(100vw - 32px))"><el-form-item label="数据版本"><el-select v-model="selectedDataVersionId" filterable placeholder="请选择同一井眼、同一用途的数据版本" style="width:100%"><el-option v-for="v in dataLinkTarget?.versions || []" :key="v.survey_version_id" :value="v.survey_version_id" :label="`${v.wellbore_code || '井眼'} · ${v.trajectory_kind === 'DESIGN' ? '设计' : '实测'} v${v.version_no}${Number(v.is_current) ? '（当前）' : ''}`" /></el-select></el-form-item><el-form-item label="说明"><el-input v-model="selectedDataLabel" maxlength="255" /></el-form-item></el-form><template #footer><el-button @click="dataLinkOpen=false">取 消</el-button><el-button type="primary" :loading="saving" @click="saveDataLink">关联</el-button></template></ResizableDialog>
    <ResizableDialog v-model="targetPointOpen" title="编辑实钻轨迹入靶数据" width="min(620px, calc(100vw - 32px))"><el-form v-if="targetPointEdit" label-width="120px"><el-form-item label="靶点名称"><el-input v-model="targetPointEdit.target_no" /></el-form-item><el-form-item label="MD（m）"><el-input-number v-model="targetPointEdit.md" :controls="false" /></el-form-item><el-form-item label="井斜角（°）"><el-input-number v-model="targetPointEdit.inclination" :controls="false" /></el-form-item><el-form-item label="方位角（°）"><el-input-number v-model="targetPointEdit.azimuth" :controls="false" /></el-form-item><el-form-item label="TVD（m）"><el-input-number v-model="targetPointEdit.tvd" :controls="false" /></el-form-item><el-form-item v-for="c in targetPointColumns.filter(c=>['closure_distance','closure_azimuth','offset','longitudinal_offset','lateral_offset'].includes(c.key))" :key="c.key" :label="c.label.replaceAll('\n',' ')"><el-input-number v-model="targetPointEdit[c.key]" :controls="false"/></el-form-item><el-form-item label="备注"><el-input v-model="targetPointEdit.remark" type="textarea" maxlength="500" /></el-form-item></el-form><template #footer><el-button @click="targetPointOpen=false">取消</el-button><el-button type="primary" :loading="targetPointSaving" @click="saveTargetPoint">保存</el-button></template></ResizableDialog>
    <ResizableDialog v-model="targetOpen" :title="targetEditId?'编辑靶区':'新增靶区'" width="min(760px, calc(100vw - 32px))"><el-form label-width="120px"><el-form-item label="靶点名称" required><el-input v-model="targetForm.name" maxlength="128" /></el-form-item><el-form-item label="靶区类型"><el-select v-model="targetForm.type"><el-option label="点靶" value="POINT"/><el-option label="矩形靶区" value="RECTANGLE"/><el-option label="椭圆靶区" value="ELLIPSE"/></el-select></el-form-item><el-row :gutter="16"><el-col :span="12"><el-form-item label="海拔（m）"><el-input-number v-model="targetForm.elevation" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="纵坐标 X（m）"><el-input-number v-model="targetForm.north" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="横坐标 Y（m）"><el-input-number v-model="targetForm.east" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="垂深（m）"><el-input-number v-model="targetForm.tvd" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="水平位移（m）"><el-input-number v-model="targetForm.closureDistance" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="平移方位（°）"><el-input-number v-model="targetForm.closureAzimuth" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="形状"><el-input v-model="targetForm.shape" maxlength="32" /></el-form-item></el-col><el-col :span="12"><el-form-item label="半径（m）"><el-input-number v-model="targetForm.radius" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="高度（m）"><el-input-number v-model="targetForm.height" :controls="false" /></el-form-item></el-col><el-col :span="12"><el-form-item label="宽度（m）"><el-input-number v-model="targetForm.width" :controls="false" /></el-form-item></el-col></el-row><el-form-item label="备注"><el-input v-model="targetForm.remark" type="textarea" maxlength="500" /></el-form-item></el-form><template #footer><el-button @click="targetOpen=false">取消</el-button><el-button type="primary" :loading="targetSaving" @click="saveTarget">保存</el-button></template></ResizableDialog>
    <ResizableDialog v-model="basicOpen" title="修改基础数据" width="min(760px,calc(100vw - 32px))" :close-on-click-modal="false"><el-form label-width="145px"><el-row :gutter="16"><el-col v-for="c in basicFields" :key="c.key" :span="12"><el-form-item :label="c.label.replaceAll('\n',' ')"><el-input v-if="['coordinate_system','depth_datum'].includes(c.key)" v-model="basicForm[c.key]"/><el-select v-else-if="c.key==='north_reference'" v-model="basicForm[c.key]"><el-option label="网格北" value="GRID_NORTH"/><el-option label="真北" value="TRUE_NORTH"/><el-option label="磁北" value="MAGNETIC_NORTH"/></el-select><el-input-number v-else v-model="basicForm[c.key]" :controls="false"/></el-form-item></el-col></el-row></el-form><template #footer><el-button @click="basicOpen=false">取消</el-button><el-button type="primary" :loading="rowSaving" @click="saveBasic">保存</el-button></template></ResizableDialog>
    <ResizableDialog v-model="rowOpen" :title="rowIndex<0?'新增测点':'修改测点'" width="min(760px,calc(100vw - 32px))" :close-on-click-modal="false"><el-form label-width="125px"><el-row :gutter="16"><el-col v-for="c in trajectoryColumns(activeTab==='surveyData')" :key="c.key" :span="12"><el-form-item :label="c.label.replaceAll('\n',' ')"><el-input v-if="c.key==='remark'" v-model="rowForm[c.key]"/><el-input-number v-else v-model="rowForm[c.key]" :controls="false" :precision="3"/></el-form-item></el-col></el-row></el-form><template #footer><el-button @click="rowOpen=false">取消</el-button><el-button type="primary" :loading="rowSaving" @click="saveTrajectoryRow">保存</el-button></template></ResizableDialog>
    <ResizableDialog v-model="fileStatusOpen" title="状态修改" width="420px"><el-select v-model="fileStatus"><el-option v-for="(label,key) in {ACTIVE:'使用中',HISTORICAL:'历史资料',WITHDRAWN:'已撤回',FORBIDDEN:'禁用'}" :key="key" :label="label" :value="key"/></el-select><template #footer><el-button @click="fileStatusOpen=false">取消</el-button><el-button type="primary" @click="saveFileStatus">保存</el-button></template></ResizableDialog>
    <CatalogTrajectoryData ref="catalogData" :field-id="managementFieldId" :node="selectedNode" :table-role="activeTab" :wellbores="trajectoryWellbores" :writable-wellbore-ids="writableWellboreIds" :can-read-files="access.readFiles" :engineering-path="breadcrumbs.filter(n=>['BLOCK','FIELD','WELL','WELLBORE'].includes(n.node_type)).map(nodeLabel).join(' / ')" :versions="trajectoryVersions" @saved="load" />
    <DocumentContent ref="sourcePreview" :field-id="managementFieldId" @parsed="loadContents" />
    <SourceOriginalPreview ref="sourceOriginalPreview" :field-id="managementFieldId" />
    <SourceUploadDialog ref="sourceUpload" :nodes="uploadNodes" :document-roles="documentRoles" @uploaded="sourcesUploaded" />
  </DrillingPageFrame>
</template>
<script setup>
import {computed,nextTick,onMounted,reactive,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {useElementSize} from '@vueuse/core'
import {ElMessage,ElMessageBox} from 'element-plus'
import useUserStore from '@/store/modules/user'
import {catalogTree,libraryNode,createLibraryNode,updateLibraryNode,archiveLibraryNode,libraryDeleteImpact,createLibraryRevision,activateLibraryRevision,attachRevisionData,libraryDocumentHistory,libraryRevision,updateLibraryFileStatus,listTargets,createTarget,updateTarget,archiveTarget,listTrajectoryTargetPoints,updateTrajectoryTargetPoint,archiveTrajectoryTargetPoint} from '@/api/engineeringLibrary'
import { fileDisplayName } from '@/utils/fileNames'
import { catalogPath, catalogCreationTarget, catalogTypeLabels, catalogAccess, catalogNodeWritable } from '@/utils/engineeringCatalog'
import { editFileDisplayName } from '../composables/fileDisplayName'
import {downloadSourceOriginal,downloadBlob,exportTrajectorySource,activateTrajectoryVersion,trajectoryVersion,saveTrajectoryStations} from '@/api/drillingRegistry'
import {createDrillingBlock,updateDrillingBlock,createDrillingField,updateDrillingField,archiveDrillingBlock,archiveDrillingField} from '@/api/drillingField'
import DrillingPageFrame from '../layout/DrillingPageFrame.vue'
import ResizableDialog from '@/components/ResizableDialog.vue'
import WellboreSelect from '../layout/WellboreSelect.vue'
import CatalogTrajectoryData from './survey/CatalogTrajectoryData.vue'
import DocumentContent from './survey/DocumentContent.vue'
import AppendixTable from './survey/AppendixTable.vue'
import {basicColumns,targetColumns,trajectoryColumns,targetPointColumns,appendixNotes,standardRows,sortMd} from '@/utils/appendixTables'
import {engineeringTableData,saveEngineeringBasic,engineeringFileImpact,deleteEngineeringDocument,createTrajectoryTargetPoint} from '@/api/engineeringLibrary'
import SourceOriginalPreview from './survey/SourceOriginalPreview.vue'
import SourceUploadDialog from './survey/SourceUploadDialog.vue'
import { listAccounts } from '@/api/access'
const catalogData=ref(),sourcePreview=ref(),sourceOriginalPreview=ref(),sourceUpload=ref(),reviewFieldId=ref(null),route=useRoute()
const nodeTarget=ref(null),editTarget=ref(null),catalogTarget=ref(null),revisionTarget=ref(null),dataLinkTarget=ref(null),targetOpen=ref(false),filesOpen=ref(false),targetEditId=ref(null),targetLoading=ref(false),targetSaving=ref(false),targetRows=ref([]),targetPointRows=ref([]),targetPointLoading=ref(false),targetPointVersionId=ref(null),targetPointOpen=ref(false),targetPointSaving=ref(false),targetPointEdit=ref(null),targetForm=reactive({name:'',type:'POINT',elevation:null,north:null,east:null,tvd:null,closureDistance:null,closureAzimuth:null,shape:'',radius:null,height:null,width:null,remark:''})
const statusLabels={UNPLANNED:'待规划',PLANNED:'已规划',DRILLING:'正在钻进',SUSPENDED:'暂停',COMPLETED:'已完成',ABANDONED:'废弃',UNKNOWN:'未填写'}
const catalogConstructionLabels={UNPLANNED:'未完成',COMPLETED:'完成',PLANNED:'已规划',DRILLING:'正在钻进',SUSPENDED:'暂停'}
const emptyCatalogForm=()=>({code:'',name:'',province:'',authorizedUser:'',coordinateSystem:'',wellheadNorth:'',wellheadEast:'',location:'',operatorName:'',structuralPosition:'',magneticDeclination:'',meridianConvergence:'',constructionStatus:'UNPLANNED',enabled:'1',remark:'',sourceReference:'',description:''})
const provinceOptions=['北京市','天津市','河北省','山西省','内蒙古自治区','辽宁省','吉林省','黑龙江省','上海市','江苏省','浙江省','安徽省','福建省','江西省','山东省','河南省','湖北省','湖南省','广东省','广西壮族自治区','海南省','重庆市','四川省','贵州省','云南省','西藏自治区','陕西省','甘肃省','青海省','宁夏回族自治区','新疆维吾尔自治区','香港特别行政区','澳门特别行政区','台湾省']
const coordinateOptions=[{label:'2000国家大地坐标系',value:'CGCS2000'},{label:'WGS-84坐标系',value:'WGS84'},{label:'西安80坐标系',value:'XIAN80'},{label:'北京54坐标系',value:'BEIJING54'},{label:'局部东—北—垂向坐标',value:'LOCAL_ENU'}]
const wellTypeOptions=[{label:'直井',value:'VERTICAL'},{label:'定向井',value:'DIRECTIONAL'},{label:'水平井',value:'HORIZONTAL'},{label:'分支井',value:'MULTILATERAL'},{label:'其他',value:'OTHER'}]

import {useDrillingContext} from '../composables/useDrillingContext'
const {fields,fieldId,loadFields}=useDrillingContext(),user=useUserStore(),loading=ref(false),contentLoading=ref(false),treeRef=ref(),treeKeyword=ref(''),activeTab=ref(route.query.tab||'basicData'),selectedNode=ref(null),nodes=ref([]),files=ref([]),revisions=ref([]),children=ref([]),trajectoryVersions=ref([]),inlineDetail=ref(null),fileHistory=ref([]),revisionDetail=ref(null),total=ref(0),query=reactive({pageNum:1,pageSize:20,keyword:'',role:'',trajectoryKind:'',sort:'date',order:'desc'}),nodeOpen=ref(false),editOpen=ref(false),catalogOpen=ref(false),revisionOpen=ref(false),fileHistoryOpen=ref(false),revisionDetailOpen=ref(false),dataLinkOpen=ref(false),saving=ref(false),catalogMode=ref('create'),catalogType=ref('field'),catalogForm=reactive(emptyCatalogForm()),nodeForm=reactive({nodeType:'WELL',name:'',code:'',parentId:null,fieldId:null,parentWellboreId:null,entityKind:'WELL',engineeringStatus:'UNPLANNED'}),editForm=reactive({name:'',code:'',status:'ACTIVE',expectedVersion:1,wellType:'',location:'',province:'',coordinateSystem:'LOCAL_ENU',horizontalDatum:'',wellheadN:null,wellheadE:null,wellheadTvd:null,magneticDeclination:null,meridianConvergence:null,operatorName:'',structuralPosition:'',remark:'',sourceReference:''}),revisionForm=reactive({businessRevision:'',outcomeName:'',phase:'PLAN',note:''}),selectedRevisionId=ref(null),selectedDataVersionId=ref(null),selectedDataLabel=ref(''),userOptions=ref([])
const stationColumns=[['md','MD（m）'],['inclination','井斜角（°）'],['azimuth','方位角（°）'],['tvd','垂深 TVD（m）'],['north','北坐标 N/S（m）'],['east','东坐标 E/W（m）'],['closure_distance','水平位移/闭合距（m）'],['closure_azimuth','水平位移方位/闭合方位（°）'],['horizontal_length','水平长度/视平长（m）'],['dogleg','井眼曲率（°/30m）'],['curvature','曲率（1/m）'],['remark','备注']]
const sortedInlineStations=computed(()=>[...(inlineDetail.value?.stations||[])].sort((a,b)=>Number(a.md)-Number(b.md)))
const contentRef=ref();const {width:contentWidth}=useElementSize(contentRef);const publicMetaColumns=computed(()=>contentWidth.value>1100?4:contentWidth.value>650?2:1)
const engineeringData=ref({basic:{}}),basicOpen=ref(false),basicForm=reactive({}),rowOpen=ref(false),rowForm=reactive({}),rowIndex=ref(-1),rowSaving=ref(false),fileStatusOpen=ref(false),fileStatusRow=ref(null),fileStatus=ref('ACTIVE')
const basicFields=basicColumns.flatMap(c=>c.children)
const canEditActual=computed(()=>access.value.writeData&&['DRILLING','SUSPENDED'].includes(selectedNode.value?.engineering_status))
const inlineRows=computed(()=>{
 const role=activeTab.value,data=engineeringData.value,detail=inlineDetail.value
 if(!detail)return []
 if(role==='designNodes'){const points=detail.stations||[];const explicit=points.filter(r=>r.is_key_node||(()=>{try{return JSON.parse(r.raw_json||'{}').is_key_node}catch{return false}})());return standardRows(data.design_nodes?.length?data.design_nodes:explicit.length?explicit:points.filter((_,i)=>i===0||i===points.length-1),detail.reference,data.design_nodes?.length?data.nodes_are_offsets!==false:false)}
 if(role==='surveyData'&&data.table_role==='SOURCE'&&data.survey?.length===(detail.stations||[]).length&&data.survey.every(r=>['north','east','tvd'].every(k=>r[k]!=null)))return standardRows(data.survey,detail.reference,true)
 if(role==='designDetails'&&data.table_role==='SOURCE'&&data.design_details?.length&&data.design_details.every(r=>['north','east','tvd'].every(k=>r[k]!=null)))return standardRows(data.design_details,detail.reference,true)
 return standardRows(detail.stations||[],detail.reference,false)
})
function appendixTitle(code,label){const name=engineeringData.value.well_name||selectedNode.value?.well_name||nodePath(selectedNode.value).find(n=>n.node_type==='WELL')?.node_name||'';return `表 ${code}  ${name}井${label}`.replace('井井','井')}
function canEditTrajectoryRow(row){if(!canEditInlineTrajectory.value)return false;return activeTab.value==='surveyData'||!['DRILLING','SUSPENDED'].includes(selectedNode.value?.engineering_status)||Number(row.md)>Number(engineeringData.value.drilled_md||0)}
function editTrajectoryRow(row,index){if(!canEditTrajectoryRow(row))return;rowIndex.value=index;for(const k of Object.keys(rowForm))delete rowForm[k];Object.assign(rowForm,row);rowOpen.value=true}
function addInlineRow(){const last=inlineRows.value.at(-1)||{};editTrajectoryRow({...last,md:Math.max(Number(last.md||0),Number(engineeringData.value.drilled_md||0))+30,remark:''},-1)}
async function persistTrajectoryRows(rows){
 const sorted=sortMd(rows),ref=inlineDetail.value.reference||{},n0=Number(ref.wellhead_north??ref.source_reference?.wellhead_north??ref.origin_north??0),e0=Number(ref.wellhead_east??ref.source_reference?.wellhead_east??ref.origin_east??0)
 const stations=sorted.map(r=>({...r,north:r.north==null?null:Number(r.north)+n0,east:r.east==null?null:Number(r.east)+e0}))
 await saveTrajectoryStations(managementFieldId.value,inlineDetail.value.survey_version_id,stations,'表格修订',{tableRole:activeTab.value,expectedCurrentVersionId:inlineDetail.value.survey_version_id})
 await loadContents()
}
async function saveTrajectoryRow(){if(rowSaving.value)return;if(!canEditTrajectoryRow(rowForm))return ElMessage.warning('该点已钻，不能修改设计数据');rowSaving.value=true;try{const rows=inlineRows.value.map(r=>({...r}));if(rowIndex.value<0)rows.push({...rowForm});else rows[rowIndex.value]={...rowForm};await persistTrajectoryRows(rows);rowOpen.value=false;ElMessage.success('已保存新数据版本')}catch(e){ElMessage.error(message(e))}finally{rowSaving.value=false}}
async function deleteTrajectoryRow(row,index){if(!canEditTrajectoryRow(row))return;try{await ElMessageBox.confirm(`删除井深 ${row.md} m 的测点并生成新版本？`,'删除测点',{type:'warning'});await persistTrajectoryRows(inlineRows.value.filter((_,i)=>i!==index));ElMessage.success('测点已删除')}catch(e){if(!['cancel','close'].includes(e))ElMessage.error(message(e))}}
function editBasic(){for(const k of Object.keys(basicForm))delete basicForm[k];Object.assign(basicForm,engineeringData.value.basic);basicOpen.value=true}
async function saveBasic(){rowSaving.value=true;try{await saveEngineeringBasic(managementFieldId.value,selectedNode.value.wellbore_id,{...basicForm});basicOpen.value=false;await loadContents();ElMessage.success('基础数据已保存')}catch(e){ElMessage.error(message(e))}finally{rowSaving.value=false}}
async function refreshBasic(){const id=selectedNode.value.node_id;nodes.value=await catalogTree();selectedNode.value=nodes.value.find(n=>Number(n.node_id)===Number(id))||selectedNode.value;await loadContents()}
async function saveTargetPointRecord(row,payload){return row.target_point_id?updateTrajectoryTargetPoint(managementFieldId.value,selectedNode.value.wellbore_id,row.target_point_id,payload):createTrajectoryTargetPoint(managementFieldId.value,selectedNode.value.wellbore_id,payload)}
function newTargetPoint(){targetPointEdit.value={target_no:'',md:null,inclination:null,azimuth:null,tvd:null,north:null,east:null,remark:''};targetPointOpen.value=true}
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
// 工程树只表达真实工程层级：区块→井场→井→井眼。
// 设计方案、实测测斜和文件属于井眼的数据页，不作为树节点重复展示。
const childTypes=computed(()=>({BLOCK:['FIELD'],FIELD:['WELL'],WELL:['WELLBORE'],WELLBORE:[],PLAN:[],SURVEY:[],INBOX:[]}[selectedNode.value?.node_type]||[]))
const childTabLabel=computed(()=>({BLOCK:'井场列表',FIELD:'井列表',WELL:'井眼列表',WELLBORE:''}[selectedNode.value?.node_type]||''))
const permissions=computed(()=>user.permissions||[]),hasPermission=key=>permissions.value.includes('*:*:*')||permissions.value.includes(key)
const managementFieldId=computed(()=>Number(selectedNode.value?.field_id)||null)
const canCreateBlock=computed(()=>hasPermission('drilling:block:add'))
const canEditNode=computed(()=>catalogNodeWritable(selectedNode.value)&&hasPermission({BLOCK:'drilling:block:edit',FIELD:'drilling:field:edit'}[selectedNode.value.node_type]||'drilling:well:edit'))
const canAddNode=computed(()=>childTypes.value.length>0&&catalogNodeWritable(selectedNode.value)&&hasPermission(selectedNode.value?.node_type==='BLOCK'?'drilling:field:add':'drilling:well:add'))
const addNodeLabel=computed(()=>({BLOCK:'新增井场',FIELD:'新增井',WELL:'新增井眼',WELLBORE:'添加资料'}[selectedNode.value?.node_type]||''))
const access=computed(()=>catalogAccess(selectedNode.value,permissions.value))
const canDeleteSelected=computed(()=>!!selectedNode.value&&catalogNodeWritable(selectedNode.value)&&hasPermission(selectedNode.value.node_type==='BLOCK'?'drilling:block:remove':selectedNode.value.node_type==='FIELD'?'drilling:field:remove':'drilling:well:remove'))
const canDeleteNode=row=>Number(row?.node_id)>0&&catalogNodeWritable(row)&&hasPermission('drilling:well:remove')
const displayedTrajectoryVersions=computed(()=>{const rows=activeTab.value==='surveyData'?trajectoryVersions.value.filter(row=>row.trajectory_kind==='ACTUAL'):trajectoryVersions.value.filter(row=>row.trajectory_kind==='DESIGN');return [...rows].sort((a,b)=>Number(b.is_current)-Number(a.is_current)||Number(b.source_review_status==='CONFIRMED')-Number(a.source_review_status==='CONFIRMED')||Number(b.quality_status==='READY')-Number(a.quality_status==='READY')||Number(b.version_no||0)-Number(a.version_no||0))})
const actualVersions=computed(()=>trajectoryVersions.value.filter(row=>row.trajectory_kind==='ACTUAL').sort((a,b)=>Number(b.is_current)-Number(a.is_current)||Number(b.version_no||0)-Number(a.version_no||0)))
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
    const bore=cleanSourceText(node.wellbore_name||node.wellbore_code||node.node_name||node.node_code)
    return bore||'主井眼'
  }
  if(type==='PLAN')return cleanSourceText(node.node_name)||'设计方案'
  if(type==='SURVEY')return cleanSourceText(node.node_name)||'实钻测斜资料'
  if(type==='INBOX'&&['项目级资料','工程资料'].includes(node.node_name))return '井场资料'
  return cleanSourceText(node?.node_name)||nodeTypeLabels[type]||'工程节点'
}
function catalogDisplayFields(node){
  if(!node||!['BLOCK','FIELD'].includes(node.node_type))return []
  const field=node.node_type==='BLOCK'
  const value=(key,fallback='')=>node[key] ?? fallback
  return [
    {key:'name',label:field?'区块名称':'井场名称',value:nodeLabel(node)},
    {key:'code',label:field?'区块编码':'井场编码',value:node.node_code},
    {key:'province',label:'省份',value:value('province')},
    {key:'authorized_user',label:'授权用户',value:value('authorized_user')},
    {key:'coordinate_system',label:'井位坐标系',value:value('coordinate_system')},
    {key:'wellhead_e',label:'井口横坐标',value:value('wellhead_e')},
    {key:'wellhead_n',label:'井口纵坐标',value:value('wellhead_n')},
    {key:'operator_name',label:'作业单位',value:value('operator_name')},
    {key:'location',label:'区块位置',value:value('location')},
    {key:'structural_position',label:'所属构造',value:value('structural_position')},
    {key:'construction_status',label:'施工状态',value:catalogConstructionLabels[value('construction_status','UNPLANNED')]||value('construction_status')},
    {key:'enabled',label:'是否启用',value:String(value('enabled','1'))==='1'?'是':'否'},
    {key:'magnetic_declination',label:'磁偏角（°）',value:value('magnetic_declination')},
    {key:'meridian_convergence',label:'收敛角（°）',value:value('meridian_convergence')},
    {key:'description',label:'说明',value:value('description')},
    {key:'remark',label:'备注',value:value('remark')},
    {key:'source_reference',label:'基础资料来源',value:value('source_reference')},
    {key:'status',label:'资料状态',value:nodeStateLabel(node.status)}
  ]
}
function wellDisplayFields(node){
  const value=(key,fallback='')=>node?.[key] ?? fallback
  const metadata=node?.well_metadata||{}
  return [
    {key:'name',label:'井名',value:nodeLabel(node)},
    {key:'code',label:'井号',value:node?.well_code||node?.node_code},
    {key:'well_type',label:'井型',value:value('well_type')},
    {key:'location',label:'井位置',value:value('well_location')||value('location')},
    {key:'province',label:'省份',value:metadata.province},
    {key:'coordinate_system',label:'坐标系',value:metadata.coordinate_system},
    {key:'horizontal_datum',label:'井口横坐标基准',value:metadata.horizontal_datum},
    {key:'wellhead_n',label:'井口纵坐标（m）',value:value('wellhead_n')},
    {key:'wellhead_e',label:'井口横坐标（m）',value:value('wellhead_e')},
    {key:'wellhead_tvd',label:'井口垂深（m）',value:value('wellhead_tvd')},
    {key:'magnetic_declination',label:'磁偏角（°）',value:value('magnetic_declination')},
    {key:'meridian_convergence',label:'子午线收敛角（°）',value:value('meridian_convergence')},
    {key:'operator_name',label:'作业单位',value:value('well_operator_name')||value('operator_name')},
    {key:'structural_position',label:'所属构造',value:value('well_structural_position')||value('structural_position')},
    {key:'well_status',label:'施工状态',value:statusLabels[node?.well_status]||node?.well_status||'未填写'},
    {key:'status',label:'资料状态',value:nodeStateLabel(node?.status)},
    {key:'remark',label:'备注',value:value('well_remark')},
    {key:'source_reference',label:'基础资料来源',value:value('well_source_reference')}
  ]
}
const hiddenSourceRoots=['采气二厂煤岩气完井资料+设计（11口）','采气二厂煤岩气完井资料+设计(11口)','Volve数据集','well_control_iscwsa'];
function cleanSourceText(value){let text=String(value||'');for(const root of hiddenSourceRoots)text=text.replaceAll(root,'');text=text.replace(/^[/\\]+/,'').replaceAll('\\','/');return text}
const displayFileName=fileDisplayName
function displaySourcePath(row){const value=cleanSourceText(row?.source_path||'');return value.split('/').pop()||'来源文件'}
function trajectoryLabel(row){return [row?.block_name,row?.field_name,row?.well_name,row?.wellbore_name].map(cleanSourceText).filter(Boolean).join(' / ')||humanNodeLabel(row?.wellbore_name||row?.wellbore_code)}
function humanNodeLabel(value){return cleanSourceText(value)||'井眼'}
const treeData=computed(()=>{const visible=nodes.value.filter(n=>!['PLAN','SURVEY','INBOX'].includes(n.node_type));const rows=visible.map(n=>({...n,children:[]}));const map=new Map(rows.map(n=>[Number(n.node_id),n]));const roots=[];rows.forEach(n=>{if(n.parent_id&&Number(n.parent_id)!==Number(n.node_id)&&map.has(Number(n.parent_id)))map.get(Number(n.parent_id)).children.push(n);else roots.push(n)});return roots})
function nodePath(node){return catalogPath(node,nodes.value)}
const trajectoryWellbores=computed(()=>nodes.value.filter(n=>n.node_type==='WELLBORE').map(n=>{
  const path=nodePath(n),block=path.find(p=>p.node_type==='BLOCK'),field=path.find(p=>p.node_type==='FIELD'),well=path.find(p=>p.node_type==='WELL')
  return {...n,block_id:block?.block_id,block_name:block?nodeLabel(block):'',field_name:field?nodeLabel(field):'',well_name:well?nodeLabel(well):'',wellbore_name:nodeLabel(n)}
}))
const breadcrumbs=computed(()=>nodePath(selectedNode.value));const message=e=>e?.response?.data?.msg||e?.message||'操作失败'
async function loadOptions(){
  try{
    const response=await listAccounts({pageNum:1,pageSize:100,status:'0'})
    userOptions.value=(response?.rows||[]).map(item=>({value:item.loginName,label:item.userName?`${item.userName}（${item.loginName}）`:item.loginName})).filter(item=>item.value)
  }catch{userOptions.value=[]}
}
watch(treeKeyword,v=>treeRef.value?.filter(v));onMounted(()=>{loadOptions();load()})
function iconFor(type){return {BLOCK:'MapLocation',FIELD:'Location',INBOX:'Folder',WELL:'OfficeBuilding',WELLBORE:'Connection',PLAN:'Document',SURVEY:'Tickets'}[type]||'Document'}function filterNode(v,d){return !v||`${d.node_name} ${d.node_code||''}`.toLowerCase().includes(v.toLowerCase())}
async function load(){loading.value=true;try{if(!fields.value.length)await loadFields({basicOnly:true});nodes.value=await catalogTree()||[];const visibleNodes=nodes.value.filter(n=>!['PLAN','SURVEY','INBOX'].includes(n.node_type));const active=fields.value.find(p=>Number(p.field_id)===Number(fieldId.value));const current=visibleNodes.find(n=>Number(n.node_id)===Number(selectedNode.value?.node_id))||visibleNodes.find(n=>n.scope_type==='FIELD'&&Number(n.field_id)===Number(active?.field_id))||visibleNodes.find(n=>n.scope_type==='FIELD')||visibleNodes[0];if(current){selectedNode.value=current;if(!['basicData','targetData','designNodes','designDetails','surveyData','targetPoints','files','children'].includes(activeTab.value))activeTab.value='basicData';await loadContents()}else{selectedNode.value=null;files.value=[];revisions.value=[];children.value=[];trajectoryVersions.value=[]}}catch(e){ElMessage.error(message(e))}finally{loading.value=false}}
let contentsRequest=0
async function loadContents(){
 const ticket=++contentsRequest,n=selectedNode.value;if(!n)return
 contentLoading.value=true;inlineDetail.value=null;engineeringData.value={basic:{}}
 try{
  if(n.node_type==='BLOCK'){children.value=nodes.value.filter(x=>Number(x.parent_id)===Number(n.node_id));files.value=[];trajectoryVersions.value=[];return}
  const p=Number(n.field_id);if(!p)return
  const r=await libraryNode(p,n.node_id,query);if(ticket!==contentsRequest)return
  files.value=r.files||[];revisions.value=r.revisions||[];children.value=nodes.value.filter(x=>Number(x.parent_id)===Number(n.node_id)&&childTypes.value.includes(x.node_type));trajectoryVersions.value=r.trajectoryVersions||[];total.value=r.total||0
  if(n.node_type==='WELLBORE'){
   const kind=activeTab.value==='surveyData'?'ACTUAL':(['designNodes','designDetails'].includes(activeTab.value)?'DESIGN':'')
   const current=trajectoryVersions.value.filter(x=>x.trajectory_kind===kind).sort((a,b)=>Number(b.is_current)-Number(a.is_current)||Number(b.version_no)-Number(a.version_no))[0]
   const [data,detail]=await Promise.all([engineeringTableData(p,n.wellbore_id,current?.survey_version_id),current?trajectoryVersion(p,current.survey_version_id):null]);if(ticket!==contentsRequest)return
   engineeringData.value=data;inlineDetail.value=detail
   if(activeTab.value==='targetData')await loadTargets()
   if(activeTab.value==='targetPoints')await loadTargetPoints()
  }
 }catch(e){if(ticket===contentsRequest)ElMessage.error(message(e))}finally{if(ticket===contentsRequest)contentLoading.value=false}
}
function search(){query.pageNum=1;loadContents()}
let targetRequest=0,targetPointRequest=0
async function loadTargets(){const p=managementFieldId.value,b=selectedNode.value?.wellbore_id;if(!p||!b)return;const request=++targetRequest;targetLoading.value=true;try{const rows=await listTargets(p,b);if(request===targetRequest&&b===selectedNode.value?.wellbore_id)targetRows.value=rows||[]}catch(e){if(b===selectedNode.value?.wellbore_id){targetRows.value=[];ElMessage.error(message(e))}}finally{if(request===targetRequest)targetLoading.value=false}}
async function loadTargetPoints(){const p=managementFieldId.value,b=selectedNode.value?.wellbore_id;if(!p||!b)return;const request=++targetPointRequest;targetPointLoading.value=true;try{if(!actualVersions.value.some(v=>Number(v.survey_version_id)===Number(targetPointVersionId.value)))targetPointVersionId.value=actualVersions.value.find(v=>Number(v.is_current))?.survey_version_id||actualVersions.value[0]?.survey_version_id||null;const version=targetPointVersionId.value,rows=version?await listTrajectoryTargetPoints(p,b,version):[];if(request===targetPointRequest&&b===selectedNode.value?.wellbore_id)targetPointRows.value=rows||[]}catch(e){if(b===selectedNode.value?.wellbore_id){targetPointRows.value=[];ElMessage.error(message(e))}}finally{if(request===targetPointRequest)targetPointLoading.value=false}}

function formatStationValue(value){if(value==null||value==='')return '—';if(typeof value==='string'&&!/^[-+]?\d+(\.\d+)?$/.test(value))return value;const n=Number(value);return Number.isFinite(n)?n.toFixed(3):String(value)}
const canEditInlineTrajectory=computed(()=>{if(!inlineDetail.value||!selectedNode.value?.wellbore_id)return false;const status=selectedNode.value.engineering_status;const allowed=inlineDetail.value.trajectory_kind==='ACTUAL'?['DRILLING','SUSPENDED'].includes(status):['UNPLANNED','PLANNED','DRILLING','SUSPENDED'].includes(status);return canWriteTrajectory({wellbore_id:selectedNode.value.wellbore_id})&&allowed})
async function exportCurrentTrajectory(){const row=displayedTrajectoryVersions.value.find(v=>Number(v.is_current))||displayedTrajectoryVersions.value[0];if(row){const role=inlineDetail.value?.reference?.engineering_table_role||'SOURCE';await exportSource(row,role==='SOURCE'?'ORIGINAL':'PATCHED')}}
function showCurrentTrajectoryVisual(){const row=displayedTrajectoryVersions.value.find(v=>Number(v.is_current))||displayedTrajectoryVersions.value[0];if(row)catalogData.value?.show(row,{visualOnly:true})}
async function openWellboreFiles(){filesOpen.value=true;const previous=activeTab.value;activeTab.value='files';await loadContents();activeTab.value=previous}
function showFiles(){filesOpen.value=true}
function editTargetPoint(row){targetPointEdit.value={...row};targetPointOpen.value=true}
async function saveTargetPoint(){if(!targetPointEdit.value)return;targetPointSaving.value=true;try{await saveTargetPointRecord(targetPointEdit.value,{versionId:targetPointVersionId.value,targetNo:targetPointEdit.value.target_no,md:targetPointEdit.value.md,inclination:targetPointEdit.value.inclination,azimuth:targetPointEdit.value.azimuth,tvd:targetPointEdit.value.tvd,north:targetPointEdit.value.north,east:targetPointEdit.value.east,closure_distance:targetPointEdit.value.closure_distance,closure_azimuth:targetPointEdit.value.closure_azimuth,offset:targetPointEdit.value.offset,longitudinal_offset:targetPointEdit.value.longitudinal_offset,lateral_offset:targetPointEdit.value.lateral_offset,remark:targetPointEdit.value.remark});targetPointOpen.value=false;await loadTargetPoints();ElMessage.success('入靶数据已保存')}catch(e){ElMessage.error(message(e))}finally{targetPointSaving.value=false}}
async function deleteTargetPoint(row){try{await ElMessageBox.confirm(`确定删除入靶数据“${row.target_no}”？`,'删除入靶数据',{type:'warning'});await archiveTrajectoryTargetPoint(managementFieldId.value,selectedNode.value.wellbore_id,row.target_point_id,targetPointVersionId.value);await loadTargetPoints();ElMessage.success('入靶数据已删除')}catch(e){if(e!=='cancel')ElMessage.error(message(e))}}
function geometryObject(row){let value=row?.geometry;try{if(typeof value==='string')value=JSON.parse(value)}catch{value={}};return value||{}}
function targetNumber(value){return value==null||value===''?null:Number(value)}
function openTargetDialog(row=null){const g=geometryObject(row),t=typeof row?.tolerance==='string'?(()=>{try{return JSON.parse(row.tolerance)}catch{return {}}})():row?.tolerance||{};targetEditId.value=row?.target_id||null;Object.assign(targetForm,{name:row?.target_name||'',type:row?.target_type||'POINT',elevation:targetNumber(g.elevation),north:targetNumber(g.north),east:targetNumber(g.east),tvd:targetNumber(g.tvd),closureDistance:targetNumber(g.closure_distance),closureAzimuth:targetNumber(g.closure_azimuth),shape:g.shape||'',radius:targetNumber(g.radius??t.radius),height:targetNumber(g.height),width:targetNumber(g.width),remark:g.remark||''});targetOpen.value=true}
async function saveTarget(){if(!targetForm.name.trim())return ElMessage.warning('请输入靶区名称');const geometry={elevation:targetForm.elevation,north:targetForm.north,east:targetForm.east,tvd:targetForm.tvd,closure_distance:targetForm.closureDistance,closure_azimuth:targetForm.closureAzimuth,shape:targetForm.shape,radius:targetForm.radius,height:targetForm.height,width:targetForm.width,remark:targetForm.remark};const tolerance={radius:targetForm.radius};targetSaving.value=true;try{if(targetEditId.value)await updateTarget(managementFieldId.value,targetEditId.value,{name:targetForm.name.trim(),type:targetForm.type,geometry,tolerance});else await createTarget(managementFieldId.value,selectedNode.value.wellbore_id,{name:targetForm.name.trim(),type:targetForm.type,geometry,tolerance});targetOpen.value=false;await loadTargets();ElMessage.success('靶区数据已保存')}catch(e){ElMessage.error(message(e))}finally{targetSaving.value=false}}
async function commandTarget(command,row){if(command==='edit')return openTargetDialog(row);if(command==='delete'){try{await ElMessageBox.confirm(`确定删除“${row.target_name}”？`,'删除靶区',{type:'warning'});await archiveTarget(managementFieldId.value,row.target_id);await loadTargets();ElMessage.success('靶区已删除')}catch(e){if(e!=='cancel')ElMessage.error(message(e))}}}
async function commandTrajectory(command,row){if(command==='data'||command==='visual')return showTrajectoryVersion(row);if(command==='original')return exportSource(row,'ORIGINAL');if(command==='source')return exportSource(row,'PATCHED');if(command==='current')return activateTrajectory(row);if(command==='edit')return catalogData.value?.openDesignEditor(row)}
function commandFile(command,row){if(command==='rename')return editFileDisplayName(managementFieldId.value,row,loadContents);if(command==='preview')return showSourceContent(row);if(command==='status')return changeFileStatus(row);if(command==='delete')return deleteFile(row)}
async function changeFileStatus(row){fileStatusRow.value=row;fileStatus.value=row.file_usage_state||'ACTIVE';fileStatusOpen.value=true}
async function saveFileStatus(){try{const impact=await engineeringFileImpact(managementFieldId.value,fileStatusRow.value.document_id);if(fileStatus.value!=='ACTIVE'&&impact.blocked){await ElMessageBox.alert(impact.reason,'无法修改状态',{type:'warning'});return}await updateLibraryFileStatus(managementFieldId.value,fileStatusRow.value.document_id,fileStatus.value);fileStatusOpen.value=false;await loadContents();ElMessage.success('文件状态已修改')}catch(e){ElMessage.error(message(e))}}
async function deleteFile(row){try{const impact=await engineeringFileImpact(managementFieldId.value,row.document_id);if(impact.blocked){await ElMessageBox.alert(impact.reason,'无法删除文件',{type:'warning'});return}await ElMessageBox.confirm(`删除“${displayFileName(row)}”及其文件版次的显示记录？原件将保留归档。`,'删除文件',{type:'warning'});await deleteEngineeringDocument(managementFieldId.value,row.document_id);await loadContents();ElMessage.success('文件已删除')}catch(e){if(!['cancel','close'].includes(e))ElMessage.error(message(e))}}
async function selectNode(n){
  selectedNode.value=n;query.pageNum=1;query.keyword='';query.role='';query.trajectoryKind='';targetPointVersionId.value=null;targetPointRows.value=[]
  activeTab.value=['BLOCK','FIELD','WELL','WELLBORE'].includes(n.node_type)?'basicData':'files'
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
      Object.assign(catalogForm,emptyCatalogForm());catalogOpen.value=true;return
    }
    nodeTarget.value=target
    Object.assign(nodeForm,{nodeType:target.nodeType,name:'',code:'',parentId:target.parentId,fieldId:target.fieldId,parentWellboreId:null,entityKind:'WELL',engineeringStatus:'UNPLANNED'})
    nodeOpen.value=true
  }catch(e){ElMessage.warning(message(e))}
}
function openBlockDialog(){catalogTarget.value=null;catalogMode.value='create';catalogType.value='block';Object.assign(catalogForm,emptyCatalogForm());catalogOpen.value=true}
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
    Object.assign(catalogForm,{...emptyCatalogForm(),code:n.node_code||'',name:n.node_name||'',province:n.province||'',authorizedUser:n.authorized_user||'',coordinateSystem:n.coordinate_system||'',wellheadNorth:n.wellhead_n??'',wellheadEast:n.wellhead_e??'',location:n.location||'',operatorName:n.operator_name||'',structuralPosition:n.structural_position||'',magneticDeclination:n.magnetic_declination??'',meridianConvergence:n.meridian_convergence??'',constructionStatus:n.construction_status||'UNPLANNED',enabled:n.enabled==null?'1':String(n.enabled),remark:n.remark||'',sourceReference:n.source_reference||'',description:n.description||''});catalogOpen.value=true;return
  }
  editTarget.value={...n};Object.assign(editForm,{name:nodeLabel(n),code:n.node_code||n.wellbore_code||'',status:n.status,expectedVersion:n.row_version,engineeringStatus:n.engineering_status||'UNKNOWN',wellType:n.well_type||'',location:n.well_location||n.location||'',province:n.well_metadata?.province||'',coordinateSystem:n.well_metadata?.coordinate_system||'LOCAL_ENU',horizontalDatum:n.well_metadata?.horizontal_datum||'',wellheadN:n.wellhead_n??null,wellheadE:n.wellhead_e??null,wellheadTvd:n.wellhead_tvd??null,magneticDeclination:n.magnetic_declination??null,meridianConvergence:n.meridian_convergence??null,operatorName:n.well_operator_name||n.operator_name||'',structuralPosition:n.well_structural_position||n.structural_position||'',remark:n.well_remark||n.remark||'',sourceReference:n.well_source_reference||n.source_reference||''});editOpen.value=true
}
async function saveEdit(){
  if(saving.value||!editTarget.value)return
  if(!editForm.name.trim())return ElMessage.warning('请输入名称')
  saving.value=true
  try{
    const target=editTarget.value,{engineeringStatus,code,...payload}=editForm
    if(target.node_type==='WELLBORE'&&!code?.trim())return ElMessage.warning('请输入井眼编码')
    if(target.node_type==='WELLBORE')payload.code=code
    if(target.node_type==='WELLBORE')payload.engineeringStatus=engineeringStatus
    await updateLibraryNode(target.field_id,target.node_id,payload);editOpen.value=false;await load();ElMessage.success('已保存')
  }catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
async function saveCatalog(){
  if(saving.value)return
  const required=[['code','编码'],['name',catalogType.value==='block'?'区块名称':'井场名称'],['province','省份'],['authorizedUser','授权用户'],['coordinateSystem','井位坐标系'],['wellheadNorth','井口纵坐标'],['wellheadEast','井口横坐标']]
  const missing=required.find(([key])=>String(catalogForm[key]??'').trim()==='')
  if(missing)return ElMessage.warning(`请填写${missing[1]}`)
  saving.value=true
  try{
    const target=catalogTarget.value,n=target?.node
    if(catalogMode.value==='create'){
      if(catalogType.value==='block')await createDrillingBlock({blockCode:catalogForm.code,blockName:catalogForm.name,...catalogForm})
      else {if(!target?.blockId)throw new Error('请先选择所属区块');await createDrillingField({blockId:target.blockId,fieldCode:catalogForm.code,fieldName:catalogForm.name,...catalogForm})}
    }else if(catalogType.value==='block')await updateDrillingBlock(n.block_id,{blockName:catalogForm.name,...catalogForm})
    else await updateDrillingField(n.field_id,{fieldName:catalogForm.name,...catalogForm})
    catalogOpen.value=false;await loadFields({basicOnly:true});await load();ElMessage.success('已保存')
  }catch(e){ElMessage.error(message(e))}finally{saving.value=false}
}
async function deleteNode(row){
  if(!canDeleteNode(row))return
  try{const impact=await libraryDeleteImpact(Number(row.field_id),Number(row.node_id));if(impact.blocked)throw new Error(impact.blockReason||'该工程节点禁止删除');await ElMessageBox.confirm(`删除“${nodeLabel(row)}”将隐藏 ${impact.descendantCount||0} 个下级节点、${impact.wellCount||0} 口井及其文件、轨迹和关联数据；原始文件仍保留在归档中。是否继续？`,'删除工程节点',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'});await archiveLibraryNode(Number(row.field_id),Number(row.node_id));await load();ElMessage.success('工程节点已删除')}catch(e){if(e!=='cancel')ElMessage.error(message(e))}
}
async function deleteSelectedNode(){
  const n=selectedNode.value;if(!n)return
  try{
    let impact={descendantCount:0,wellCount:0,blocked:false,blockReason:''};if(n.node_type==='WELLBORE'||n.node_type==='WELL'||n.node_type==='PLAN'||n.node_type==='SURVEY')impact=await libraryDeleteImpact(Number(n.field_id),Number(n.node_id));if(impact.blocked)throw new Error(impact.blockReason);await ElMessageBox.confirm(`删除“${nodeLabel(n)}”将隐藏 ${impact.descendantCount||0} 个下级节点、${impact.wellCount||0} 口井及其文件、轨迹和关联数据；原始文件仍保留在归档中。是否继续？`,'删除工程项目',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'})
    if(n.node_type==='BLOCK')await archiveDrillingBlock(n.block_id)
    else if(n.node_type==='FIELD')await archiveDrillingField(n.field_id)
    else await archiveLibraryNode(Number(n.field_id),Number(n.node_id))
    selectedNode.value=null;await loadFields({basicOnly:true});await load();ElMessage.success('工程项目已删除')
  }catch(e){if(e!=='cancel')ElMessage.error(message(e))}
}
const uploadNodes=computed(()=>nodes.value.map(node=>({...node,label:nodeLabel(node),engineering_path:nodePath(node).map(nodeLabel).join(' / ')})))
async function sourcesUploaded(rows){
  if(!rows.length)return
  const target=rows[0].target,targetFieldId=Number(target.field_id)
  nodes.value=await catalogTree()||[]
  selectedNode.value=nodes.value.find(n=>Number(n.node_id)===Number(target.node_id))||target
  activeTab.value='files';query.pageNum=1;query.keyword=''
  await loadContents();reviewFieldId.value=targetFieldId
}
async function showFileHistory(row){try{fileHistory.value=await libraryDocumentHistory(managementFieldId.value,row.document_id)||[];fileHistoryOpen.value=true}catch(e){ElMessage.error(message(e))}}




function fileStateLabel(value){return {DRAFT:'草稿',HISTORICAL:'历史资料',ACTIVE:'使用中',WITHDRAWN:'已撤回',FORBIDDEN:'禁用',UNASSIGNED:'未指定'}[value]||'—'}
function trajectoryKindLabel(value){return {DESIGN:'设计轨道',ACTUAL:'实钻测斜'}[value]||'—'}
function phaseLabel(value){return {PLAN:'设计',DESIGN:'设计',ACTUAL:'实钻',PROTOTYPE:'方案草稿',DOCUMENT:'工程资料'}[value]||value||'—'}
function numberText(value){return value==null?'—':Number(value).toFixed(2)}
function showTrajectoryVersion(row){catalogData.value.show(row)}
function canPatchSource(row){const name=String(row.original_name||'').toLowerCase();return /\.(xlsx|xlsm|xls|docx|csv|tsv|txt|asc|dat|mwd|survey|xml|witsml)$/.test(name)||!name.includes('.')}
function trajectoryExportName(row){const original=String(row.original_name||'trajectory');const dot=original.lastIndexOf('.');const suffix=dot>0&&dot<original.length-1?original.slice(dot):'';const well=String(row.well_name||row.wellbore_name||row.wellbore_code||'井眼').replace(/[\\/:*?"<>|]/g,'_');const type=row.trajectory_kind==='DESIGN'?'设计方案':'实测测斜';return `${well}_${type}_${row.version_no||1}${suffix}`}
async function exportSource(row,mode){try{const blob=await exportTrajectorySource(managementFieldId.value,row.survey_version_id,mode,activeTab.value);downloadBlob(blob,trajectoryExportName(row))}catch(e){ElMessage.error(message(e))}}
function showSourceContent(row){sourceOriginalPreview.value?.show(row)}
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
.inline-trajectory-table{margin:0 0 18px;border:1px solid var(--el-border-color-light);padding:10px;background:var(--el-bg-color)}.inline-table-title{font-size:13px;font-weight:600;color:var(--el-text-color-primary);margin-bottom:8px}
.library-page{min-height:650px}.library-layout{display:grid;grid-template-columns:280px minmax(0,1fr);min-height:620px;border:1px solid var(--el-border-color-light);background:var(--el-bg-color)}
.library-tree-panel{border-right:1px solid var(--el-border-color-light);padding:14px;overflow:auto;max-height:max(480px,calc(100vh - 190px))}
.tree-title{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-bottom:12px;color:var(--el-text-color-primary);font-weight:600}.tree-title>span{white-space:nowrap}.tree-title .el-input{flex-basis:100%}
.tree-node{display:flex;align-items:center;gap:6px;min-width:0;font-size:13px}.tree-node>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.library-content{padding:18px;min-width:0}.content-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:16px}.content-heading :deep(.el-breadcrumb){line-height:1.8}.content-heading :deep(.el-breadcrumb__item){float:none;display:inline}
.basic-data-summary{margin-bottom:18px}.basic-data-title{margin:18px 0 10px;color:var(--el-text-color-primary);font-size:14px}
.wellbore-public-meta{display:flex;align-items:flex-start;gap:14px;margin:0 0 12px}.wellbore-public-meta :deep(.el-descriptions){flex:1;min-width:0}.wellbore-public-meta :deep(.el-descriptions__label){white-space:nowrap}.wellbore-public-meta :deep(.el-descriptions__content){word-break:break-word}.wellbore-public-actions{display:flex;gap:8px;white-space:nowrap}
.node-actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end;flex-shrink:0}.library-tabs{margin-bottom:12px}.content-section{min-width:0}
.attachment-location{margin-bottom:16px;line-height:1.7;color:var(--el-text-color-regular)}
.section-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.section-toolbar .el-form{margin-bottom:0}.section-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.library-content :deep(.el-table){width:100%}
@media(max-width:1100px){.content-heading,.section-toolbar{align-items:flex-start;flex-wrap:wrap}.section-actions{padding-bottom:4px}}
@media(max-width:900px){.library-layout{grid-template-columns:1fr}.library-tree-panel{max-height:260px;border-right:0;border-bottom:1px solid var(--el-border-color-light)}.library-content{padding:12px}.node-actions{justify-content:flex-start}.section-toolbar :deep(.el-form-item){margin-right:10px}.section-toolbar :deep(.el-input){max-width:100%}}
</style>
