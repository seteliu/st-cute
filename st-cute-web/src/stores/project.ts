import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getProjects, saveProject, deleteProjectById, updateProjectExpanded, setActiveProject } from '@/api/project'
import { Project } from '@/types'
import { useConversationStore } from './conversation'

export const useProjectStore = defineStore('project', () => {
  const projectList = ref<Project[]>([])
  const activeProjectId = ref<number | null>(null)

  const loadProjects = async () => {
    try {
      const data = await getProjects()
      projectList.value = data
      if (data.length > 0) {
        if (activeProjectId.value === null || !data.some(p => p.id === activeProjectId.value)) {
          // 容错逻辑：筛选出 active === true 的项目，若有多个按 id 降序排列取 id 最大的一个
          const activeProjects = data.filter(p => p.active === true)
          if (activeProjects.length > 0) {
            activeProjects.sort((a, b) => b.id - a.id)
            activeProjectId.value = activeProjects[0].id
          } else {
            // 兜底逻辑：当 active 全部为 0 时，按 id 降序排列，默认选中 id 最大的项目并保存落盘
            const sortedData = [...data].sort((a, b) => b.id - a.id)
            await changeActiveProject(sortedData[0].id)
          }
        }
      } else {
        activeProjectId.value = null
      }
    } catch (e) {
      console.error('加载项目列表失败:', e)
    }
  }

  const changeActiveProject = async (id: number) => {
    try {
      activeProjectId.value = id
      projectList.value.forEach(p => {
        p.active = (p.id === id)
      })
      await setActiveProject(id)
    } catch (e) {
      console.error('更新活跃项目失败:', e)
    }
  }

  const handleSaveProject = async (project: Partial<Project>) => {
    try {
      const saved = await saveProject(project)
      await loadProjects()
      await changeActiveProject(saved.id)
      return saved;
    } catch (e: any) {
      console.error('保存项目失败:', e)
      throw e
    }
  }

  const handleDeleteProject = async (id: number) => {
    try {
      await deleteProjectById(id)
      
      const conversationStore = useConversationStore()
      if (activeProjectId.value === id) {
        activeProjectId.value = null
      }
      
      await loadProjects()
      // 项目级联删除，需要重新加载会话列表
      await conversationStore.loadConversations()
    } catch (e) {
      console.error('删除项目失败:', e)
    }
  }

  const handleUpdateExpanded = async (id: number, expanded: boolean) => {
    try {
      const proj = projectList.value.find(p => p.id === id)
      if (proj) {
        proj.expanded = expanded
      }
      await updateProjectExpanded(id, expanded)
    } catch (e) {
      console.error('更新项目展开状态失败:', e)
    }
  }

  return {
    projectList,
    activeProjectId,
    loadProjects,
    changeActiveProject,
    handleSaveProject,
    handleDeleteProject,
    handleUpdateExpanded
  }
})
