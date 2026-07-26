import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getProjects, saveProject, deleteProjectById } from '@/api/project'
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
          activeProjectId.value = data[0].id
        }
      } else {
        activeProjectId.value = null
      }
    } catch (e) {
      console.error('加载项目列表失败:', e)
    }
  }

  const handleSaveProject = async (project: Partial<Project>) => {
    try {
      const saved = await saveProject(project)
      await loadProjects()
      activeProjectId.value = saved.id
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

  return {
    projectList,
    activeProjectId,
    loadProjects,
    handleSaveProject,
    handleDeleteProject
  }
})
