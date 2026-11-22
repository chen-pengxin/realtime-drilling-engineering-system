import { createWebHistory, createRouter } from 'vue-router'
/* Layout */
import Layout from '@/layout'
import ParentView from '@/components/ParentView'

/**
 * Note: 路由配置项
 *
 * hidden: true                     // 当设置 true 的时候该路由不会再侧边栏出现 如401，login等页面，或者如一些编辑页面/edit/1
 * alwaysShow: true                 // 当你一个路由下面的 children 声明的路由大于1个时，自动会变成嵌套的模式--如组件页面
 *                                  // 只有一个时，会将那个子路由当做根路由显示在侧边栏--如引导页面
 *                                  // 若你想不管路由下面的 children 声明的个数都显示你的根路由
 *                                  // 你可以设置 alwaysShow: true，这样它就会忽略之前定义的规则，一直显示根路由
 * redirect: noRedirect             // 当设置 noRedirect 的时候该路由在面包屑导航中不可被点击
 * name:'router-name'               // 设定路由的名字，一定要填写不然使用<keep-alive>时会出现各种问题
 * query: '{"id": 1, "name": "ry"}' // 访问路由的默认传递参数
 * roles: ['admin', 'common']       // 访问路由的角色权限
 * permissions: ['a:a:a', 'b:b:b']  // 访问路由的菜单权限
 * meta : {
    noCache: true                   // 如果设置为true，则不会被 <keep-alive> 缓存(默认 false)
    title: 'title'                  // 设置该路由在侧边栏和面包屑中展示的名字
    icon: 'svg-name'                // 设置该路由的图标，对应路径src/assets/icons/svg
    breadcrumb: false               // 如果设置为false，则不会在breadcrumb面包屑中显示
    activeMenu: '/system/user'      // 当路由设置了该属性，则会高亮相对应的侧边栏。
  }
 */

