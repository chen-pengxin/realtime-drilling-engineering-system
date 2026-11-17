import { navigation } from '@/api/organization'
import auth from '@/plugins/auth'
import useUserStore from './user'
import { moduleForPath, moduleLabels } from '@/security/access'
import { constantRoutes, dynamicRoutes } from '@/router'
import Layout from '@/layout/index'
import ParentView from '@/components/ParentView'
import InnerLink from '@/layout/components/InnerLink'

// 匹配views里面所有的.vue文件
const modules = import.meta.glob('./../../views/**/*.vue')

// The drilling menu is registered locally and can also be returned by an
// older server-side menu snapshot.  Collapse duplicate route paths before
// handing the list to Element Plus so the sidebar never renders two copies of
// the same root or child entry.
function uniqueRoutes(routes = []) {
  const byPath = new Map()
  routes.filter(Boolean).forEach(route => {
    const children = route.children ? uniqueRoutes(route.children) : route.children
    const normalized = children ? { ...route, children } : route
    const key = String(route.path || '/').replace(/^\/+/, '/').replace(/\/+$/, '') || '/'
    const existing = byPath.get(key)
    if (!existing) {
      byPath.set(key, normalized)
      return
    }
    if (existing.children || normalized.children) {
      const mergedChildren = uniqueRoutes([...(existing.children || []), ...(normalized.children || [])])
      byPath.set(key, { ...existing, children: mergedChildren })
    }
  })
  return Array.from(byPath.values())
}

const usePermissionStore = defineStore(
  'permission',
  {
    state: () => ({
      disabledPaths: [],
      routes: [],
      addRoutes: [],
      defaultRoutes: [],
      topbarRouters: [],
      sidebarRouters: []
    }),
    actions: {
      setRoutes(routes) {
        this.addRoutes = uniqueRoutes(routes)
        this.routes = uniqueRoutes(constantRoutes.concat(routes))
      },
      setDefaultRoutes(routes) {
        this.defaultRoutes = uniqueRoutes(constantRoutes.concat(routes))
      },
      setTopbarRoutes(routes) {
        this.topbarRouters = uniqueRoutes(routes)
      },
      setSidebarRouters(routes) {
        this.sidebarRouters = uniqueRoutes(routes)
      },
      async generateRoutes() {
        // The drilling application defines its task/basic navigation locally.
        // The Java service uses session authentication, so keep these routes local.
        const asyncRoutes = filterDynamicRoutes(dynamicRoutes)
        this.setRoutes(asyncRoutes)
        const user = useUserStore()
        const config = user.demoOnly || user.mustChangePassword ? [] : (await navigation()).data
        this.disabledPaths = config.filter(n=>n.effectiveStatus==='1').map(n=>n.routePath)
        const visible = user.demoOnly ? [{ path: '/demo', component: Layout, alwaysShow: true, meta: { title: '钻井工程', icon: 'chart' }, children: user.allowedModules.map(key => ({ path: key, meta: { title: moduleLabels[key], icon: 'monitor' } })) }] : visibleRoutes(constantRoutes)
        const menu = config.length ? configuredNavigation(visible.concat(asyncRoutes), config) : visible.concat(asyncRoutes)
        this.setSidebarRouters(uniqueRoutes(menu))
        this.defaultRoutes = uniqueRoutes(menu)
        this.setTopbarRoutes(menu)
        return Promise.resolve(asyncRoutes)
      }
    }
  })

// 遍历后台传来的路由字符串，转换为组件对象
function filterAsyncRouter(asyncRouterMap, lastRouter = false, type = false) {
  return asyncRouterMap.filter(route => {
    if (type && route.children) {
      route.children = filterChildren(route.children)
    }
    if (route.component) {
      // Layout ParentView 组件特殊处理
      if (route.component === 'Layout') {
        route.component = Layout
      } else if (route.component === 'ParentView') {
        route.component = ParentView
      } else if (route.component === 'InnerLink') {
        route.component = InnerLink
      } else {
        route.component = loadView(route.component)
      }
    }
    if (route.children != null && route.children && route.children.length) {
      route.children = filterAsyncRouter(route.children, route, type)
    } else {
      delete route['children']
      delete route['redirect']
    }
    return true
  })
}