// 公共路由
export const constantRoutes = [
  {
    path: '/drilling',
    component: Layout,
    redirect: '/drilling/realtime',
    alwaysShow: true,
    meta: { title: '钻井工程', icon: 'chart' },
    children: [
      { path: 'realtime', component: () => import('@/views/drilling/basic/RealtimeHomePage.vue'), name: 'DrillingRealtimeHome', meta: { title: '实时大屏', icon: 'monitor', modules:['realtime','alerts'] } },
      {
        path: 'tasks',
        component: ParentView,
        alwaysShow: true,
        meta: { title: '任务中心', icon: 'log' },
        children: [
          { path: 'anticollision', component: () => import('@/views/drilling/tasks/anticollision/index.vue'), name: 'DrillingAntiCollision', meta: { title: '防碰评价', icon: 'form' } },
          { path: 'prediction', component: () => import('@/views/drilling/tasks/prediction/index.vue'), name: 'DrillingPrediction', redirect: '/drilling/tasks/prediction/overview', alwaysShow: true, meta: { title: '井眼轨迹预测', icon: 'job' }, children: [
            { path: 'overview', component: () => import('@/views/drilling/tasks/prediction/PredictionWorkspace.vue'), name: 'DrillingPredictionOverview', meta: { title: '预测展示', icon: 'monitor' } },
            { path: 'models', component: () => import('@/views/drilling/tasks/prediction/ModelRepositoryPage.vue'), name: 'DrillingPredictionModels', meta: { title: '模型仓库', icon: 'table' } }
          ] },
          { path: 'replanning', component: () => import('@/views/drilling/tasks/replanning/index.vue'), name: 'DrillingReplanning', meta: { title: '轨迹再规划', icon: 'edit' } }
        ]
      },
      {
        path: 'basic',
        component: ParentView,
        alwaysShow: true,
        meta: { title: '基础功能', icon: 'tool' },
        children: [
          { path: 'catalog', component: () => import('@/views/drilling/basic/CatalogPage.vue'), name: 'DrillingCatalog', meta: { title: '工程项目管理', icon: 'tree', modules:['catalog','surveys'] } },
          { path: 'surveys', hidden: true, redirect: to => ({path:'/drilling/basic/catalog',query:{...to.query,tab:'surveyData'}}) },
          { path: 'realtime-data', component: () => import('@/views/drilling/basic/RealtimeDashboardPage.vue'), name: 'DrillingRealtimeData', meta: { title: '实时数据接入', icon: 'link', modules:['realtime-data','connectors'] } },
          { path: 'config', component: () => import('@/views/drilling/basic/ConfigPage.vue'), name: 'DrillingConfig', meta: { title: '工程参数管理', icon: 'edit' } }
        ]
      }
    ]
  },
  ...Object.entries({alerts:['/drilling/realtime','alerts'],connectors:['/drilling/basic/realtime-data','connectors']}).map(([name,[path,tab]])=>({path:`/drilling/centers/${name}`,hidden:true,redirect:to=>({path,query:{...to.query,tab}})})),
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect/index.vue')
      }
    ]
  },
  {
    path: '/login',
    component: () => import('@/views/login'),
    hidden: true
  },

  { path: '/user/password', component: Layout, hidden: true, children: [
    { path: '', component: () => import('@/views/access/PasswordPage.vue'), meta: { title: '修改密码' } }
  ] },
  { path: '/user/password-required', component: Layout, hidden: true, children: [
    { path: '', component: () => import('@/views/access/PasswordRequiredPage.vue'), meta: { title: '账号安全验证' } }
  ] },
  { path: '/demo', component: Layout, hidden: true, children: [
    { path: ':module', component: () => import('@/views/access/GuestDemoPage.vue'), meta: { title: '功能演示' } }
  ] },
  { path: '/messages', component: Layout, hidden: true, children: [
    { path: '', name: 'NoticeInbox', component: () => import('@/views/operations/NoticeInboxPage.vue'), meta: { title: '通知公告' } }
  ] },
  { path: '/', redirect: '/drilling/realtime', hidden: true },
  { path: '/index', redirect: '/drilling/realtime', hidden: true },
  {
    path: "/:pathMatch(.*)*",
    component: () => import('@/views/error/404'),
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401'),
    hidden: true
  },
  {
    path: '/lock',
    component: () => import('@/views/lock'),
    hidden: true,
    meta: { title: '锁定屏幕' }
  },
  {
    path: '/user',
    component: Layout,
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'profile/:activeTab?',
        component: () => import('@/views/access/ProfilePage.vue'),
        name: 'Profile',
        meta: { title: '个人中心', icon: 'user' }
      }
    ]
  }
]

// 动态路由，基于用户权限动态去加载
export const dynamicRoutes = [
  { path: '/access', component: Layout, alwaysShow: true, roles: ['admin'], meta: { title: '系统管理', icon: 'system' }, children: [
    { path: 'organization', component: ParentView, alwaysShow: true, meta: { title: '用户与组织', icon: 'tree' }, children: [
      { path: '/access/users', name: 'DrillingUsers', component: () => import('@/views/access/UsersPage.vue'), meta: { title: '用户管理', icon: 'user' } },
      { path: '/access/departments', name: 'DrillingDepartments', component: () => import('@/views/access/organization/DepartmentsPage.vue'), meta: { title: '部门管理', icon: 'tree' } },
      { path: '/access/posts', name: 'DrillingPosts', component: () => import('@/views/access/organization/PostsPage.vue'), meta: { title: '岗位管理', icon: 'post' } }
    ] },
    { path: 'permissions', component: ParentView, alwaysShow: true, meta: { title: '角色与权限', icon: 'lock' }, children: [
      { path: '/access/roles', name: 'DrillingRoles', component: () => import('@/views/access/RolesPage.vue'), meta: { title: '角色管理', icon: 'peoples' } },
      { path: '/access/data-scope', name: 'DrillingDataScope', component: () => import('@/views/access/organization/DataScopePage.vue'), meta: { title: '数据权限', icon: 'lock' } },
      { path: '/access/menus', name: 'DrillingMenus', component: () => import('@/views/access/organization/MenusPage.vue'), meta: { title: '菜单管理', icon: 'tree-table' } }
    ] },
    { path: 'configuration', component: ParentView, alwaysShow: true, meta: { title: '配置与公告', icon: 'system' }, children: [
      { path: '/access/dictionaries', name: 'DrillingDictionaries', component: () => import('@/views/operations/DictionariesPage.vue'), meta: { title: '字典管理', icon: 'dict' } },
      { path: '/access/settings', name: 'DrillingSystemSettings', component: () => import('@/views/operations/SettingsPage.vue'), meta: { title: '参数配置', icon: 'edit' } },
      { path: '/access/notices', name: 'DrillingNoticesAdmin', component: () => import('@/views/operations/NoticesAdminPage.vue'), meta: { title: '通知公告', icon: 'message' } }
    ] },
    { path: 'activity', component: ParentView, alwaysShow: true, meta: { title: '日志与会话', icon: 'log' }, children: [
      { path: '/access/login-logs', name: 'DrillingLoginLogs', component: () => import('@/views/operations/LoginLogsPage.vue'), meta: { title: '登录日志', icon: 'logininfor' } },
      { path: '/access/sessions', name: 'DrillingSessions', component: () => import('@/views/operations/SessionsPage.vue'), meta: { title: '在线用户', icon: 'online' } },
      { path: '/access/audit', name: 'DrillingAccountAudit', component: () => import('@/views/operations/UnifiedAuditPage.vue'), meta: { title: '操作日志', icon: 'log' } }
    ] }
  ] },
  { path: '/operations', component: Layout, alwaysShow: true, roles: ['admin'], meta: { title: '运行维护', icon: 'monitor' }, children: [
    { path: 'monitor', name: 'DrillingOperationsMonitor', component: () => import('@/views/operations/MonitorPage.vue'), meta: { title: '运行监控', icon: 'server' } },
    { path: 'maintenance', name: 'DrillingMaintenance', component: () => import('@/views/operations/MaintenancePage.vue'), meta: { title: '后台维护任务', icon: 'job' } }
  ] },
  {
    path: '/system/user-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:user:edit'],
    children: [
      {
        path: 'role/:userId(\\d+)',
        component: () => import('@/views/system/user/authRole'),
        name: 'AuthRole',
        meta: { title: '分配角色', activeMenu: '/system/user' }
      }
    ]
  },
  {
    path: '/system/role-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:role:edit'],
    children: [
      {
        path: 'user/:roleId(\\d+)',
        component: () => import('@/views/system/role/authUser'),
        name: 'AuthUser',
        meta: { title: '分配用户', activeMenu: '/system/role' }
      }
    ]
  },
  {
    path: '/system/dict-data',
    component: Layout,
    hidden: true,
    permissions: ['system:dict:list'],
    children: [
      {
        path: 'index/:dictId(\\d+)',
        component: () => import('@/views/system/dict/data'),
        name: 'Data',
        meta: { title: '字典数据', activeMenu: '/system/dict' }
      }
    ]
  },
  {
    path: '/monitor/job-log',
    component: Layout,
    hidden: true,
    permissions: ['monitor:job:list'],
    children: [
      {
        path: 'index/:jobId(\\d+)',
        component: () => import('@/views/monitor/job/log'),
        name: 'JobLog',
        meta: { title: '调度日志', activeMenu: '/monitor/job' }
      }
    ]
  },
  {
    path: '/tool/gen-edit',
    component: Layout,
    hidden: true,
    permissions: ['tool:gen:edit'],
    children: [
      {
        path: 'index/:tableId(\\d+)',
        component: () => import('@/views/tool/gen/editTable'),
        name: 'GenEdit',
        meta: { title: '修改生成配置', activeMenu: '/tool/gen' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0 }
  },
})

export default router