function filterChildren(childrenMap, lastRouter = false) {
  var children = []
  childrenMap.forEach(el => {
    el.path = lastRouter ? lastRouter.path + '/' + el.path : el.path
    if (el.children && el.children.length && el.component === 'ParentView') {
      children = children.concat(filterChildren(el.children, el))
    } else {
      children.push(el)
    }
  })
  return children
}

// 动态路由遍历，验证是否具备权限
export function filterDynamicRoutes(routes) {
  const res = []
  routes.forEach(route => {
    if (route.permissions) {
      if (auth.hasPermiOr(route.permissions)) {
        res.push(route)
      }
    } else if (route.roles) {
      if (auth.hasRoleOr(route.roles)) {
        res.push(route)
      }
    }
  })
  return res
}

export const loadView = (view) => {
  let res
  for (const path in modules) {
    const dir = path.split('views/')[1].split('.vue')[0]
    if (dir === view) {
      res = () => modules[path]()
    }
  }
  return res
}

export default usePermissionStore

function visibleRoutes(routes, parent = '') {
  const user = useUserStore()
  return routes.flatMap(route => {
    const path = route.path.startsWith('/') ? route.path : `${parent}/${route.path}`
    const module = moduleForPath(path)
    if (route.meta?.modules ? !route.meta.modules.some(key=>user.allowedModules.includes(key)) : module && !user.allowedModules.includes(module)) return []
    const copy = { ...route }
    if (copy.children) {
      copy.children = visibleRoutes(copy.children, path)
      if (!copy.children.length && !copy.hidden) return []
    }
    return [copy]
  })
}

// Navigation metadata changes the menu tree; executable components remain in the local registry.
function configuredNavigation(routes, config) {
  // Older drilling menu rows used Font Awesome names ("warning" or
  // "fa fa-warning").  The Vue client renders the RuoYi SVG icon set, so
  // normalize those legacy values before they reach SidebarItem.
  const normalizeIcon = (icon, path) => {
    const value = String(icon || '').trim()
    // The anti-collision page has one canonical RuoYi icon. This also
    // repairs legacy values such as `#` or an arbitrary Font Awesome class.
    if (path === '/drilling/tasks/anticollision') return 'form'
    return value
  }
  const registry=new Map()
  function collect(nodes,parent=''){for(const n of nodes){const path=n.path.startsWith('/')?n.path:`${parent}/${n.path}`;registry.set(path,n);if(n.children)collect(n.children,path)}}
  collect(routes)
  const map=new Map()
  for(const n of config){
    if(n.effectiveStatus==='1')continue
    const local=registry.get(n.routePath)
    // These are bookmark redirects after contextual consolidation. A stale
    // saved menu must not resurrect them as orphaned top-level functions.
    if(local?.redirect&&local.hidden)continue
    if(!local&&!n.routePath?.startsWith('/navigation/'))continue
    const icon = normalizeIcon(n.icon, n.routePath)
    if(local?.meta){local.meta.title=n.menuName;local.meta.icon=icon}
    map.set(n.menuId,{...local,path:n.routePath,hidden:n.visible==='1',alwaysShow:local?.alwaysShow??true,meta:{...local?.meta,title:n.menuName,icon},component:local?.component||ParentView,children:[],orderNum:n.orderNum,parentId:n.parentId,directory:n.menuType==='M'})
  }
  const roots=[]
  for(const n of map.values()){const parent=map.get(n.parentId);if(parent)parent.children.push(n);else roots.push(n)}
  function clean(nodes){return nodes.sort((a,b)=>a.orderNum-b.orderNum).flatMap(n=>{n.children=clean(n.children);if(!n.children.length){delete n.children;n.alwaysShow=false;if(n.directory||n.path.startsWith('/navigation/')||['/drilling','/drilling/tasks','/drilling/basic','/drilling/tasks/prediction','/access','/operations'].includes(n.path))return []}if(n.children?.every(c=>c.hidden))n.hidden=true;return [n]})}
  return clean(roots)
}
